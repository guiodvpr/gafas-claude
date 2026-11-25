package dev.david.gafas.audio

import dev.david.gafas.nucleo.Texto
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * EL OÍDO QUE ENTIENDE. Corre 100 % en el celular, sin internet, con Vosk.
 *
 * Tiene dos "orejas" sobre el mismo modelo:
 *  - [reconFrase]: con gramática cerrada. Solo puede oír tus frases de activación o
 *    "[unk]" (cualquier otra cosa). Con tan pocas opciones gasta poco y casi no se equivoca.
 *  - [reconOrden]: libre; transcribe lo que digas después de la frase.
 *
 * Y una memoria de los últimos 2 segundos de audio ([anillo]). Sirve para dos cosas:
 *  - Que no se pierda el comienzo de la orden cuando dices todo de corrido
 *    ("oye claudio qué hora es"): al despertar, a la segunda oreja se le repite el
 *    último instante que ya había pasado.
 *  - Poder mandar el audio completo a un transcriptor en la nube, si lo activas.
 *
 * Todo [procesar] corre en el hilo del micrófono. Lo que pide el servicio desde otro hilo
 * se deja en [pedido] y se aplica al comenzar el siguiente pedazo de audio: así Vosk
 * nunca es tocado por dos hilos a la vez.
 */
