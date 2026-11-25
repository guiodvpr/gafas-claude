package dev.david.gafas.audio

import dev.david.gafas.nucleo.Ajustes
import dev.david.gafas.nucleo.Diario
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * TRANSCRIPCIÓN PREMIUM (opcional). El oído offline es rápido y privado, pero se enreda con
 * nombres propios, inglés mezclado o ruido. Si activas esta opción, cuando terminas de
 * hablar el audio se manda a un servicio Whisper en la nube y su texto reemplaza al local.
 *
 * Habla el formato de la API de transcripción de OpenAI, que es un estándar de facto:
 * sirve con Groq (por defecto, muy rápido), con OpenAI o con cualquier servicio compatible;
 * solo cambian la dirección, el modelo y la llave en Ajustes.
 *
 * Si la nube falla o tarda, no pasa nada: se usa lo que entendió el oído offline.
 */
class Transcriptor(private val ajustes: Ajustes) {

    val activo: Boolean
        get() = ajustes.transcripcion == dev.david.gafas.nucleo.MotorTranscripcion.NUBE && ajustes.sttKey.isNotBlank()

    /** Devuelve el texto, o null si no se pudo (y entonces se usa la transcripción local). */
    suspend fun transcribir(pcm: ByteArray): String? {
        val inicio = System.currentTimeMillis()
        return try {
            withTimeoutOrNull(LIMITE_MS) { withContext(Dispatchers.IO) { enviar(Wav.desdePcm(pcm, Microfono.TASA)) } }
                ?.also { Diario.info("Transcripción en la nube · ${System.currentTimeMillis() - inicio} ms") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diario.error("Falló la transcripción en la nube; uso la local", e)
            null
        }
    }

    private fun enviar(wav: ByteArray): String? {
        val frontera = "----gafas" + System.nanoTime()
        val cuerpo = ByteArrayOutputStream()
        fun campo(nombre: String, valor: String) {
            cuerpo.write("--$frontera\r\nContent-Disposition: form-data; name=\"$nombre\"\r\n\r\n$valor\r\n".toByteArray())
        }
        campo("model", ajustes.sttModelo)
        campo("language", "es")
        campo("response_format", "json")
        campo("temperature", "0")
        cuerpo.write(
            "--$frontera\r\nContent-Disposition: form-data; name=\"file\"; filename=\"voz.wav\"\r\nContent-Type: audio/wav\r\n\r\n"
                .toByteArray(),
        )
        cuerpo.write(wav)
        cuerpo.write("\r\n--$frontera--\r\n".toByteArray())

        val conexion = (URL(ajustes.sttUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4_000
            readTimeout = 7_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${ajustes.sttKey}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$frontera")
            setFixedLengthStreamingMode(cuerpo.size())
        }
        try {
            conexion.outputStream.use { cuerpo.writeTo(it) }
            if (conexion.responseCode != HttpURLConnection.HTTP_OK) {
                val detalle = conexion.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(200)
                throw java.io.IOException("HTTP ${conexion.responseCode} $detalle")
            }
            val respuesta = conexion.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(respuesta).optString("text").trim().ifEmpty { null }
        } finally {
            conexion.disconnect()
        }
    }

    companion object {
        private const val LIMITE_MS = 8_000L
    }
}

/** Le pone la cabecera WAV a un audio PCM crudo para que cualquier servicio lo reconozca. */
object Wav {
    fun desdePcm(pcm: ByteArray, tasa: Int, canales: Int = 1, bitsPorMuestra: Int = 16): ByteArray {
        val bytesPorSegundo = tasa * canales * bitsPorMuestra / 8
        val cabecera = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcm.size)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // tamaño del bloque fmt
            putShort(1) // 1 = PCM sin comprimir
            putShort(canales.toShort())
            putInt(tasa)
            putInt(bytesPorSegundo)
            putShort((canales * bitsPorMuestra / 8).toShort())
            putShort(bitsPorMuestra.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcm.size)
        }
        return cabecera.array() + pcm
    }
}
