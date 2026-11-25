package dev.david.gafas.nucleo

import java.text.Normalizer

/** Utilidades de texto puras (sin Android): se pueden probar en cualquier máquina. */
object Texto {
    private val MARCAS = Regex("\\p{M}+")
    private val NO_LETRAS = Regex("[^a-z0-9ñ ]+")
    private val ESPACIOS = Regex("\\s+")

    /** "¿Oye, Cláudio?" → "oye claudio". Para comparar sin tildes, signos ni mayúsculas. */
    fun normalizar(texto: String): String {
        val sinTildes = Normalizer.normalize(texto.lowercase().replace("ñ", "\u0001"), Normalizer.Form.NFD)
            .replace(MARCAS, "")
            .replace("\u0001", "ñ")
        return sinTildes.replace(NO_LETRAS, " ").replace(ESPACIOS, " ").trim()
    }

    /** Cuántas letras hay que cambiar para ir de una palabra a otra (distancia de Levenshtein). */
    fun distancia(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var anterior = IntArray(b.length + 1) { it }
        var actual = IntArray(b.length + 1)
        for (i in 1..a.length) {
            actual[0] = i
            for (j in 1..b.length) {
                val costo = if (a[i - 1] == b[j - 1]) 0 else 1
                actual[j] = minOf(actual[j - 1] + 1, anterior[j] + 1, anterior[j - 1] + costo)
            }
            val intercambio = anterior
            anterior = actual
            actual = intercambio
        }
        return anterior[b.length]
    }

    /** ¿Suenan casi igual? Tolera errores del reconocedor en palabras largas ("claudio" ≈ "claudia"). */
    fun parecidas(a: String, b: String, tolerancia: Int = 2): Boolean {
        if (a == b) return true
        val corta = minOf(a.length, b.length)
        val permitida = if (corta >= 5) tolerancia else if (corta >= 4) minOf(1, tolerancia) else 0
        return permitida > 0 && distancia(a, b) <= permitida
    }

    /**
     * Quita la frase de activación del comienzo de lo que se transcribió.
     *
     * "oye claudio qué hora es" → "qué hora es"
     * "claudio pon música"       → "pon música"   (a veces solo se alcanza a oír el final de la frase)
     * "Oye, Claude: ¿me oyes?"   → "¿me oyes?"    (un transcriptor en la nube puede escribirlo distinto)
     */
    fun quitarFrase(texto: String, frases: List<String>): String {
        val palabras = texto.trim().split(ESPACIOS).filter { it.isNotEmpty() }
        if (palabras.isEmpty()) return ""
        val limpias = palabras.map { normalizar(it) }
        var quitar = 0
        for (frase in frases) {
            val clave = normalizar(frase).split(' ').filter { it.isNotEmpty() }
            // Probamos la frase completa y también sus finales ("oye claudio" → "claudio")
            for (desde in clave.indices) {
                val cola = clave.subList(desde, clave.size)
                if (cola.size > limpias.size || cola.size <= quitar) continue
                val esConfiable = if (cola.size > 1) {
                    cola.indices.all { i -> parecidas(limpias[i], cola[i]) }
                } else {
                    // Una palabra suelta es mala evidencia: "oye" a secas no es la frase, y
                    // "audio" se parece a "claudio". Solo vale si es larga y casi idéntica, o si
                    // viene con coma o dos puntos ("Claude, ..."), que es como se escribe un llamado.
                    val conSigno = palabras[0].last() in ",:;.!"
                    cola[0].length >= 5 && parecidas(limpias[0], cola[0], tolerancia = if (conSigno) 2 else 1)
                }
                if (esConfiable) quitar = cola.size
            }
        }
        return palabras.drop(quitar).joinToString(" ").trimStart(',', '.', ':', ';', '-', ' ')
    }

    /** ¿El texto contiene alguna de las frases de activación? (ya normalizado) */
    fun contieneFrase(textoNormalizado: String, frasesNormalizadas: List<String>): Boolean =
        frasesNormalizadas.any { frase ->
            textoNormalizado == frase ||
                textoNormalizado.endsWith(" $frase") ||
                textoNormalizado.startsWith("$frase ") ||
                " $frase " in textoNormalizado
        }
}
