package dev.david.gafas.ia

import org.json.JSONObject

/**
 * Cada modelo de Claude acepta ajustes distintos de "pensamiento", y mandar el que no es
 * devuelve un error 400. Esta tabla sale de la documentación de la API (nov 2026):
 *
 *  - Haiku 5.5:  se le puede apagar el pensamiento → la respuesta más rápida posible.
 *  - Sonnet 5.5: no se apaga del todo; "between_tools" quita el pensamiento inicial.
 *  - Opus 5.5 y Fable 5.2: siempre piensan; solo se regula con el esfuerzo.
 *  - Modelo desconocido: no mandamos nada y que la API use sus valores por defecto.
 *
 * En voz, lo que más se siente es cuánto tarda en salir la primera palabra. Por eso
 * pedimos el mínimo pensamiento que cada modelo permite y esfuerzo bajo por defecto.
 */
data class PerfilModelo(
    val pensamiento: JSONObject?,
    val aceptaEsfuerzo: Boolean,
    val maxTokens: Int,
) {
    /** Agrega al cuerpo de la petición lo que este modelo acepta. */
    fun aplicar(cuerpo: JSONObject, esfuerzo: String) {
        cuerpo.put("max_tokens", maxTokens)
        pensamiento?.let { cuerpo.put("thinking", it) }
        if (aceptaEsfuerzo) {
            val permitido = if (esfuerzo in ESFUERZOS) esfuerzo else "low"
            cuerpo.put("output_config", JSONObject().put("effort", permitido))
        }
    }

    companion object {
        val ESFUERZOS = listOf("low", "medium", "high")

        fun de(modelo: String): PerfilModelo {
            val m = modelo.trim().lowercase()
            return when {
                m.startsWith("claude-haiku-5-5") ->
                    PerfilModelo(JSONObject().put("type", "disabled"), aceptaEsfuerzo = true, maxTokens = 2048)
                m.startsWith("claude-sonnet-5-5") ->
                    PerfilModelo(JSONObject().put("type", "between_tools"), aceptaEsfuerzo = true, maxTokens = 4096)
                m.startsWith("claude-opus-5-5") || m.startsWith("claude-fable-5-2") || m.startsWith("claude-mythos-5-2") ->
                    // El pensamiento cuenta dentro de max_tokens: hay que dejarle espacio
                    PerfilModelo(pensamiento = null, aceptaEsfuerzo = true, maxTokens = 8192)
                else -> PerfilModelo(pensamiento = null, aceptaEsfuerzo = false, maxTokens = 4096)
            }
        }
    }
}
