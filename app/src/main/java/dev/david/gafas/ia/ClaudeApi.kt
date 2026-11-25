package dev.david.gafas.ia

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Un error que la API explicó (a diferencia de un fallo de red, que es un IOException normal). */
class ErrorApi(val codigo: Int, val tipo: String, mensaje: String) : IOException(mensaje) {
    /** ¿Vale la pena reintentar? Saturación y límites temporales sí; una petición mal hecha no. */
    val esPasajero: Boolean
        get() = codigo == 429 || codigo == 529 || codigo >= 500 ||
            tipo == "overloaded_error" || tipo == "rate_limit_error" || tipo == "api_error"
}

/**
 * LA LÍNEA DIRECTA CON CLAUDE. Una sola función: manda una petición a la Messages API en
 * modo streaming y entrega cada evento apenas llega.
 *
 * Usa HttpURLConnection, que ya viene con Android: cero dependencias. El streaming usa
 * Server-Sent Events, un formato de texto donde cada evento es una línea "data: {json}".
 */
object ClaudeApi {
    private const val URL_MENSAJES = "https://api.anthropic.com/v1/messages"
    private const val VERSION = "2023-06-01"

    suspend fun transmitir(
        apiKey: String,
        cuerpo: JSONObject,
        betas: List<String>,
        alEvento: (JSONObject) -> Unit,
    ) = coroutineScope {
        val conexion = (URL(URL_MENSAJES).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            // Durante búsquedas largas la API manda "pings", así que 60 s sin nada es que se cayó
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("accept", "text/event-stream")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", VERSION)
            if (betas.isNotEmpty()) setRequestProperty("anthropic-beta", betas.joinToString(","))
        }

        // Leer de la red bloquea el hilo y no se entera de las cancelaciones. Este vigía sí:
        // si interrumpes a Claude, cierra la conexión y la lectura se destraba al instante.
        val vigia = launch(Dispatchers.Default) {
            try {
                awaitCancellation()
            } finally {
                runCatching { conexion.disconnect() }
            }
        }

        try {
            withContext(Dispatchers.IO) {
                conexion.outputStream.use { it.write(cuerpo.toString().toByteArray(Charsets.UTF_8)) }

                val codigo = conexion.responseCode
                if (codigo != HttpURLConnection.HTTP_OK) {
                    val texto = conexion.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    throw errorDesde(codigo, texto)
                }

                BufferedReader(InputStreamReader(conexion.inputStream, Charsets.UTF_8)).use { lector ->
                    while (true) {
                        ensureActive()
                        val linea = lector.readLine() ?: break
                        if (!linea.startsWith("data:")) continue
                        val datos = linea.substring(5).trim()
                        if (datos.isEmpty() || datos == "[DONE]") continue
                        val evento = runCatching { JSONObject(datos) }.getOrNull() ?: continue
                        alEvento(evento)
                        if (evento.optString("type") == "message_stop") break
                    }
                }
            }
        } catch (e: IOException) {
            // Si el corte lo provocó una cancelación, que se vea como cancelación y no como error de red
            ensureActive()
            throw e
        } finally {
            vigia.cancel()
            runCatching { conexion.disconnect() }
        }
    }

    private fun errorDesde(codigo: Int, cuerpo: String): ErrorApi {
        val error = runCatching { JSONObject(cuerpo).optJSONObject("error") }.getOrNull()
        return ErrorApi(
            codigo = codigo,
            tipo = error?.let { Ensamblador.cadena(it, "type") }.orEmpty(),
            mensaje = error?.let { Ensamblador.cadena(it, "message") }.orEmpty().ifEmpty { cuerpo.take(300) },
        )
    }

    /** Traduce un fallo a algo que se pueda decir en voz alta. */
    fun explicar(error: Throwable): String = when {
        error is ErrorApi && error.codigo == 401 -> "La llave de Claude no es válida. Revísala en Ajustes."
        error is ErrorApi && error.codigo == 403 -> "Tu llave no tiene permiso para esto: ${error.message}"
        error is ErrorApi && error.codigo == 404 -> "Ese modelo no existe o tu cuenta no tiene acceso. Cámbialo en Ajustes."
        error is ErrorApi && error.codigo == 429 -> "Llegaste al límite de uso de la API. Intenta en un momento."
        error is ErrorApi && (error.codigo == 529 || error.tipo == "overloaded_error") ->
            "Claude está muy ocupado ahora mismo. Intenta otra vez en unos segundos."
        error is ErrorApi && error.codigo == 400 && "web_search" in error.message.orEmpty() ->
            "La búsqueda web no está habilitada para tu llave. Actívala en la consola de Anthropic o apágala en Capacidades."
        error is ErrorApi && error.codigo == 400 && "credit" in error.message.orEmpty() ->
            "Tu cuenta de la API se quedó sin saldo."
        error is ErrorApi -> "Claude devolvió un error: ${error.message}"
        error is java.net.UnknownHostException || error is java.net.ConnectException ->
            "No tengo conexión a internet."
        error is java.net.SocketTimeoutException -> "La conexión está muy lenta y se agotó el tiempo."
        else -> "Tuve un problema de conexión: ${error.message ?: error.javaClass.simpleName}"
    }
}
