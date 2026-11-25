package dev.david.gafas.herramientas

import org.json.JSONArray
import org.json.JSONObject

/**
 * EL ENCHUFE. Una herramienta es algo que Claude puede DECIDIR usar.
 *
 * Tú le describes qué hace ([descripcion]) y qué datos necesita ([parametros], en JSON
 * Schema); Claude te pide ejecutarla cuando le sirve y tú le devuelves un [Resultado].
 * Para darle a Claude una habilidad nueva solo hay que escribir una clase así y sumarla
 * a una [Capacidad] en el [Catalogo].
 */
interface Herramienta {
    /** Nombre técnico que ve Claude: minúsculas y guiones bajos. */
    val nombre: String

    /** Cuándo y para qué usarla. Entre mejor esté escrita, mejor decide Claude. */
    val descripcion: String

    val parametros: JSONObject

    /** Lo que se muestra en pantalla mientras corre: "Mirando por las gafas…". */
    val enCurso: String

    /** Lo que queda anotado en el chat cuando termina: "Miró por las gafas". */
    val hecho: String

    suspend fun ejecutar(entrada: JSONObject): Resultado
}

data class Resultado(
    val texto: String,
    val esError: Boolean = false,
    val imagenJpegBase64: String? = null,
    /** Ruta del JPEG guardado, para mostrar la miniatura en el chat. */
    val archivoFoto: String? = null,
) {
    /** El formato que espera la API dentro de un bloque tool_result. */
    fun comoContenido(): JSONArray {
        val contenido = JSONArray()
        imagenJpegBase64?.let { contenido.put(bloqueImagen(it)) }
        contenido.put(JSONObject().put("type", "text").put("text", texto))
        return contenido
    }

    companion object {
        fun error(mensaje: String) = Resultado(mensaje, esError = true)

        fun bloqueImagen(jpegBase64: String): JSONObject = JSONObject()
            .put("type", "image")
            .put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", jpegBase64))
    }
}

/**
 * Una capacidad es lo que ves como un interruptor en la pestaña Capacidades: agrupa una
 * o varias herramientas que tienen sentido juntas y los permisos de Android que necesitan.
 */
class Capacidad(
    val id: String,
    val titulo: String,
    val descripcion: String,
    val permisos: List<String> = emptyList(),
    val porDefecto: Boolean = true,
    val herramientas: List<Herramienta>,
)

/** Pequeños atajos para escribir JSON Schema sin tanto ruido. */
object Esquema {
    fun objeto(vararg propiedades: Pair<String, JSONObject>, requeridos: List<String> = emptyList()): JSONObject {
        val props = JSONObject()
        propiedades.forEach { (nombre, tipo) -> props.put(nombre, tipo) }
        val esquema = JSONObject().put("type", "object").put("properties", props)
        if (requeridos.isNotEmpty()) esquema.put("required", JSONArray(requeridos))
        return esquema
    }

    fun texto(descripcion: String): JSONObject = JSONObject().put("type", "string").put("description", descripcion)

    fun entero(descripcion: String): JSONObject = JSONObject().put("type", "integer").put("description", descripcion)

    fun logico(descripcion: String): JSONObject = JSONObject().put("type", "boolean").put("description", descripcion)

    fun opciones(descripcion: String, vararg valores: String): JSONObject =
        JSONObject().put("type", "string").put("description", descripcion).put("enum", JSONArray(valores.toList()))
}
