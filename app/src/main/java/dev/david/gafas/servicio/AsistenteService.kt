package dev.david.gafas.servicio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.PowerManager
import dev.david.gafas.MainActivity
import dev.david.gafas.R
import dev.david.gafas.audio.Frases
import dev.david.gafas.audio.Microfono
import dev.david.gafas.audio.ModeloVosk
import dev.david.gafas.audio.Oido
import dev.david.gafas.audio.RutaAudio
import dev.david.gafas.audio.Transcriptor
import dev.david.gafas.audio.Voz
import dev.david.gafas.datos.Historial
import dev.david.gafas.datos.Rol
import dev.david.gafas.gafas.Gafas
import dev.david.gafas.herramientas.Catalogo
import dev.david.gafas.ia.Agente
import dev.david.gafas.ia.ClaudeApi
import dev.david.gafas.ia.Instrucciones
import dev.david.gafas.ia.Vision
import dev.david.gafas.nucleo.Ajustes
import dev.david.gafas.nucleo.Diario
import dev.david.gafas.nucleo.Entrada
import dev.david.gafas.nucleo.Estado
import dev.david.gafas.nucleo.Fase
import dev.david.gafas.nucleo.ModoEscucha
import dev.david.gafas.nucleo.RespuestaParcial
import dev.david.gafas.nucleo.Texto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * EL DIRECTOR DE ORQUESTA. Vive en primer plano (con notificación) para seguir funcionando
 * con la pantalla apagada, y conecta todas las piezas:
 *
 *   Microfono ─audio─▶ Oido ─"oye claudio" + orden─▶ Agente (Claude) ─frases─▶ Voz ─▶ gafas
 *                                                       │
 *                                                       └─▶ herramientas (cámara, calendario…)
 *
 * Es una máquina de estados ([Fase]) con una sola regla de oro: en cada momento hay como
 * mucho UN turno de conversación corriendo ([turno]), y siempre se puede cancelar. Eso es
 * lo que permite interrumpir a Claude a mitad de frase sin que nada quede a medias.
 */
