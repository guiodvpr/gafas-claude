package dev.david.gafas.ia

import dev.david.gafas.datos.Memoria
import dev.david.gafas.herramientas.Herramienta
import dev.david.gafas.herramientas.Resultado
import dev.david.gafas.nucleo.Ajustes
import dev.david.gafas.nucleo.Diario
import java.io.IOException
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * EL CEREBRO. Convierte una pregunta en una respuesta usando el "bucle de agente":
 *
 *   1. Le mandamos a Claude la conversación + la lista de herramientas disponibles.
 *   2. Claude va escribiendo; cada trozo de texto se entrega al instante (para hablarlo).
 *   3. Si Claude para con "tool_use", ejecutamos lo que pidió (tomar una foto, mirar el
 *      calendario…), le devolvemos el resultado y volvemos al paso 2.
 *   4. Cuando para con "end_turn", terminamos.
 *
 * Las herramientas "de servidor" (búsqueda web, leer páginas, conectores MCP) las ejecuta
 * Anthropic por su lado: aquí solo las declaramos y vemos pasar sus resultados.
 */
class Agente(
    private val ajustes: Ajustes,
    private val herramientasActivas: () -> List<Herramienta>,
) {
    /** Cómo se entera el resto de la app de lo que va pasando mientras Claude responde. */
    class Oyentes(
        /** Llegó un trocito de texto. Puede llamarse desde un hilo de red. */
        val alTexto: (String) -> Unit,
        /** Claude empezó a usar una herramienta; el texto es para mostrar ("Buscando en la web…"). */
        val alUsar: (String) -> Unit,
        /** Terminó un tramo de texto antes de una herramienta: buen momento para vaciar lo que falte por decir. */
        val alCorte: () -> Unit,
    )

    data class Respuesta(val texto: String, val acciones: List<String>, val foto: String?)

    class Foto(val jpegBase64: String, val archivo: String?)

    /**
     * Memoria corta de la conversación: solo tu pregunta y la respuesta final de cada
     * intercambio. Fotos y resultados de búsqueda no se acumulan, así cada llamada sigue
     * siendo liviana y rápida.
     */
    private val intercambios = ArrayDeque<Pair<String, String>>()
    private var ultimoUso = 0L

    /** La última foto se conserva un rato por si la siguiente pregunta es sobre lo mismo. */
    private var fotoReciente: Foto? = null
    private var fotoHora = 0L
    private var fotoUsosRestantes = 0

    /** Si la API rechaza los ajustes de pensamiento de un modelo, dejamos de mandarlos. */
    @Volatile private var sinAfinacion = false

    fun olvidarConversacion() {
        intercambios.clear()
        fotoReciente = null
    }

    /**
     * MODO PARCIAL. Una sola llamada, lo más corta posible: foto → respuesta.
     *
     * Por qué es más rápido que el modo normal:
     *  - No se mandan herramientas ni historial: la petición es solo la foto y unas reglas cortas.
     *  - No hay ida y vuelta para "decidir mirar": la foto ya va en la primera llamada.
     *  - Rápido = Haiku 5.5 con esfuerzo bajo. Preciso = Sonnet 5.5 con esfuerzo medio, que
     *    piensa un poco más antes de responder (vale la pena en cálculos o preguntas con trampa).
     *  - En los dos, el pensamiento es "adaptativo": en una pregunta fácil responde de una;
     *    solo piensa cuando la pregunta lo necesita. Ese pensamiento nunca se dice en voz alta.
     */
    suspend fun responderFoto(foto: Foto, preciso: Boolean, alTexto: (String) -> Unit): String {
        val modelo = if (preciso) Ajustes.MODELO_EQUILIBRADO else Ajustes.MODELO_RAPIDO
        val mensajes = JSONArray().put(
            JSONObject().put("role", "user").put(
                "content",
                JSONArray()
                    .put(Resultado.bloqueImagen(foto.jpegBase64))
                    .put(texto(InstruccionesParcial.PEDIDO)),
            ),
        )
        val construir = {
            val cuerpo = JSONObject()
                .put("model", modelo)
                .put("stream", true)
                // El pensamiento cuenta dentro de este límite: hay que dejarle espacio
                .put("max_tokens", if (preciso) 8192 else 4096)
                .put("system", InstruccionesParcial.sistema)
                .put("messages", mensajes)
            if (!sinAfinacion) cuerpo.put("output_config", JSONObject().put("effort", if (preciso) "medium" else "low"))
            cuerpo
        }
        val turno = transmitir(construir, emptyList()) { novedad ->
            if (novedad is Ensamblador.Novedad.Texto) alTexto(novedad.delta)
        }
        Diario.info("Parcial · $modelo · ${turno.tokensEntrada}+${turno.tokensSalida} tokens")
        return turno.texto().trim()
    }

    /** Si interrumpiste a Claude, igual queda registro de lo que alcanzó a decir. */
    fun anotarInterrumpido(pregunta: String, dicho: String) {
        if (dicho.isBlank()) return
        guardarIntercambio(pregunta, dicho.trim() + " (aquí la persona te interrumpió)")
    }

    suspend fun responder(
        pregunta: String,
        foto: Foto?,
        porGafas: Boolean,
        sinFrase: Boolean,
        oyentes: Oyentes,
    ): Respuesta {
        val inicio = System.currentTimeMillis()
        if (inicio - ultimoUso > OLVIDAR_TRAS_MS) olvidarConversacion()
        ultimoUso = inicio

        val herramientas = herramientasActivas()
        val servidores = ajustes.servidoresMcp.filter { it.url.startsWith("https://") }
        // Se calcula UNA vez por pregunta: dentro de un mismo turno nada del encabezado puede cambiar
        val sistema = sistema()
        val definiciones = definiciones(herramientas, servidores.map { nombreMcp(it.nombre) })
        val betas = if (servidores.isEmpty()) emptyList() else listOf(BETA_MCP)

        val mensajes = JSONArray()
        intercambios.forEach { (tu, claude) ->
            mensajes.put(mensaje("user", tu))
            mensajes.put(mensaje("assistant", claude))
        }
        mensajes.put(JSONObject().put("role", "user").put("content", contenidoUsuario(pregunta, foto, porGafas, sinFrase)))
        foto?.let { recordarFoto(it) }

        val construir = {
            val cuerpo = JSONObject()
                .put("model", ajustes.modelo)
                .put("stream", true)
                .put("system", sistema)
                .put("messages", mensajes)
            if (definiciones.length() > 0) cuerpo.put("tools", definiciones)
            if (servidores.isNotEmpty()) {
                val lista = JSONArray()
                servidores.forEach { s ->
                    val o = JSONObject().put("type", "url").put("url", s.url).put("name", nombreMcp(s.nombre))
                    if (s.token.isNotBlank()) o.put("authorization_token", s.token)
                    lista.put(o)
                }
                cuerpo.put("mcp_servers", lista)
            }
            val perfil = PerfilModelo.de(ajustes.modelo)
            if (sinAfinacion) cuerpo.put("max_tokens", perfil.maxTokens) else perfil.aplicar(cuerpo, ajustes.esfuerzo)
            cuerpo
        }

        val acciones = LinkedHashSet<String>()
        val dicho = StringBuilder()
        var archivoFoto = foto?.archivo
        var primerTexto = 0L
        var vuelta = 0

        while (vuelta++ < MAX_VUELTAS) {
            val turno = transmitir(construir, betas) { novedad ->
                when (novedad) {
                    is Ensamblador.Novedad.Texto -> {
                        if (primerTexto == 0L) primerTexto = System.currentTimeMillis()
                        oyentes.alTexto(novedad.delta)
                    }
                    is Ensamblador.Novedad.Herramienta -> if (novedad.enServidor) {
                        val (enCurso, hecho) = etiquetasDeServidor(novedad.nombre)
                        acciones.add(hecho)
                        oyentes.alUsar(enCurso)
                    }
                }
            }
            val contenido = turno.contenido()
            turno.texto().trim().takeIf { it.isNotEmpty() }?.let {
                if (dicho.isNotEmpty()) dicho.append(' ')
                dicho.append(it)
            }
            Diario.info(
                "Claude · ${turno.razonDeParada.ifEmpty { "fin" }} · ${turno.tokensEntrada}+${turno.tokensSalida} tokens" +
                    if (turno.tokensDeCache > 0) " (${turno.tokensDeCache} de caché)" else "",
            )
            if (contenido.length() == 0) break
            // Devolvemos el turno de Claude TAL CUAL: bloques de pensamiento y de búsqueda incluidos
            mensajes.put(JSONObject().put("role", "assistant").put("content", contenido))

            when (turno.razonDeParada) {
                "tool_use" -> {
                    oyentes.alCorte()
                    val resultados = JSONArray()
                    for (i in 0 until contenido.length()) {
                        val bloque = contenido.optJSONObject(i) ?: continue
                        if (bloque.optString("type") != "tool_use") continue
                        val nombre = bloque.optString("name")
                        val herramienta = herramientas.firstOrNull { it.nombre == nombre }
                        herramienta?.let { oyentes.alUsar(it.enCurso) }
                        val resultado = ejecutar(herramienta, nombre, bloque.optJSONObject("input") ?: JSONObject())
                        if (herramienta != null && !resultado.esError) acciones.add(herramienta.hecho)
                        resultado.archivoFoto?.let { archivoFoto = it }
                        resultado.imagenJpegBase64?.let { recordarFoto(Foto(it, resultado.archivoFoto)) }
                        resultados.put(
                            JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", bloque.optString("id"))
                                .put("content", resultado.comoContenido())
                                .put("is_error", resultado.esError),
                        )
                    }
                    if (resultados.length() == 0) break
                    mensajes.put(JSONObject().put("role", "user").put("content", resultados))
                }
                // Una búsqueda larga se pausó del lado del servidor: se reenvía tal cual y Claude sigue
                "pause_turn" -> oyentes.alCorte()
                else -> break
            }
        }

        var texto = dicho.toString().trim()
        if (texto.isEmpty()) texto = "No logré armar una respuesta. Intenta otra vez."
        if (texto != Instrucciones.SILENCIO) guardarIntercambio(pregunta, texto)

        val total = System.currentTimeMillis() - inicio
        val espera = if (primerTexto > 0) primerTexto - inicio else total
        Diario.info("Respuesta en ${total} ms (primera palabra a los ${espera} ms, ${ajustes.modelo})")
        return Respuesta(texto, acciones.toList(), archivoFoto)
    }

    private suspend fun ejecutar(herramienta: Herramienta?, nombre: String, entrada: JSONObject): Resultado {
        if (herramienta == null) return Resultado.error("La herramienta $nombre no está disponible ahora.")
        val inicio = System.currentTimeMillis()
        return try {
            herramienta.ejecutar(entrada)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diario.error("Falló $nombre", e)
            Resultado.error("Falló $nombre: ${e.message ?: e.javaClass.simpleName}")
        }.also { Diario.info("Herramienta $nombre · ${System.currentTimeMillis() - inicio} ms" + if (it.esError) " · error" else "") }
    }

    /** Una llamada a la API con reintentos para fallos pasajeros (solo si aún no se ha dicho nada). */
    private suspend fun transmitir(
        construir: () -> JSONObject,
        betas: List<String>,
        alNovedad: (Ensamblador.Novedad) -> Unit,
    ): Ensamblador {
        var intento = 0
        while (true) {
            val ensamblador = Ensamblador()
            var emitio = false
            try {
                ClaudeApi.transmitir(ajustes.apiKey, construir(), betas) { evento ->
                    ensamblador.procesar(evento)?.let {
                        emitio = true
                        alNovedad(it)
                    }
                }
                return ensamblador
            } catch (e: CancellationException) {
                throw e
            } catch (e: ErrorApi) {
                val mensaje = e.message.orEmpty()
                val esDeAfinacion = e.codigo == 400 && !sinAfinacion &&
                    ("thinking" in mensaje || "effort" in mensaje || "output_config" in mensaje)
                when {
                    esDeAfinacion -> {
                        Diario.info("El modelo rechazó los ajustes de pensamiento; sigo sin ellos")
                        sinAfinacion = true
                    }
                    e.esPasajero && !emitio && intento < 2 -> {
                        intento++
                        Diario.info("Claude ocupado (${e.codigo}); reintento $intento")
                        delay(700L * intento * intento)
                    }
                    else -> throw e
                }
            } catch (e: IOException) {
                if (emitio || intento >= 1) throw e
                intento++
                Diario.info("Fallo de red; reintento")
                delay(500)
            }
        }
    }

    private fun sistema(): JSONArray {
        val bloques = JSONArray().put(
            JSONObject()
                .put("type", "text")
                .put("text", Instrucciones.fijas)
                // Punto de caché: herramientas + instrucciones fijas se reutilizan entre llamadas
                .put("cache_control", JSONObject().put("type", "ephemeral")),
        )
        val personales = Instrucciones.personales(
            nombre = ajustes.nombre,
            extra = ajustes.instruccionesExtra,
            memoria = Memoria.apuntes.value.map { "${it.id}: ${it.texto}" },
        )
        if (personales.isNotBlank()) bloques.put(JSONObject().put("type", "text").put("text", personales))
        return bloques
    }

    private fun definiciones(herramientas: List<Herramienta>, servidoresMcp: List<String>): JSONArray {
        val lista = JSONArray()
        herramientas.forEach { h ->
            lista.put(
                JSONObject()
                    .put("name", h.nombre)
                    .put("description", h.descripcion)
                    .put("input_schema", h.parametros),
            )
        }
        if (ajustes.busquedaWeb) {
            val ubicacion = JSONObject().put("type", "approximate").put("timezone", TimeZone.getDefault().id)
            Locale.getDefault().country.takeIf { it.length == 2 }?.let { ubicacion.put("country", it) }
            lista.put(
                JSONObject()
                    .put("type", "web_search_20250305")
                    .put("name", "web_search")
                    .put("max_uses", 4)
                    .put("user_location", ubicacion),
            )
        }
        if (ajustes.leerWeb) {
            lista.put(JSONObject().put("type", "web_fetch_20250910").put("name", "web_fetch").put("max_uses", 3))
        }
        servidoresMcp.forEach { lista.put(JSONObject().put("type", "mcp_toolset").put("mcp_server_name", it)) }
        return lista
    }

    private fun contenidoUsuario(pregunta: String, foto: Foto?, porGafas: Boolean, sinFrase: Boolean): JSONArray {
        val contenido = JSONArray()
        val anterior = fotoReciente
        when {
            foto != null -> {
                contenido.put(Resultado.bloqueImagen(foto.jpegBase64))
                contenido.put(texto("(Foto tomada ahora mismo por las gafas: es lo que la persona tiene al frente.)"))
            }
            anterior != null && fotoUsosRestantes > 0 && System.currentTimeMillis() - fotoHora < FOTO_VIGENTE_MS -> {
                fotoUsosRestantes--
                val segundos = (System.currentTimeMillis() - fotoHora) / 1000
                contenido.put(Resultado.bloqueImagen(anterior.jpegBase64))
                contenido.put(
                    texto("(Última foto de las gafas, de hace $segundos segundos. Úsala solo si la pregunta sigue hablando de eso; si pide mirar algo nuevo, toma otra.)"),
                )
            }
        }
        contenido.put(texto(pregunta))
        contenido.put(texto(Instrucciones.contexto(ZonedDateTime.now(), porGafas, sinFrase)))
        return contenido
    }

    private fun recordarFoto(foto: Foto) {
        fotoReciente = foto
        fotoHora = System.currentTimeMillis()
        fotoUsosRestantes = 2
    }

    private fun guardarIntercambio(pregunta: String, respuesta: String) {
        intercambios.addLast(pregunta to respuesta)
        while (intercambios.size > MAX_INTERCAMBIOS) intercambios.removeFirst()
    }

    private fun etiquetasDeServidor(nombre: String): Pair<String, String> = when (nombre) {
        "web_search" -> "Buscando en la web…" to "Buscó en la web"
        "web_fetch" -> "Leyendo una página…" to "Leyó una página"
        else -> "Usando ${nombre.replace('_', ' ')}…" to "Usó ${nombre.replace('_', ' ')}"
    }

    private fun mensaje(rol: String, texto: String) = JSONObject().put("role", rol).put("content", texto)

    private fun texto(contenido: String) = JSONObject().put("type", "text").put("text", contenido)

    companion object {
        private const val BETA_MCP = "mcp-client-2025-11-20"
        private const val MAX_VUELTAS = 8
        private const val MAX_INTERCAMBIOS = 8
        private const val OLVIDAR_TRAS_MS = 15 * 60 * 1000L // 15 min sin hablar = conversación nueva
        private const val FOTO_VIGENTE_MS = 3 * 60 * 1000L

        /** La API pide nombres simples y únicos para cada servidor MCP. */
        fun nombreMcp(nombre: String): String =
            nombre.trim().lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').ifEmpty { "conector" }
    }
}
