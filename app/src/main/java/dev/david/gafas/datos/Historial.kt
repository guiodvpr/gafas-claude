package dev.david.gafas.datos

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

enum class Rol { USUARIO, CLAUDE, SISTEMA }

data class Mensaje(
    val id: Long,
    val rol: Rol,
    val texto: String,
    val hora: Long,
    /** Qué hizo Claude para responder: "Miró por las gafas", "Buscó en la web"… */
    val acciones: List<String> = emptyList(),
    /** Ruta del JPEG si en este mensaje hubo una foto de las gafas. */
    val foto: String? = null,
)

/** La conversación que ves en la pestaña Chat. Sobrevive a cerrar la app. */
object Historial {
    private const val MAXIMO = 300
    private var almacen: Almacen? = null

    val mensajes = MutableStateFlow<List<Mensaje>>(emptyList())

    fun iniciar(context: Context) {
        if (almacen != null) return
        val nuevo = Almacen(context, "historial.json")
        almacen = nuevo
        val guardados = nuevo.leer()
        mensajes.value = (0 until guardados.length()).mapNotNull { i ->
            val o = guardados.optJSONObject(i) ?: return@mapNotNull null
            val acciones = o.optJSONArray("acciones")
            Mensaje(
                id = o.optLong("id"),
                rol = runCatching { Rol.valueOf(o.optString("rol")) }.getOrDefault(Rol.SISTEMA),
                texto = o.optString("texto"),
                hora = o.optLong("hora"),
                acciones = if (acciones == null) emptyList() else (0 until acciones.length()).map { acciones.optString(it) },
                foto = o.optString("foto").takeIf { it.isNotBlank() },
            )
        }
    }

    fun agregar(rol: Rol, texto: String, acciones: List<String> = emptyList(), foto: String? = null): Mensaje {
        val mensaje = Mensaje(System.nanoTime(), rol, texto, System.currentTimeMillis(), acciones, foto)
        mensajes.update { (it + mensaje).takeLast(MAXIMO) }
        persistir()
        return mensaje
    }

    fun quitar(id: Long) {
        mensajes.update { lista -> lista.filterNot { it.id == id } }
        persistir()
    }

    fun borrarTodo() {
        mensajes.value = emptyList()
        persistir()
    }

    private fun persistir() {
        val lista = JSONArray()
        mensajes.value.forEach { m ->
            val o = JSONObject()
                .put("id", m.id)
                .put("rol", m.rol.name)
                .put("texto", m.texto)
                .put("hora", m.hora)
            if (m.acciones.isNotEmpty()) o.put("acciones", JSONArray(m.acciones))
            m.foto?.let { o.put("foto", it) }
            lista.put(o)
        }
        almacen?.guardar(lista)
    }
}
