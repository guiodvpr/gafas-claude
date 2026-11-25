package dev.david.gafas.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.david.gafas.nucleo.Diario
import dev.david.gafas.nucleo.Estado
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * EL OÍDO FÍSICO. Abre un AudioRecord, que entrega el sonido como números (PCM de 16 bits
 * a 16 kHz, justo lo que espera el reconocedor), y lo reparte en pedazos de 100 ms.
 *
 * Corre en su propio hilo porque leer del micrófono bloquea. De paso mide el volumen
 * para que el orbe de la pantalla se mueva con tu voz.
 */
class Microfono {
    private var grabadora: AudioRecord? = null
    private var hilo: Thread? = null

    @Volatile private var activo = false

    /** ¿De verdad está entrando el audio por las gafas? Se sabe solo después de empezar a grabar. */
    @Volatile var porGafas = false
        private set

    val abierto: Boolean get() = activo

    /**
     * @param preferido micrófono de las gafas, o null para usar el del celular
     * @param alAudio recibe cada pedazo (bytes, cuántos son válidos). Se llama en el hilo del micrófono.
     * @param alCaer se llama si el sistema nos quita el micrófono (una llamada, se apagaron las gafas…)
     */
    @SuppressLint("MissingPermission") // la pantalla exige RECORD_AUDIO antes de dejar iniciar
    fun abrir(preferido: AudioDeviceInfo?, alAudio: (ByteArray, Int) -> Unit, alCaer: () -> Unit): Boolean {
        cerrar()
        val minimo = AudioRecord.getMinBufferSize(TASA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimo <= 0) return false
        val nueva = try {
            // VOICE_RECOGNITION es la fuente que el ejemplo oficial de Meta usa para las gafas:
            // con VOICE_COMMUNICATION reportan que a veces solo se captura silencio.
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                TASA,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimo * 4, BYTES_POR_PEDAZO * 4),
            )
        } catch (e: Exception) {
            Diario.error("No pude crear el AudioRecord", e)
            return false
        }
        if (nueva.state != AudioRecord.STATE_INITIALIZED) {
            nueva.release()
            return false
        }
        preferido?.let { nueva.setPreferredDevice(it) }

        grabadora = nueva
        activo = true
        porGafas = false
        hilo = thread(name = "microfono") {
            val pedazo = ByteArray(BYTES_POR_PEDAZO)
            var nivel = 0f
            var pedazos = 0
            var cayo = false
            try {
                nueva.startRecording()
                while (activo) {
                    val leidos = nueva.read(pedazo, 0, pedazo.size)
                    if (leidos < 0) {
                        cayo = true
                        break
                    }
                    if (leidos == 0) continue
                    if (++pedazos == 3) {
                        // La ruta real solo se conoce con la grabación andando
                        porGafas = nueva.routedDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    }
                    nivel = suavizar(nivel, volumen(pedazo, leidos))
                    Estado.nivel.value = nivel
                    alAudio(pedazo, leidos)
                }
            } catch (e: Exception) {
                Diario.error("Se cayó la lectura del micrófono", e)
                cayo = true
            } finally {
                runCatching { nueva.stop() }
                Estado.nivel.value = 0f
            }
            if (cayo && activo) {
                activo = false
                alCaer()
            }
        }
        return true
    }

    fun cerrar() {
        activo = false
        val anterior = hilo
        hilo = null
        if (anterior != null && anterior !== Thread.currentThread()) anterior.join(1_500)
        grabadora?.release()
        grabadora = null
        porGafas = false
    }

    /** Volumen de 0 a 1 a partir de la raíz cuadrática media (RMS) de las muestras. */
    private fun volumen(pcm: ByteArray, largo: Int): Float {
        var suma = 0.0
        var i = 0
        while (i + 1 < largo) {
            val muestra = (pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)
            suma += muestra.toDouble() * muestra
            i += 2
        }
        val rms = sqrt(suma / maxOf(1, largo / 2))
        return (rms / 4_000.0).toFloat().coerceIn(0f, 1f)
    }

    /** Sube rápido y baja despacio, como la aguja de un vúmetro. */
    private fun suavizar(anterior: Float, nuevo: Float): Float =
        if (nuevo > anterior) anterior + (nuevo - anterior) * 0.6f else anterior + (nuevo - anterior) * 0.2f

    companion object {
        const val TASA = 16_000
        const val BYTES_POR_SEGUNDO = TASA * 2
        const val BYTES_POR_PEDAZO = BYTES_POR_SEGUNDO / 10 // 100 ms
    }
}
