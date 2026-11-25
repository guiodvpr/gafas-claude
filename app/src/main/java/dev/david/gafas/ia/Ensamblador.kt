package dev.david.gafas.ia

import java.util.TreeMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reconstruye la respuesta de Claude a partir del goteo de eventos del streaming.
 *
 * La API no manda la respuesta entera: manda eventos pequeños
 *   content_block_start → content_block_delta (muchos) → content_block_stop
 * para cada "bloque" (texto, uso de herramienta, pensamiento, resultado de búsqueda…).
 *
 * Aquí hacemos dos cosas a la vez:
 *  1. Avisamos cada trocito de texto apenas llega ([Novedad.Texto]) para irlo hablando.
 *  2. Guardamos cada bloque COMPLETO y sin tocar, porque si Claude pidió una herramienta
 *     hay que devolverle su turno tal cual (incluidas las firmas de sus bloques de
 *     pensamiento): si falta o cambia algo, la API rechaza la siguiente llamada.
 */
class Ensamblador {

    sealed interface Novedad {
        data class Texto(val delta: String) : Novedad
        data class Herramienta(val nombre: String, val enServidor: Boolean) : Novedad
    }

    private class Bloque(val base: JSONObject) {
        val texto = StringBuilder()
        val entrada = StringBuilder()
        val pensamiento = StringBuilder()
        val firma = StringBuilder()
        val citas = JSONArray()
    }

    private val bloques = TreeMap<Int, Bloque>()

    /** Por qué paró Claude: end_turn, tool_use, pause_turn, max_tokens, refusal… */
    var razonDeParada: String = ""
        private set

    var tokensEntrada = 0
        private set
    var tokensSalida = 0
        private set
    var tokensDeCache = 0
        private set

    /** Procesa un evento ya convertido a JSON. Lanza [ErrorApi] si es un evento de error. */
    fun procesar(evento: JSONObject): Novedad? {
        when (evento.optString("type")) {
            "message_start" -> evento.optJSONObject("message")?.optJSONObject("usage")?.let(::leerUso)

            "content_block_start" -> {
                val base = evento.optJSONObject("content_block") ?: return null
                val bloque = Bloque(JSONObject(base.toString()))
                bloques[evento.optInt("index")] = bloque
                when (base.optString("type")) {
                    "tool_use" -> return Novedad.Herramienta(base.optString("name"), enServidor = false)
                    "server_tool_use", "mcp_tool_use" ->
                        return Novedad.Herramienta(base.optString("name"), enServidor = true)
                    "text" -> {
                        val inicial = cadena(base, "text")
                        if (inicial.isNotEmpty()) {
                            bloque.texto.append(inicial)
                            return Novedad.Texto(inicial)
                        }
                    }
                }
            }

            "content_block_delta" -> {
                val bloque = bloques[evento.optInt("index")] ?: return null
                val delta = evento.optJSONObject("delta") ?: return null
                when (delta.optString("type")) {
                    "text_delta" -> {
                        val trozo = cadena(delta, "text")
                        if (trozo.isNotEmpty()) {
                            bloque.texto.append(trozo)
                            return Novedad.Texto(trozo)
                        }
                    }
                    "input_json_delta" -> bloque.entrada.append(cadena(delta, "partial_json"))
                    "thinking_delta" -> bloque.pensamiento.append(cadena(delta, "thinking"))
                    "signature_delta" -> bloque.firma.append(cadena(delta, "signature"))
                    "citations_delta" -> delta.opt("citation")?.let { bloque.citas.put(it) }
                }
            }

            "message_delta" -> {
                evento.optJSONObject("delta")?.let { razonDeParada = cadena(it, "stop_reason").ifEmpty { razonDeParada } }
                evento.optJSONObject("usage")?.let(::leerUso)
            }

            "error" -> {
                val error = evento.optJSONObject("error")
                throw ErrorApi(
                    codigo = 0,
                    tipo = error?.let { cadena(it, "type") }.orEmpty(),
                    mensaje = error?.let { cadena(it, "message") }.orEmpty().ifEmpty { "Error en el streaming" },
                )
            }
        }
        return null
    }

    /** El turno completo de Claude, listo para devolvérselo en la siguiente llamada. */
    fun contenido(): JSONArray {
        val resultado = JSONArray()
        for (bloque in bloques.values) {
            val o = JSONObject(bloque.base.toString())
            when (o.optString("type")) {
                "text" -> {
                    val texto = bloque.texto.toString()
                    if (texto.isBlank()) continue // la API no acepta bloques de texto vacíos
                    o.put("text", texto)
                    if (bloque.citas.length() > 0) o.put("citations", bloque.citas) else o.remove("citations")
                }
                "tool_use", "server_tool_use", "mcp_tool_use" -> {
                    val json = bloque.entrada.toString()
                    val entrada = if (json.isBlank()) null else runCatching { JSONObject(json) }.getOrNull()
                    o.put("input", entrada ?: o.optJSONObject("input") ?: JSONObject())
                }
                "thinking" -> {
                    if (bloque.pensamiento.isNotEmpty()) o.put("thinking", bloque.pensamiento.toString())
                    if (bloque.firma.isNotEmpty()) o.put("signature", bloque.firma.toString())
                }
                // Todo lo demás (resultados de búsqueda, pensamiento oculto…) llega entero y se devuelve igual
            }
            resultado.put(o)
        }
        return resultado
    }

    /** Solo el texto dirigido a la persona. */
    fun texto(): String = bloques.values
        .filter { it.base.optString("type") == "text" }
        .joinToString("") { it.texto }

    private fun leerUso(uso: JSONObject) {
        if (uso.has("input_tokens")) tokensEntrada = uso.optInt("input_tokens")
        if (uso.has("output_tokens")) tokensSalida = uso.optInt("output_tokens")
        if (uso.has("cache_read_input_tokens")) tokensDeCache = uso.optInt("cache_read_input_tokens")
    }

    companion object {
        /** optString de Android devuelve el texto "null" para un null de JSON; esto no. */
        fun cadena(objeto: JSONObject, clave: String): String =
            if (objeto.isNull(clave)) "" else objeto.optString(clave)
    }
}
