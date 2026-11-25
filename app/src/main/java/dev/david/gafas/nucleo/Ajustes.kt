package dev.david.gafas.nucleo

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class ModoEscucha { SIEMPRE, BOTON }

enum class MotorTranscripcion { LOCAL, NUBE }

/** Un servidor MCP remoto: la puerta para conectar Claude con cualquier otra app. */
data class ServidorMcp(val nombre: String, val url: String, val token: String)

/**
 * Todo lo que configuras en la app. Vive solo en tu celular.
 * Las llaves pasan por [Boveda]; el resto son preferencias normales.
 */
class Ajustes private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    /** Cambia cada vez que se guarda algo: la interfaz lo observa para refrescarse. */
    val version = MutableStateFlow(0)

    // ---- Claude ----
    var apiKey: String
        get() = secreto("api_key")
        set(valor) = guardarSecreto("api_key", valor)

    var modelo: String
        get() = texto("modelo", MODELO_RAPIDO)
        set(valor) = guardar { putString("modelo", valor.trim()) }

    /** low = respuestas más rápidas; medium/high = Claude lo piensa más. */
    var esfuerzo: String
        get() = texto("esfuerzo", "low")
        set(valor) = guardar { putString("esfuerzo", valor) }

    var busquedaWeb: Boolean
        get() = prefs.getBoolean("busqueda_web", true)
        set(valor) = guardar { putBoolean("busqueda_web", valor) }

    var leerWeb: Boolean
        get() = prefs.getBoolean("leer_web", true)
        set(valor) = guardar { putBoolean("leer_web", valor) }

    // ---- Escucha ----
    /** Una o varias frases separadas por coma: "oye claudio, hola claudio". */
    var frases: String
        get() = texto("frase", FRASE_POR_DEFECTO)
        set(valor) = guardar { putString("frase", valor.trim().lowercase()) }

    val listaDeFrases: List<String>
        get() = frases.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("oye claudio") }

    var modoEscucha: ModoEscucha
        get() = runCatching { ModoEscucha.valueOf(texto("modo_escucha", ModoEscucha.SIEMPRE.name)) }
            .getOrDefault(ModoEscucha.SIEMPRE)
        set(valor) = guardar { putString("modo_escucha", valor.name) }

    /** Tras cada respuesta, unos segundos para seguir hablando sin repetir la frase. */
    var seguirConversacion: Boolean
        get() = prefs.getBoolean("seguir", true)
        set(valor) = guardar { putBoolean("seguir", valor) }

    /** Decir la frase mientras Claude habla lo interrumpe. */
    var interrumpible: Boolean
        get() = prefs.getBoolean("interrumpible", true)
        set(valor) = guardar { putBoolean("interrumpible", valor) }

    var sonidos: Boolean
        get() = prefs.getBoolean("sonidos", true)
        set(valor) = guardar { putBoolean("sonidos", valor) }

    // ---- Transcripción ----
    var transcripcion: MotorTranscripcion
        get() = runCatching { MotorTranscripcion.valueOf(texto("transcripcion", MotorTranscripcion.LOCAL.name)) }
            .getOrDefault(MotorTranscripcion.LOCAL)
        set(valor) = guardar { putString("transcripcion", valor.name) }

    var sttUrl: String
        get() = texto("stt_url", STT_URL_POR_DEFECTO)
        set(valor) = guardar { putString("stt_url", valor.trim()) }

    var sttModelo: String
        get() = texto("stt_modelo", STT_MODELO_POR_DEFECTO)
        set(valor) = guardar { putString("stt_modelo", valor.trim()) }

    var sttKey: String
        get() = secreto("stt_key")
        set(valor) = guardarSecreto("stt_key", valor)

    // ---- Voz ----
    var velocidadVoz: Float
        get() = prefs.getFloat("velocidad_voz", 1.05f)
        set(valor) = guardar { putFloat("velocidad_voz", valor) }

    /** Nombre de la voz del sistema; vacío = la mejor en español que haya. */
    var voz: String
        get() = texto("voz", "")
        set(valor) = guardar { putString("voz", valor) }

    // ---- Visión ----
    /** Si la pregunta es claramente visual, toma la foto de una vez sin esperar a que Claude la pida. */
    var vistaRapida: Boolean
        get() = prefs.getBoolean("vista_rapida", true)
        set(valor) = guardar { putBoolean("vista_rapida", valor) }

    // ---- Modo parcial ----
    /** Foto → respuesta directa, sin explicaciones. Se prende y apaga desde Inicio. */
    var modoParcial: Boolean
        get() = prefs.getBoolean("modo_parcial", false)
        set(valor) = guardar { putBoolean("modo_parcial", valor) }

    /** false = Rápido (Haiku); true = Preciso (Sonnet, piensa un poco más en lo difícil). */
    var parcialPreciso: Boolean
        get() = prefs.getBoolean("parcial_preciso", false)
        set(valor) = guardar { putBoolean("parcial_preciso", valor) }

    // ---- Personalización ----
    var nombre: String
        get() = texto("nombre", "")
        set(valor) = guardar { putString("nombre", valor.trim()) }

    var instruccionesExtra: String
        get() = texto("instrucciones", "")
        set(valor) = guardar { putString("instrucciones", valor.trim()) }

    // ---- Capacidades ----
    fun capacidadActiva(id: String, porDefecto: Boolean): Boolean = prefs.getBoolean("cap_$id", porDefecto)

    fun activarCapacidad(id: String, activa: Boolean) = guardar { putBoolean("cap_$id", activa) }

    // ---- Conectores MCP ----
    var servidoresMcp: List<ServidorMcp>
        get() = runCatching {
            val lista = JSONArray(secreto("mcp").ifBlank { "[]" })
            (0 until lista.length()).mapNotNull { i ->
                val o = lista.optJSONObject(i) ?: return@mapNotNull null
                ServidorMcp(o.optString("nombre"), o.optString("url"), o.optString("token"))
            }
        }.getOrDefault(emptyList())
        set(valor) {
            val lista = JSONArray()
            valor.forEach {
                lista.put(JSONObject().put("nombre", it.nombre).put("url", it.url).put("token", it.token))
            }
            guardarSecreto("mcp", lista.toString())
        }

    // ---- Interno ----
    private fun texto(clave: String, porDefecto: String): String =
        prefs.getString(clave, null)?.takeIf { it.isNotBlank() } ?: porDefecto

    private inline fun guardar(cambio: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(cambio).apply()
        version.value++
    }

    private fun secreto(clave: String): String {
        prefs.getString("sec_$clave", null)?.let { return Boveda.descifrar(it).orEmpty() }
        // Compatibilidad: la versión 1 guardaba la llave en claro. Se migra la primera vez.
        val enClaro = prefs.getString(clave, null)?.takeIf { it.isNotBlank() } ?: return ""
        if (!bovedaFallo) {
            val cifrado = Boveda.cifrar(enClaro)
            if (cifrado != null) {
                prefs.edit().putString("sec_$clave", cifrado).remove(clave).apply()
            } else {
                bovedaFallo = true
            }
        }
        return enClaro
    }

    @Volatile private var bovedaFallo = false

    private fun guardarSecreto(clave: String, valor: String) {
        val limpio = valor.trim()
        val cifrado = if (limpio.isEmpty()) null else Boveda.cifrar(limpio)
        guardar {
            when {
                limpio.isEmpty() -> remove("sec_$clave").remove(clave)
                cifrado != null -> putString("sec_$clave", cifrado).remove(clave)
                // Si el Keystore fallara, preferimos que la app siga funcionando
                else -> remove("sec_$clave").putString(clave, limpio)
            }
        }
    }

    companion object {
        const val MODELO_RAPIDO = "claude-haiku-5-5"
        const val MODELO_EQUILIBRADO = "claude-sonnet-5-5"
        const val MODELO_PROFUNDO = "claude-opus-5-5"

        // El reconocedor offline solo oye palabras de su diccionario en español:
        // "claude" es inglés; "claudio" es un nombre común y suena casi igual.
        // La segunda frase es el respaldo por si a tu voz le va mejor.
        const val FRASE_POR_DEFECTO = "oye claudio, oye asistente"

        // Cualquier servicio compatible con la API de transcripción de OpenAI sirve aquí.
        const val STT_URL_POR_DEFECTO = "https://api.groq.com/openai/v1/audio/transcriptions"
        const val STT_MODELO_POR_DEFECTO = "whisper-large-v3-turbo"

        @Volatile private var instancia: Ajustes? = null

        fun de(context: Context): Ajustes =
            instancia ?: synchronized(this) {
                instancia ?: Ajustes(context.applicationContext).also { instancia = it }
            }
    }
}
