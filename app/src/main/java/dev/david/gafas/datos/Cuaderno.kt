package dev.david.gafas.datos

import android.content.Context
import dev.david.gafas.nucleo.Texto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

data class Apunte(val id: Int, val texto: String, val hora: Long)

/** Una lista de textos cortos que se guarda en disco. Base de la memoria y de las notas. */
open class Cuaderno(private val archivo: String, private val maximo: Int) {
    private var almacen: Almacen? = null

    val apuntes = MutableStateFlow<List<Apunte>>(emptyList())

    fun iniciar(context: Context) {
        if (almacen != null) return
        val nuevo = Almacen(context, archivo)
        almacen = nuevo
        val guardados = nuevo.leer()
        apuntes.value = (0 until guardados.length()).mapNotNull { i ->
            val o = guardados.optJSONObject(i) ?: return@mapNotNull null
            Apunte(o.optInt("id"), o.optString("texto"), o.optLong("hora"))
        }
    }

    fun agregar(texto: String): Apunte {
        val apunte = Apunte((apuntes.value.maxOfOrNull { it.id } ?: 0) + 1, texto.trim(), System.currentTimeMillis())
        apuntes.update { (it + apunte).takeLast(maximo) }
        persistir()
        return apunte
    }

    fun quitar(id: Int): Boolean {
        val antes = apuntes.value.size
        apuntes.update { lista -> lista.filterNot { it.id == id } }
        persistir()
        return apuntes.value.size < antes
    }

    /** Los apuntes que contienen todas las palabras de la consulta, sin importar tildes ni mayúsculas. */
    fun buscar(consulta: String): List<Apunte> {
        val palabras = Texto.normalizar(consulta).split(' ').filter { it.length > 2 }
        if (palabras.isEmpty()) return apuntes.value
        return apuntes.value.filter { apunte ->
            val contenido = Texto.normalizar(apunte.texto)
            palabras.all { it in contenido }
        }
    }

    private fun persistir() {
        val lista = JSONArray()
        apuntes.value.forEach { lista.put(JSONObject().put("id", it.id).put("texto", it.texto).put("hora", it.hora)) }
        almacen?.guardar(lista)
    }
}

/** Lo que Claude sabe de ti entre conversaciones: datos estables que le pediste recordar. */
object Memoria : Cuaderno("memoria.json", maximo = 80)

/** Notas rápidas dictadas desde las gafas. */
object Notas : Cuaderno("notas.json", maximo = 500)