class Oido(
    modelo: Model,
    private val frases: List<String>,
    private val alEvento: (Evento) -> Unit,
) {
    sealed interface Evento {
        /** Oyó la frase de activación. */
        data object Despertado : Evento

        /** Lo que va entendiendo mientras hablas. */
        data class Parcial(val texto: String) : Evento

        /** Terminaste de hablar. [audio] trae la grabación (PCM 16 kHz) solo si se pidió grabar. */
        class Orden(val texto: String, val audio: ByteArray?) : Evento

        /** Se abrió la escucha pero nadie dijo nada. */
        data object Silencio : Evento
    }

    enum class Modo { DORMIDO, ESPERANDO_FRASE, ESCUCHANDO_ORDEN, ESCUCHANDO_SEGUIMIENTO }

    /** Si está encendido, cada orden viene con su audio (para el transcriptor en la nube). */
    @Volatile var grabar = false

    private val frasesNormalizadas = frases.map { Texto.normalizar(it) }.filter { it.isNotEmpty() }
    private val reconFrase = Recognizer(modelo, TASA, JSONArray(frasesNormalizadas + "[unk]").toString())
    private val reconOrden = Recognizer(modelo, TASA)

    private val anillo = ByteArray(BYTES_ANILLO)
    private var anilloPos = 0
    private var anilloLleno = false

    private var modo = Modo.DORMIDO
    private var inicio = 0L
    private var esperaSinVoz = ESPERA_ORDEN_MS
    private var oyoAlgo = false
    private var ultimoParcial = ""
    private var coincidencias = 0
    private val grabacion = ByteArrayOutputStream()

    @Volatile private var pedido: Modo? = null

    /** Lo llama el servicio desde cualquier hilo. */
    fun pedir(nuevo: Modo) {
        pedido = nuevo
    }

    /** Lo llama el micrófono con cada pedazo de 100 ms. */
    fun procesar(audio: ByteArray, largo: Int) {
        pedido?.let {
            pedido = null
            cambiarA(it, conAnillo = false)
        }
        // Primero al anillo: si este pedazo es el que despierta, ya queda incluido en la repetición
        guardarEnAnillo(audio, largo)
        when (modo) {
            Modo.DORMIDO -> Unit
            Modo.ESPERANDO_FRASE -> buscarFrase(audio, largo)
            Modo.ESCUCHANDO_ORDEN, Modo.ESCUCHANDO_SEGUIMIENTO -> escucharOrden(audio, largo)
        }
    }

    private fun cambiarA(nuevo: Modo, conAnillo: Boolean) {
        when (nuevo) {
            Modo.ESPERANDO_FRASE -> {
                reconFrase.reset()
                coincidencias = 0
            }
            Modo.ESCUCHANDO_ORDEN, Modo.ESCUCHANDO_SEGUIMIENTO -> {
                reconOrden.reset()
                grabacion.reset()
                inicio = System.currentTimeMillis()
                esperaSinVoz = if (nuevo == Modo.ESCUCHANDO_SEGUIMIENTO) ESPERA_SEGUIMIENTO_MS else ESPERA_ORDEN_MS
                oyoAlgo = false
                ultimoParcial = ""
                if (conAnillo) {
                    // A Vosk le repetimos solo el último instante; a la grabación, también la frase
                    // (un transcriptor en la nube entiende mejor con contexto, y luego se recorta).
                    val reciente = ultimosBytes(BYTES_PREVIOS_VOSK)
                    reconOrden.acceptWaveForm(reciente, reciente.size)
                    if (grabar) grabacion.write(ultimosBytes(BYTES_PREVIOS_GRABACION))
                }
            }
            Modo.DORMIDO -> Unit
        }
        modo = nuevo
    }

    private fun buscarFrase(audio: ByteArray, largo: Int) {
        val termino = reconFrase.acceptWaveForm(audio, largo)
        val texto = Texto.normalizar(if (termino) leer(reconFrase.result, "text") else leer(reconFrase.partialResult, "partial"))
        val coincide = Texto.contieneFrase(texto, frasesNormalizadas)
        // Un resultado final basta. Un parcial debe sostenerse dos pedazos seguidos (200 ms):
        // eso descarta casi todos los falsos positivos de una palabra parecida dicha al pasar.
        coincidencias = if (coincide) coincidencias + 1 else 0
        if (coincide && (termino || coincidencias >= 2)) {
            cambiarA(Modo.ESCUCHANDO_ORDEN, conAnillo = true)
            alEvento(Evento.Despertado)
        }
    }

    private fun escucharOrden(audio: ByteArray, largo: Int) {
        if (grabar && grabacion.size() < MAXIMO_GRABACION) grabacion.write(audio, 0, largo)
        val transcurrido = System.currentTimeMillis() - inicio

        // acceptWaveForm devuelve true cuando Vosk detecta que terminaste la frase (un silencio)
        if (reconOrden.acceptWaveForm(audio, largo)) {
            val texto = Texto.quitarFrase(leer(reconOrden.result, "text"), frases)
            if (texto.length >= 2) {
                terminar(Evento.Orden(texto, audioGrabado()))
                return
            }
        } else {
            val parcial = Texto.quitarFrase(leer(reconOrden.partialResult, "partial"), frases)
            if (parcial.isNotBlank() && parcial != ultimoParcial) {
                oyoAlgo = true
                ultimoParcial = parcial
                alEvento(Evento.Parcial(parcial))
            }
        }

        when {
            !oyoAlgo && transcurrido > esperaSinVoz -> terminar(Evento.Silencio)
            transcurrido > MAXIMO_ORDEN_MS -> {
                val texto = Texto.quitarFrase(leer(reconOrden.finalResult, "text"), frases)
                terminar(if (texto.length < 2) Evento.Silencio else Evento.Orden(texto, audioGrabado()))
            }
        }
    }

    private fun terminar(evento: Evento) {
        modo = Modo.DORMIDO
        alEvento(evento)
    }

    private fun audioGrabado(): ByteArray? = if (grabar && grabacion.size() > 0) grabacion.toByteArray() else null

    private fun guardarEnAnillo(audio: ByteArray, largo: Int) {
        var copiados = 0
        while (copiados < largo) {
            val n = minOf(largo - copiados, anillo.size - anilloPos)
            System.arraycopy(audio, copiados, anillo, anilloPos, n)
            anilloPos = (anilloPos + n) % anillo.size
            if (anilloPos == 0) anilloLleno = true
            copiados += n
        }
    }

    /** Los últimos [cuantos] bytes que pasaron por el micrófono, en orden. */
    private fun ultimosBytes(cuantos: Int): ByteArray {
        val disponibles = if (anilloLleno) anillo.size else anilloPos
        val n = minOf(cuantos, disponibles)
        val salida = ByteArray(n)
        val desde = (anilloPos - n + anillo.size) % anillo.size
        val primerTramo = minOf(n, anillo.size - desde)
        System.arraycopy(anillo, desde, salida, 0, primerTramo)
        if (primerTramo < n) System.arraycopy(anillo, 0, salida, primerTramo, n - primerTramo)
        return salida
    }

    fun cerrar() {
        reconFrase.close()
        reconOrden.close()
    }

    companion object {
        private const val TASA = Microfono.TASA.toFloat()
        private const val BYTES_ANILLO = Microfono.BYTES_POR_SEGUNDO * 2
        private const val BYTES_PREVIOS_VOSK = Microfono.BYTES_POR_SEGUNDO * 3 / 10 // 300 ms
        private const val BYTES_PREVIOS_GRABACION = Microfono.BYTES_POR_SEGUNDO * 3 / 2 // 1,5 s
        private const val MAXIMO_GRABACION = Microfono.BYTES_POR_SEGUNDO * 30
        private const val ESPERA_ORDEN_MS = 7_000L
        private const val ESPERA_SEGUIMIENTO_MS = 5_000L
        private const val MAXIMO_ORDEN_MS = 30_000L

        private fun leer(json: String, campo: String): String =
            runCatching { JSONObject(json).optString(campo) }.getOrDefault("").trim()
    }
}
