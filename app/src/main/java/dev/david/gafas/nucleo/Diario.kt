package dev.david.gafas.nucleo

import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Bitácora en memoria. Sirve para la pantalla de Diagnóstico: cuando algo falle con las
 * gafas puestas, ahí queda qué pasó y cuánto tardó cada cosa, sin conectar el celular al PC.
 */
object Diario {
    private const val TAG = "GafasClaude"
    private const val MAXIMO = 200
    private val hora = DateTimeFormatter.ofPattern("HH:mm:ss")

    val lineas = MutableStateFlow<List<String>>(emptyList())

    fun info(mensaje: String) {
        Log.i(TAG, mensaje)
        anotar(mensaje)
    }

    fun error(mensaje: String, causa: Throwable? = null) {
        Log.e(TAG, mensaje, causa)
        anotar("⚠ $mensaje" + (causa?.message?.let { " · $it" } ?: ""))
    }

    private fun anotar(mensaje: String) {
        val linea = "${LocalTime.now().format(hora)}  $mensaje"
        lineas.update { (it + linea).takeLast(MAXIMO) }
    }
}