class AsistenteService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mic = Microfono()
    private val candadoAudio = Mutex()

    private lateinit var ajustes: Ajustes
    private lateinit var ruta: RutaAudio
    private lateinit var voz: Voz
    private lateinit var catalogo: Catalogo
    private lateinit var agente: Agente
    private lateinit var transcriptor: Transcriptor

    private var oido: Oido? = null
    private var turno: Job? = null
    private var despierto: PowerManager.WakeLock? = null

    private var arrancado = false
    private var listo = false
    private var audioAbierto = false
    private var porGafas = false
    private var sinFrase = false
    private var reconexionPendiente = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ajustes = Ajustes.de(this)
        ruta = RutaAudio(this)
        voz = Voz(this)
        catalogo = Catalogo(this)
        agente = Agente(ajustes) { catalogo.herramientasActivas() }
        transcriptor = Transcriptor(ajustes)
        instancia = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACCION_DETENER -> stopSelf()
            ACCION_HABLAR -> if (listo) {
                if (ajustes.modoParcial) fotografiar() else hablar()
            } else if (!arrancado) {
                stopSelf()
            }
            ACCION_CALLAR -> if (listo) callar() else if (!arrancado) stopSelf()
            // Siempre foto → respuesta corta, esté o no prendido el modo parcial (acceso directo del botón lateral)
            ACCION_FOTO -> if (listo) fotografiar() else if (!arrancado) stopSelf()
            ACCION_TEXTO -> {
                val texto = intent.getStringExtra(EXTRA_TEXTO).orEmpty().trim()
                if (listo && texto.isNotEmpty()) preguntarPorEscrito(texto) else if (!arrancado) stopSelf()
            }
            else -> if (!arrancado) {
                arrancado = true
                try {
                    var tipos = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    // Para que la ubicación funcione con la pantalla apagada, Android exige declararlo aquí
                    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        tipos = tipos or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    }
                    startForeground(ID_NOTIFICACION, notificacion(Fase.PREPARANDO), tipos)
                } catch (e: Exception) {
                    fallar("Android no dejó iniciar el asistente: ${e.message}")
                    return START_NOT_STICKY
                }
                scope.launch { Estado.fase.collect { actualizarNotificacion(it) } }
                scope.launch { arrancar() }
            }
        }
        return START_NOT_STICKY
    }

    // ───────────────────────── Arranque ─────────────────────────

    private suspend fun arrancar() {
        Estado.fase.value = Fase.PREPARANDO
        Estado.detalle.value = "Preparando el asistente…"
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            Gafas.inicializar(applicationContext)
        }
        if (ajustes.apiKey.isBlank()) return fallar("Falta tu llave de Claude. Ponla en Ajustes.")

        val modelo = try {
            ModeloVosk.cargar(this) { porcentaje ->
                Estado.descargaModelo.value = porcentaje
                if (porcentaje != null) Estado.detalle.value = "Descargando el oído offline (solo esta vez): $porcentaje %"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fallar("No pude preparar el reconocimiento de voz. La primera vez necesita internet. (${e.message})")
        }
        Estado.detalle.value = "Preparando la voz…"
        if (!voz.preparar(ajustes.voz, ajustes.velocidadVoz)) {
            return fallar("El celular no tiene un motor de voz activo. Revisa Ajustes del sistema → Texto a voz.")
        }
        val nuevoOido = try {
            Oido(modelo, ajustes.listaDeFrases) { evento -> scope.launch { alOir(evento) } }
        } catch (e: Exception) {
            return fallar("No pude iniciar el oído con la frase \"${ajustes.frases}\": ${e.message}")
        }
        nuevoOido.grabar = transcriptor.activo
        oido = nuevoOido
        ruta.vigilar { scope.launch { alCambiarRuta() } }
        listo = true
        vigilarModoParcial()

        if (ajustes.modoEscucha == ModoEscucha.SIEMPRE) {
            val abrio = candadoAudio.withLock { abrirAudio() }
            if (!abrio) return fallar("No pude abrir el micrófono. ¿Otra app lo está usando?")
        } else {
            voz.salida(porGafas = false)
        }
        Diario.info("Asistente listo · ${ajustes.modelo} · ${catalogo.herramientasActivas().size} herramientas")
        reposar()
    }

    /** Cuando prendes el modo parcial, la sesión con la cámara se abre de una vez y se mantiene. */
    private fun vigilarModoParcial() {
        var antes = false
        scope.launch {
            ajustes.version.collect {
                val ahora = ajustes.modoParcial
                if (ahora == antes) return@collect
                antes = ahora
                catalogo.camara.mantenerSesion = ahora
                if (ahora) launch { catalogo.camara.precalentar() }
                actualizarNotificacion(Estado.fase.value)
                if (Estado.fase.value == Fase.ESPERANDO) reposar() // refresca la pista bajo el orbe
            }
        }
    }

    // ───────────────────────── Audio ─────────────────────────

    /** Abre el canal hacia las gafas (si están) y el micrófono. Llamar siempre con [candadoAudio] tomado. */
    private suspend fun abrirAudio(): Boolean {
        val escucha = oido ?: return false
        val microfonoDeGafas = ruta.activar()
        porGafas = microfonoDeGafas != null
        voz.salida(porGafas)
        val abrio = mic.abrir(microfonoDeGafas, escucha::procesar) { scope.launch { alCaerMicrofono() } }
        audioAbierto = abrio
        Estado.entrada.value = when {
            !abrio -> Entrada.NINGUNA
            porGafas -> Entrada.GAFAS
            else -> Entrada.TELEFONO
        }
        if (abrio) {
            mantenerDespierto(true)
            Diario.info(if (porGafas) "Escuchando por las gafas" else "No vi las gafas: escucho por el celular")
        } else {
            ruta.liberar()
        }
        return abrio
    }

    private fun cerrarAudio() {
        mic.cerrar()
        ruta.liberar()
        audioAbierto = false
        porGafas = false
        Estado.entrada.value = Entrada.NINGUNA
        mantenerDespierto(false)
    }

    /** Con la pantalla apagada el procesador se duerme; mientras escuchamos hay que impedirlo. */
    private fun mantenerDespierto(activo: Boolean) {
        if (activo) {
            if (despierto?.isHeld == true) return
            despierto = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gafasclaude:escucha")
                .apply { acquire(12 * 60 * 60 * 1000L) }
        } else {
            despierto?.let { if (it.isHeld) it.release() }
            despierto = null
        }
    }

    /** El sistema nos quitó el micrófono: entró una llamada, se apagaron las gafas… */
    private suspend fun alCaerMicrofono() {
        Diario.info("Se perdió el micrófono")
        audioAbierto = false
        Estado.entrada.value = Entrada.NINGUNA
        if (ajustes.modoEscucha == ModoEscucha.SIEMPRE) {
            reconectar()
        } else {
            cerrarAudio()
            if (Estado.fase.value == Fase.ESCUCHANDO) reposar()
        }
    }

    /** Se conectaron o desconectaron las gafas: cambiamos de micrófono sin que tengas que hacer nada. */
    private suspend fun alCambiarRuta() {
        delay(1_500) // Bluetooth tarda en estabilizarse
        if (!listo || ajustes.modoEscucha != ModoEscucha.SIEMPRE) return
        if (ruta.hayGafas() == porGafas && audioAbierto) return
        if (Estado.fase.value == Fase.ESPERANDO) reconectar() else reconexionPendiente = true
    }

    private suspend fun reconectar() {
        candadoAudio.withLock {
            if (!listo) return
            if (Estado.fase.value.enTurno) {
                reconexionPendiente = true
                return
            }
            // Varios avisos de Bluetooth pueden llegar seguidos: si otro ya dejó todo bien, no hay nada que hacer
            if (audioAbierto && mic.abierto && ruta.hayGafas() == porGafas) return
            Estado.fase.value = Fase.PREPARANDO
            Estado.detalle.value = "Reconectando el audio…"
            oido?.pedir(Oido.Modo.DORMIDO)
            cerrarAudio()
            var espera = 800L
            repeat(8) {
                delay(espera)
                espera = minOf(espera * 2, 15_000L)
                while (ruta.enLlamada()) {
                    Estado.detalle.value = "Estás en una llamada. Vuelvo cuando termines."
                    delay(2_000)
                }
                if (abrirAudio()) {
                    reposar()
                    return
                }
            }
        }
        fallar("Perdí el micrófono y no logré recuperarlo. Toca Iniciar para volver.")
    }

    // ───────────────────────── Conversación ─────────────────────────

    /** Vuelve al estado de espera: escuchando la frase, o quieto hasta que toques el botón. */
    private fun reposar() {
        Estado.parcial.value = ""
        Estado.respuestaViva.value = ""
        sinFrase = false
        if (reconexionPendiente) {
            reconexionPendiente = false
            scope.launch { reconectar() }
            return
        }
        Estado.fase.value = Fase.ESPERANDO
        val accion = if (ajustes.modoParcial) "tomar la foto" else "hablar"
        if (ajustes.modoEscucha == ModoEscucha.SIEMPRE) {
            Estado.detalle.value = "Di «${ajustes.listaDeFrases.first()}» o toca el orbe para $accion"
            oido?.pedir(Oido.Modo.ESPERANDO_FRASE)
        } else {
            // Modo botón: entre pregunta y pregunta el micrófono queda cerrado del todo
            oido?.pedir(Oido.Modo.DORMIDO)
            if (audioAbierto) cerrarAudio()
            voz.salida(porGafas = false)
            Estado.detalle.value = "Toca el orbe para $accion"
        }
    }

    /** Botón "Hablar": abre la escucha sin frase. Si Claude estaba hablando, lo interrumpe. */
    private fun hablar() {
        when (Estado.fase.value) {
            Fase.PREPARANDO, Fase.APAGADO, Fase.ERROR -> return
            Fase.ESCUCHANDO -> {
                // Tocar otra vez mientras escucha = "ya, olvídalo"
                oido?.pedir(Oido.Modo.DORMIDO)
                reposar()
                return
            }
            else -> interrumpir()
        }
        scope.launch {
            if (!audioAbierto) {
                val abrio = candadoAudio.withLock { audioAbierto || abrirAudio() }
                if (!abrio) {
                    Estado.avisar("No pude abrir el micrófono")
                    return@launch
                }
            }
            sonar(Voz.Sonido.TE_ESCUCHO)
            sinFrase = false
            Estado.parcial.value = ""
            Estado.respuestaViva.value = ""
            Estado.detalle.value = ""
            Estado.fase.value = Fase.ESCUCHANDO
            oido?.pedir(Oido.Modo.ESCUCHANDO_ORDEN)
        }
    }

    /** Botón "Callar": corta la respuesta y vuelve a esperar. */
    private fun callar() {
        if (!Estado.fase.value.enTurno) return
        interrumpir()
        reposar()
    }

    private fun interrumpir() {
        turno?.cancel()
        turno = null
        voz.callar()
    }

    private fun preguntarPorEscrito(texto: String) {
        if (Estado.fase.value == Fase.PREPARANDO) return
        interrumpir()
        oido?.pedir(Oido.Modo.DORMIDO)
        empezarTurno(texto, audio = null, sinFrase = false)
    }

    /** Lo que el oído va reportando. Siempre llega en el hilo principal y en orden. */
    private fun alOir(evento: Oido.Evento) {
        if (!listo) return
        when (evento) {
            Oido.Evento.Despertado -> {
                // En modo parcial la frase no abre la escucha: dispara la foto de una vez
                if (ajustes.modoParcial) return fotografiar()
                if (Estado.fase.value.enTurno) interrumpir() // dijiste la frase mientras Claude hablaba
                sonar(Voz.Sonido.TE_ESCUCHO)
                sinFrase = false
                Estado.parcial.value = ""
                Estado.respuestaViva.value = ""
                Estado.detalle.value = ""
                Estado.fase.value = Fase.ESCUCHANDO
            }
            is Oido.Evento.Parcial -> if (Estado.fase.value == Fase.ESCUCHANDO) Estado.parcial.value = evento.texto
            Oido.Evento.Silencio -> if (Estado.fase.value == Fase.ESCUCHANDO) {
                if (!sinFrase) sonar(Voz.Sonido.NADA)
                reposar()
            }
            is Oido.Evento.Orden -> if (Estado.fase.value == Fase.ESCUCHANDO) {
                empezarTurno(evento.texto, evento.audio, sinFrase)
            }
        }
    }

    // ───────────────────────── Modo parcial ─────────────────────────

    /** Foto → respuesta. Si ya había una respuesta en curso, la corta y toma una foto nueva. */
    private fun fotografiar() {
        when (Estado.fase.value) {
            Fase.PREPARANDO, Fase.APAGADO, Fase.ERROR -> return
            else -> interrumpir()
        }
        turno = scope.launch { responderFoto() }
    }

    private suspend fun responderFoto() {
        val inicio = System.currentTimeMillis()
        val esteTurno = currentCoroutineContext()[Job]
        fun vigente() = esteTurno?.isActive == true
        fun segundos() = (System.currentTimeMillis() - inicio) / 1000f

        // Si no hay gafas conectadas, todo queda en la pantalla: que el parlante del celular
        // no se ponga a hablar en medio de una evaluación.
        val enVoz = (porGafas && audioAbierto) || ruta.hayGafas()
        fun decir(frase: String) {
            if (!vigente() || !enVoz) return
            Estado.fase.value = Fase.HABLANDO
            voz.decir(frase)
        }
        fun tono(sonido: Voz.Sonido) {
            if (enVoz) sonar(sonido)
        }

        try {
            // Mientras responde, decir la frase otra vez toma una foto nueva
            oido?.pedir(if (ajustes.interrumpible && audioAbierto) Oido.Modo.ESPERANDO_FRASE else Oido.Modo.DORMIDO)
            Estado.parcial.value = ""
            Estado.respuestaViva.value = ""
            Estado.fase.value = Fase.ACTUANDO
            Estado.detalle.value = "Tomando la foto…"
            tono(Voz.Sonido.TE_ESCUCHO)

            val captura = catalogo.camara.capturar(nitida = true).getOrElse { error ->
                val mensaje = error.message ?: "No pude tomar la foto."
                Estado.respuestaParcial.value = RespuestaParcial(mensaje, null, segundos(), lista = true)
                tono(Voz.Sonido.NADA)
                decir("No pude tomar la foto.")
                if (enVoz) voz.esperarSilencio()
                reposar()
                return
            }
            // Segundo tono: la foto ya quedó, puedes mover la cabeza
            tono(Voz.Sonido.ENTENDIDO)
            Diario.info("Parcial · foto en ${"%.1f".format(segundos())} s")
            Estado.fase.value = Fase.PENSANDO
            Estado.detalle.value = ""
            Estado.respuestaParcial.value = RespuestaParcial("", captura.archivo, 0f, lista = false)

            val frases = Frases()
            val dicho = StringBuilder()
            val texto = agente.responderFoto(Agente.Foto(captura.jpegBase64, captura.archivo), ajustes.parcialPreciso) { trozo ->
                if (vigente()) {
                    dicho.append(trozo)
                    Estado.respuestaParcial.value = RespuestaParcial(dicho.toString().trim(), captura.archivo, segundos(), lista = false)
                    frases.agregar(trozo).forEach { decir(it) }
                }
            }
            frases.cortar()?.let { decir(it) }
            val respuesta = texto.ifBlank { "No encontré una respuesta en la foto." }
            if (dicho.isBlank()) decir(respuesta)

            val total = segundos()
            Estado.respuestaParcial.value = RespuestaParcial(respuesta, captura.archivo, total, lista = true)
            Historial.agregar(Rol.CLAUDE, respuesta, listOf("Modo parcial", "%.1f s".format(total)), captura.archivo)
            Diario.info("Parcial · respuesta completa en ${"%.1f".format(total)} s")
            if (enVoz) voz.esperarSilencio()
            reposar()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val explicacion = ClaudeApi.explicar(e)
            Diario.error("Falló el modo parcial", e)
            Estado.respuestaParcial.value = RespuestaParcial(explicacion, null, segundos(), lista = true)
            tono(Voz.Sonido.NADA)
            decir(explicacion)
            if (enVoz) voz.esperarSilencio()
            reposar()
        }
    }

    // ───────────────────────── Turnos normales ─────────────────────────

    private fun empezarTurno(textoOido: String, audio: ByteArray?, sinFrase: Boolean) {
        turno?.cancel()
        turno = scope.launch { responder(textoOido, audio, sinFrase) }
    }

    /** Un turno completo: de lo que oímos a la respuesta hablada. Cancelable en cualquier punto. */
    private suspend fun responder(textoOido: String, audio: ByteArray?, sinFrase: Boolean) {
        var pregunta = textoOido
        val dicho = StringBuilder()
        var hablo = false
        var completo = false
        // Los trozos de texto llegan desde un hilo de red. Si ya te interrumpieron, lo que
        // venga en camino se descarta: nada debe sonar ni cambiar el estado después del corte.
        val esteTurno = currentCoroutineContext()[Job]
        fun vigente() = esteTurno?.isActive == true

        fun pronunciar(frase: String) {
            if (!vigente() || Instrucciones.SILENCIO in frase) return
            hablo = true
            Estado.fase.value = Fase.HABLANDO
            Estado.detalle.value = ""
            voz.decir(frase)
        }

        try {
            Estado.fase.value = Fase.PENSANDO
            Estado.detalle.value = ""
            Estado.parcial.value = pregunta
            // Mientras Claude piensa y habla, el oído sigue pendiente de la frase para poder interrumpirlo
            oido?.pedir(if (ajustes.interrumpible && audioAbierto) Oido.Modo.ESPERANDO_FRASE else Oido.Modo.DORMIDO)

            if (audio != null && transcriptor.activo) {
                transcriptor.transcribir(audio)
                    ?.let { Texto.quitarFrase(it, ajustes.listaDeFrases) }
                    ?.takeIf { it.length >= 2 }
                    ?.let {
                        pregunta = it
                        Estado.parcial.value = it
                    }
            }
            val mensajeUsuario = Historial.agregar(Rol.USUARIO, pregunta)

            // Vista rápida: si es obvio que hay que mirar, tomamos la foto de una vez
            // y nos ahorramos una ida y vuelta completa con Claude.
            var foto: Agente.Foto? = null
            if (ajustes.vistaRapida && catalogo.activa(catalogo.capacidades.first { it.id == "camara" }) &&
                catalogo.camara.disponible && Vision.pideVer(pregunta)
            ) {
                Estado.fase.value = Fase.ACTUANDO
                Estado.detalle.value = catalogo.camara.enCurso
                catalogo.camara.capturar().onSuccess { foto = Agente.Foto(it.jpegBase64, it.archivo) }
                Estado.fase.value = Fase.PENSANDO
                Estado.detalle.value = ""
            }

            val frases = Frases()
            val respuesta = agente.responder(
                pregunta = pregunta,
                foto = foto,
                porGafas = porGafas && audioAbierto,
                sinFrase = sinFrase,
                oyentes = Agente.Oyentes(
                    alTexto = { trozo ->
                        dicho.append(trozo)
                        if (vigente()) Estado.respuestaViva.value = dicho.toString()
                        frases.agregar(trozo).forEach { pronunciar(it) }
                    },
                    alUsar = { etiqueta ->
                        if (vigente()) {
                            Estado.fase.value = Fase.ACTUANDO
                            Estado.detalle.value = etiqueta
                        }
                    },
                    alCorte = { frases.cortar()?.let { pronunciar(it) } },
                ),
            )
            frases.cortar()?.let { pronunciar(it) }

            if (respuesta.texto.trim() == Instrucciones.SILENCIO) {
                // Lo que se oyó no era para Claude: hacemos como si nada
                Historial.quitar(mensajeUsuario.id)
                completo = true
                reposar()
                return
            }
            if (!hablo) pronunciar(respuesta.texto)
            Historial.agregar(Rol.CLAUDE, respuesta.texto, respuesta.acciones, respuesta.foto)
            completo = true
            voz.esperarSilencio()
            Estado.respuestaViva.value = ""
            despuesDeResponder()
        } catch (e: CancellationException) {
            if (!completo && dicho.isNotBlank()) {
                Historial.agregar(Rol.CLAUDE, dicho.toString().trim() + " …")
                agente.anotarInterrumpido(pregunta, dicho.toString())
            }
            throw e
        } catch (e: Exception) {
            val explicacion = ClaudeApi.explicar(e)
            Diario.error("Falló el turno", e)
            Historial.agregar(Rol.SISTEMA, explicacion)
            Estado.respuestaViva.value = ""
            Estado.fase.value = Fase.HABLANDO
            sonar(Voz.Sonido.NADA)
            voz.decir(explicacion)
            voz.esperarSilencio()
            reposar()
        }
    }

    /** Tras responder: unos segundos para seguir la conversación sin repetir la frase. */
    private suspend fun despuesDeResponder() {
        if (!ajustes.seguirConversacion || !audioAbierto) return reposar()
        delay(250) // que se apague el eco de la última palabra antes de volver a oír
        sinFrase = true
        Estado.parcial.value = ""
        Estado.detalle.value = "Puedes seguir hablando"
        Estado.fase.value = Fase.ESCUCHANDO
        oido?.pedir(Oido.Modo.ESCUCHANDO_SEGUIMIENTO)
    }

    /** Dice algo en voz alta por iniciativa propia (un recordatorio). Espera a que haya un hueco. */
    fun anunciar(texto: String) {
        scope.launch {
            if (!listo) return@launch
            withTimeoutOrNull(120_000) { Estado.fase.first { it == Fase.ESPERANDO } } ?: return@launch
            oido?.pedir(Oido.Modo.DORMIDO)
            Estado.fase.value = Fase.HABLANDO
            Estado.detalle.value = "Recordatorio"
            sonar(Voz.Sonido.ENTENDIDO)
            voz.decir("Recordatorio: $texto")
            voz.esperarSilencio()
            if (Estado.fase.value == Fase.HABLANDO) reposar()
        }
    }

    private fun sonar(sonido: Voz.Sonido) {
        if (ajustes.sonidos) voz.sonar(sonido)
    }

    private fun fallar(mensaje: String) {
        Diario.error(mensaje)
        Estado.fase.value = Fase.ERROR
        Estado.detalle.value = mensaje
        Estado.descargaModelo.value = null
        stopSelf()
    }

    override fun onDestroy() {
        instancia = null
        listo = false
        ruta.dejarDeVigilar()
        turno?.cancel()
        mic.cerrar() // primero el micrófono: después de esto nadie más toca el oído
        ruta.liberar()
        mantenerDespierto(false)
        oido?.cerrar()
        oido = null
        voz.cerrar()
        catalogo.cerrar()
        scope.cancel()
        if (Estado.fase.value != Fase.ERROR) {
            Estado.fase.value = Fase.APAGADO
            Estado.detalle.value = ""
        }
        Estado.entrada.value = Entrada.NINGUNA
        Estado.parcial.value = ""
        Estado.respuestaViva.value = ""
        Estado.descargaModelo.value = null
        super.onDestroy()
    }

    // ───────────────────────── Notificación ─────────────────────────
    // Un servicio en primer plano está obligado a mostrar una. La aprovechamos como control remoto.

    private fun actualizarNotificacion(fase: Fase) {
        if (fase == Fase.APAGADO || fase == Fase.ERROR) return
        getSystemService(NotificationManager::class.java).notify(ID_NOTIFICACION, notificacion(fase))
    }

    private fun notificacion(fase: Fase): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, "Asistente activo", NotificationManager.IMPORTANCE_LOW),
        )
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle("Gafas + Claude")
            .setContentText(if (ajustes.modoParcial) "Modo parcial · ${fase.etiqueta}" else fase.etiqueta)
            .setOngoing(true)
            .setContentIntent(abrir)
            .addAction(accion(if (ajustes.modoParcial) "Foto" else "Hablar", ACCION_HABLAR, 1))
            .addAction(accion("Detener", ACCION_DETENER, 2))
            .build()
    }

    private fun accion(titulo: String, accion: String, codigo: Int): Notification.Action {
        val intento = PendingIntent.getService(
            this, codigo, Intent(this, AsistenteService::class.java).setAction(accion), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notificacion), titulo, intento).build()
    }

    companion object {
        const val ACCION_HABLAR = "dev.david.gafas.HABLAR"
        const val ACCION_CALLAR = "dev.david.gafas.CALLAR"
        const val ACCION_FOTO = "dev.david.gafas.FOTO"
        const val ACCION_DETENER = "dev.david.gafas.DETENER"
        const val ACCION_TEXTO = "dev.david.gafas.TEXTO"
        const val EXTRA_TEXTO = "texto"
        private const val CANAL = "asistente"
        private const val ID_NOTIFICACION = 1

        /** El servicio que está corriendo, si lo hay. Mismo proceso, así que basta una referencia. */
        @Volatile
        var instancia: AsistenteService? = null
            private set

        fun iniciar(context: Context) {
            context.startForegroundService(Intent(context, AsistenteService::class.java))
        }

        fun detener(context: Context) {
            context.stopService(Intent(context, AsistenteService::class.java))
        }

        fun enviar(context: Context, accion: String, texto: String? = null) {
            if (instancia == null) return
            val intento = Intent(context, AsistenteService::class.java).setAction(accion)
            texto?.let { intento.putExtra(EXTRA_TEXTO, it) }
            context.startService(intento)
        }
    }
}
