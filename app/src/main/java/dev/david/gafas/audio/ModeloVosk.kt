package dev.david.gafas.audio

import android.content.Context
import dev.david.gafas.nucleo.Diario
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.vosk.Model

/**
 * El "cerebro" del oído offline: un modelo de español de unos 40 MB.
 * Se descarga UNA sola vez, la primera vez que inicias el asistente; después vive en el celular.
 */
object ModeloVosk {
    private const val NOMBRE = "vosk-model-small-es-0.42"
    private const val DIRECCION = "https://alphacephei.com/vosk/models/$NOMBRE.zip"

    @Volatile private var cargado: Model? = null

    fun yaDescargado(context: Context): Boolean = File(File(context.filesDir, NOMBRE), ".listo").exists()

    /** Cargar el modelo tarda un par de segundos, así que se conserva mientras viva el proceso. */
    suspend fun cargar(context: Context, progreso: (Int?) -> Unit): Model = withContext(Dispatchers.IO) {
        cargado?.let { return@withContext it }
        val carpeta = File(context.filesDir, NOMBRE)
        val marca = File(carpeta, ".listo")
        if (!marca.exists()) {
            carpeta.deleteRecursively()
            Diario.info("Descargando el modelo de voz…")
            try {
                descargar(context.filesDir, progreso) { ensureActive() }
            } finally {
                progreso(null)
            }
            marca.writeText("ok")
        }
        Model(carpeta.absolutePath).also { cargado = it }
    }

    private fun descargar(destino: File, progreso: (Int?) -> Unit, verificar: () -> Unit) {
        val zip = File(destino, "$NOMBRE.zip")
        val conexion = (URL(DIRECCION).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            if (conexion.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("el servidor del modelo respondió ${conexion.responseCode}")
            }
            val total = conexion.contentLengthLong
            conexion.inputStream.use { entrada ->
                zip.outputStream().use { salida ->
                    val buffer = ByteArray(64 * 1024)
                    var leidos = 0L
                    var ultimo = -1
                    while (true) {
                        verificar()
                        val n = entrada.read(buffer)
                        if (n < 0) break
                        salida.write(buffer, 0, n)
                        leidos += n
                        if (total > 0) {
                            val porcentaje = (leidos * 100 / total).toInt()
                            if (porcentaje != ultimo) {
                                ultimo = porcentaje
                                progreso(porcentaje)
                            }
                        }
                    }
                }
            }
        } finally {
            conexion.disconnect()
        }

        val raiz = destino.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { entradas ->
            while (true) {
                val entrada = entradas.nextEntry ?: break
                val archivo = File(destino, entrada.name)
                // Nunca escribir fuera de la carpeta de la app, diga lo que diga el zip
                if (!archivo.canonicalPath.startsWith(raiz)) throw IOException("el zip trae rutas inválidas")
                if (entrada.isDirectory) {
                    archivo.mkdirs()
                } else {
                    archivo.parentFile?.mkdirs()
                    archivo.outputStream().use { entradas.copyTo(it) }
                }
            }
        }
        zip.delete()
    }
}
