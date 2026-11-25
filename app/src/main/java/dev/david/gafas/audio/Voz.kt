package dev.david.gafas.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dev.david.gafas.nucleo.Diario
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * LA BOCA. Convierte el texto de Claude en voz con el motor del propio celular.
 *
 * Funciona como una cola: cada frase se encola apenas está lista ([decir]) y el motor las
 * va diciendo una tras otra sin pausas. Así la voz arranca con la primera frase mientras
 * Claude sigue escribiendo el resto.
 *
 * Si el audio está enrutado a las gafas, la voz se marca como "comunicación" para que
 * salga por el mismo canal Bluetooth del micrófono, directo a sus parlantes.
 */
class Voz(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val lista = CompletableDeferred<Boolean>()
    private val tts = TextToSpeech(context.applicationContext) { estado -> lista.complete(estado == TextToSpeech.SUCCESS) }

    private val enCola = Collections.synchronizedSet(HashSet<String>())
    private val pendientes = MutableStateFlow(0)
    private val contador = AtomicLong()

    private var atributos: AudioAttributes = atributosPara(porGafas = false)
    private var foco: AudioFocusRequest? = null
    private var pito: ToneGenerator? = null
    private var porGafas = false

    val hablando: Boolean get() = pendientes.value > 0

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = terminar(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = terminar(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = terminar(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = terminar(utteranceId)
        })
    }

    /** Espera a que el motor arranque y elige idioma, voz y velocidad. False si no hay motor de voz. */
    suspend fun preparar(vozPreferida: String, velocidad: Float): Boolean {
        if (!lista.await()) return false
        val idioma = IDIOMAS.map { Locale.forLanguageTag(it) }
            .firstOrNull { tts.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
        idioma?.let { tts.setLanguage(it) }
        elegirVoz(vozPreferida)
        tts.setSpeechRate(velocidad.coerceIn(0.6f, 2.0f))
        return true
    }

    /** Las voces en español instaladas, de mejor a peor calidad. Para el selector de Ajustes. */
    fun voces(): List<String> = vocesEnEspanol().map { it.name }

    private fun elegirVoz(preferida: String) {
        val disponibles = vocesEnEspanol()
        val elegida = disponibles.firstOrNull { it.name == preferida } ?: disponibles.firstOrNull() ?: return
        runCatching { tts.setVoice(elegida) }
    }

    private fun vocesEnEspanol(): List<Voice> = runCatching {
        tts.voices.orEmpty()
            .filter { it.locale.language == "es" && !it.isNetworkConnectionRequired }
            .sortedWith(
                compareByDescending<Voice> { it.quality }
                    .thenBy { PAISES.indexOf(it.locale.country).let { i -> if (i < 0) PAISES.size else i } }
                    .thenBy { it.name },
            )
    }.getOrDefault(emptyList())

    /** Cambia la salida entre las gafas y el parlante del celular. */
    fun salida(porGafas: Boolean) {
        this.porGafas = porGafas
        atributos = atributosPara(porGafas)
        tts.setAudioAttributes(atributos)
        pito?.release()
        pito = runCatching {
            ToneGenerator(if (porGafas) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC, 70)
        }.getOrNull()
    }

    /** Encola una frase y vuelve de inmediato. Se puede llamar desde cualquier hilo. */
    fun decir(texto: String) {
        val limpio = Frases.limpiarParaVoz(texto)
        if (limpio.isEmpty()) return
        val id = "f" + contador.incrementAndGet()
        synchronized(enCola) {
            if (enCola.isEmpty()) pedirFoco()
            enCola.add(id)
            pendientes.value = enCola.size
        }
        if (tts.speak(limpio, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) terminar(id)
    }

    /** Se suspende hasta que no quede nada por decir. */
    suspend fun esperarSilencio() {
        pendientes.first { it == 0 }
    }

    /** Se calla ya (cuando la interrumpes). */
    fun callar() {
        synchronized(enCola) {
            enCola.clear()
            pendientes.value = 0
            soltarFoco()
        }
        tts.stop()
    }

    /** Sonidos cortos que te dicen qué está pasando sin mirar el celular. */
    fun sonar(sonido: Sonido) {
        val tono = when (sonido) {
            Sonido.TE_ESCUCHO -> ToneGenerator.TONE_PROP_BEEP
            Sonido.ENTENDIDO -> ToneGenerator.TONE_PROP_ACK
            Sonido.NADA -> ToneGenerator.TONE_PROP_NACK
        }
        runCatching { pito?.startTone(tono, 140) }
    }

    enum class Sonido { TE_ESCUCHO, ENTENDIDO, NADA }

    fun cerrar() {
        callar()
        tts.shutdown()
        pito?.release()
        pito = null
    }

    private fun terminar(id: String?) {
        if (id == null) return
        synchronized(enCola) {
            if (!enCola.remove(id)) return
            pendientes.value = enCola.size
            if (enCola.isEmpty()) soltarFoco()
        }
    }

    /** Mientras Claude habla, la música baja de volumen en vez de taparlo. */
    private fun pedirFoco() {
        if (foco != null) return
        val pedido = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(atributos)
            .build()
        runCatching { audio.requestAudioFocus(pedido) }.onFailure { Diario.error("No obtuve el foco de audio", it) }
        foco = pedido
    }

    private fun soltarFoco() {
        foco?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        foco = null
    }

    private fun atributosPara(porGafas: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (porGafas) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    companion object {
        private val IDIOMAS = listOf("es-CO", "es-US", "es-MX", "es-ES", "es")
        private val PAISES = listOf("CO", "US", "MX", "419", "ES")
    }
}
