package dev.david.gafas.datos

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONArray

/**
 * Un archivo JSON pequeño en el almacenamiento privado de la app.
 *
 * ¿Por qué no una base de datos? Porque aquí guardamos decenas de cosas, no millones:
 * un archivo se entiende, se depura abriéndolo y no trae dependencias. El día que una
 * lista crezca de verdad, se cambia esta clase por Room y el resto de la app ni se entera.
 */
class Almacen(context: Context, nombre: String) {
    private val archivo = File(context.filesDir, nombre)

    fun leer(): JSONArray = synchronized(this) {
        runCatching { JSONArray(archivo.readText()) }.getOrDefault(JSONArray())
    }

    /** Escribe en segundo plano y de forma atómica: nunca queda un archivo a medias. */
    fun guardar(contenido: JSONArray) {
        val texto = contenido.toString()
        escritor.execute {
            synchronized(this) {
                runCatching {
                    val temporal = File(archivo.parentFile, archivo.name + ".tmp")
                    temporal.writeText(texto)
                    if (!temporal.renameTo(archivo)) {
                        archivo.writeText(texto)
                        temporal.delete()
                    }
                }
            }
        }
    }

    private companion object {
        val escritor = Executors.newSingleThreadExecutor()
    }
}
