package dev.david.gafas.audio

/**
 * EL TRUCO DE LA VELOCIDAD. Claude escribe la respuesta poco a poco; si esperáramos a que
 * termine para empezar a hablar, sentirías segundos de silencio. Esta clase recibe el
 * goteo de texto y va soltando frases completas apenas se cierran, para que la voz
 * arranque con la primera frase mientras Claude todavía escribe la tercera.
 *
 * Es lógica pura (no usa Android), así que tiene pruebas automáticas.
 */
class Frases {
    private val pendiente = StringBuilder()
    private var esPrimera = true

    /** Suma un trozo de texto y devuelve las frases que quedaron completas (puede ser ninguna). */
    fun agregar(delta: String): List<String> {
        pendiente.append(delta)
        val listas = mutableListOf<String>()
        while (true) {
            val corte = buscarCorte() ?: break
            val frase = pendiente.substring(0, corte).trim()
            pendiente.delete(0, corte)
            if (frase.isNotEmpty()) {
                listas.add(frase)
                esPrimera = false
            }
        }
        return listas
    }

    /** Entrega lo que quede aunque no haya terminado en punto. Se llama al final o antes de una herramienta. */
    fun cortar(): String? {
        val resto = pendiente.toString().trim()
        pendiente.setLength(0)
        return resto.ifEmpty { null }
    }

    /** Posición (exclusiva) donde termina la próxima frase que ya se puede decir, o null. */
    private fun buscarCorte(): Int? {
        // La primera frase sale lo antes posible; las siguientes pueden ser más largas y sonar más fluidas
        val minimo = if (esPrimera) MINIMO_PRIMERA else MINIMO
        for (i in pendiente.indices) {
            val c = pendiente[i]
            val hayMas = i + 1 < pendiente.length
            if (c == '\n') {
                if (pendiente.substring(0, i).isNotBlank()) return i + 1
                continue
            }
            // Solo cortamos si después viene un espacio: así "3.5" o "claude.ai" no se parten
            if (!hayMas || !pendiente[i + 1].isWhitespace()) continue
            val largo = pendiente.substring(0, i + 1).trim().length
            when {
                c in FINALES && largo >= minimo && !(c == '.' && esAbreviatura(i)) -> return i + 1
                // Una primera frase muy larga se parte en una coma para no hacer esperar
                esPrimera && c in PAUSAS && largo >= LARGO_PARA_PAUSA -> return i + 1
            }
        }
        return null
    }

    private fun esAbreviatura(posicionDelPunto: Int): Boolean {
        var inicio = posicionDelPunto
        while (inicio > 0 && pendiente[inicio - 1].isLetter()) inicio--
        val palabra = pendiente.substring(inicio, posicionDelPunto).lowercase()
        return palabra.length == 1 || palabra in ABREVIATURAS
    }

    companion object {
        private const val MINIMO_PRIMERA = 6
        private const val MINIMO = 20
        private const val LARGO_PARA_PAUSA = 70
        private const val FINALES = ".!?…"
        private const val PAUSAS = ",;:"
        private val ABREVIATURAS = setOf("sr", "sra", "srta", "dr", "dra", "ud", "uds", "etc", "ej", "aprox", "núm", "num", "pág", "av", "tel", "vs")

        private val ENLACE_MD = Regex("\\[([^\\]]+)]\\([^)]*\\)")
        private val URL = Regex("https?://\\S+")
        private val MARCAS_MD = Regex("[*_`~#>|]+")
        private val VINETA = Regex("(?m)^\\s*(?:[-•]|\\d+[.)])\\s+")
        private val EMOJIS = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]")
        private val CITAS = Regex("\\[\\d+]")
        private val ESPACIOS = Regex("[ \\t]+")

        /**
         * Quita lo que un sintetizador de voz leería mal: markdown, enlaces, emojis.
         * Claude ya tiene la instrucción de no usarlos; esto es el cinturón de seguridad.
         */
        fun limpiarParaVoz(texto: String): String = texto
            .replace(ENLACE_MD, "$1")
            .replace(URL, "")
            .replace(VINETA, "")
            .replace(MARCAS_MD, "")
            .replace(EMOJIS, "")
            .replace(CITAS, "")
            .replace(ESPACIOS, " ")
            .trim()
    }
}
