package dev.david.gafas.herramientas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import dev.david.gafas.gafas.Gafas
import dev.david.gafas.nucleo.Diario
import dev.david.gafas.nucleo.Estado
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * LOS OJOS. Toma una foto con la cámara de las gafas usando el SDK de Meta.
 *
 * La secuencia que exige el SDK es siempre la misma:
 *   sesión con las gafas → agregar cámara → arrancar el video → capturePhoto() → soltar la cámara
 *
 * La sesión se deja abierta 2 minutos por si vuelves a preguntar algo visual (la segunda
 * foto sale bastante más rápido); después se cierra sola para no ocupar las gafas.
 */
class CamaraGafas(private val context: Context) : Herramienta {
    override val nombre = "ver_camara"
    override val descripcion =
        "Toma una foto con la cámara de las gafas: muestra exactamente lo que la persona tiene al frente " +
            "en este momento. Úsala cuando la respuesta dependa de lo que está viendo (identificar algo, " +
            "leer un texto, describir un lugar, comparar productos)."
    override val parametros: JSONObject get() = Esquema.objeto()
    override val enCurso = "Mirando por las gafas…"
    override val hecho = "Miró por las gafas"

    /** Una foto lista para mandarle a Claude y para mostrar en el chat. */
    class Captura(val jpegBase64: String, val archivo: String?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val candado = Mutex()
    private var sesion: DeviceSession? = null
    private val estadoSesion = MutableStateFlow<DeviceSessionState?>(null)
    private var vigias: List<Job> = emptyList()
    private var cierreProgramado: Job? = null

    /**
     * En modo parcial la sesión con las gafas se queda abierta mientras dure el modo:
     * abrirla cuesta uno o dos segundos que no queremos pagar en cada foto.
     */
    @Volatile var mantenerSesion = false

    /** ¿Está todo listo para tomar una foto sin pedirle nada a la persona? */
    val disponible: Boolean
        get() = Gafas.listo && Estado.gafas.value.registrada && Estado.gafas.value.camaraPermitida

    override suspend fun ejecutar(entrada: JSONObject): Resultado =
        capturar().fold(
            onSuccess = { Resultado("Foto tomada ahora mismo con las gafas.", imagenJpegBase64 = it.jpegBase64, archivoFoto = it.archivo) },
            onFailure = { Resultado.error(it.message ?: "No pude tomar la foto.") },
        )

    /**
     * Toma la foto. Si falla, el mensaje del error ya viene redactado para decírselo a la persona.
     * [nitida] la deja más grande y con menos compresión: hace falta para leer letra de pantalla.
     */
    suspend fun capturar(nitida: Boolean = false): Result<Captura> = withContext(Dispatchers.Main) {
        candado.withLock {
            try {
                Result.success(tomar(nitida))
            } catch (e: Falla) {
                Diario.error("Cámara: ${e.message}")
                Result.failure(e)
            }
        }
    }

    /** Abre la sesión con las gafas por adelantado, para que la primera foto salga más rápido. */
    suspend fun precalentar() {
        if (!disponible) return
        withContext(Dispatchers.Main) {
            candado.withLock {
                cierreProgramado?.cancel()
                if (sesionLista() != null) Diario.info("Sesión con las gafas lista de antemano")
                programarCierre()
            }
        }
    }

    private class Falla(mensaje: String) : Exception(mensaje)

    private suspend fun tomar(nitida: Boolean): Captura {
        if (!Gafas.listo) throw Falla("El enlace con las gafas no está listo. Abre la app y acepta el permiso de Bluetooth.")
        if (!Estado.gafas.value.registrada) {
            throw Falla("La cámara no está conectada todavía: en la app, toca Conectar con Meta AI.")
        }
        var permiso: PermissionStatus? = null
        Wearables.checkPermissionStatus(Permission.CAMERA).onSuccess { permiso = it }
        val permitido = permiso == PermissionStatus.Granted
        Estado.gafas.update { it.copy(camaraPermitida = permitido) }
        if (!permitido) throw Falla("Falta el permiso de cámara de las gafas: en la app, toca Permitir cámara.")

        cierreProgramado?.cancel()
        val activa = sesionLista() ?: throw Falla("No logré conectarme a las gafas. ¿Están puestas y encendidas?")

        var detalle = ""
        var camara: Camera? = null
        activa.addCamera(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 15))
            .onSuccess { camara = it }
            .onFailure { error, _ -> detalle = error.description }
        val cam = camara ?: throw Falla("No pude activar la cámara. $detalle".trim())

        val estadoVideo = MutableStateFlow<StreamState?>(null)
        val vigia = scope.launch { cam.stream.state.collect { estadoVideo.value = it } }
        try {
            cam.stream.start().onFailure { error, _ -> detalle = error.description }
            val enVivo = withTimeoutOrNull(10_000) { estadoVideo.first { it == StreamState.STREAMING } }
            if (enVivo == null) throw Falla("La cámara no arrancó a tiempo. $detalle".trim())
            delay(400) // un instante para que la exposición se acomode

            var foto: PhotoData? = null
            cam.stream.capturePhoto()
                .onSuccess { foto = it }
                .onFailure { error, _ -> detalle = error.description }
            val datos = foto ?: throw Falla("No pude tomar la foto. $detalle".trim())

            return withContext(Dispatchers.Default) {
                val bitmap = decodificar(datos) ?: throw Falla("La foto llegó dañada.")
                val jpeg = if (nitida) aJpeg(bitmap, LADO_NITIDO, 90) else aJpeg(bitmap, LADO_MAXIMO, 85)
                Captura(Base64.encodeToString(jpeg, Base64.NO_WRAP), guardar(jpeg))
            }
        } finally {
            vigia.cancel()
            runCatching { cam.stop() } // suelta la cámara; la sesión sigue viva
            programarCierre()
        }
    }

    /** La sesión se cierra sola si nadie la usa un rato, para no ocupar las gafas. */
    private fun programarCierre() {
        cierreProgramado?.cancel()
        cierreProgramado = scope.launch {
            delay(if (mantenerSesion) SESION_PARCIAL_MS else SESION_OCIOSA_MS)
            candado.withLock { cerrarSesion() }
        }
    }

    private suspend fun sesionLista(): DeviceSession? {
        sesion?.let { if (estadoSesion.value == DeviceSessionState.STARTED) return it }
        cerrarSesion()

        var nueva: DeviceSession? = null
        Wearables.createSession(AutoDeviceSelector()).onSuccess { nueva = it }
        val creada = nueva ?: return null

        // Primero escuchamos y después arrancamos: así no se escapa ningún cambio de estado
        vigias = listOf(
            scope.launch { creada.state.collect { estadoSesion.value = it } },
            scope.launch { creada.errors.collect { Diario.error("Sesión con las gafas: ${it.description}") } },
        )
        sesion = creada
        creada.start()
        val arranco = withTimeoutOrNull(12_000) { estadoSesion.first { it == DeviceSessionState.STARTED } }
        if (arranco == null) {
            cerrarSesion()
            return null
        }
        return creada
    }

    private fun cerrarSesion() {
        vigias.forEach { it.cancel() }
        vigias = emptyList()
        runCatching { sesion?.stop() }
        sesion = null
        estadoSesion.value = null
    }

    fun cerrar() {
        cierreProgramado?.cancel()
        cerrarSesion()
        scope.cancel()
    }

    private fun decodificar(foto: PhotoData): Bitmap? = when (foto) {
        is PhotoData.Bitmap -> foto.bitmap
        is PhotoData.HEIC -> {
            val buffer = foto.data.duplicate().apply { rewind() }
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { enderezar(it, bytes) }
        }
    }

    /** Las gafas guardan la orientación en los metadatos; si no se aplica, Claude ve la foto de lado. */
    private fun enderezar(bitmap: Bitmap, bytes: ByteArray): Bitmap {
        val grados = runCatching { ByteArrayInputStream(bytes).use { ExifInterface(it).rotationDegrees } }.getOrDefault(0)
        if (grados == 0) return bitmap
        val giro = Matrix().apply { postRotate(grados.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, giro, true)
    }

    /** Reduce la foto al tamaño que Claude aprovecha: ve igual de bien y la subida es mucho más rápida. */
    private fun aJpeg(bitmap: Bitmap, lado: Float, calidad: Int): ByteArray {
        val escala = min(1f, lado / max(bitmap.width, bitmap.height).toFloat())
        val reducida = if (escala < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * escala).toInt(), (bitmap.height * escala).toInt(), true)
        } else {
            bitmap
        }
        return ByteArrayOutputStream().also { reducida.compress(Bitmap.CompressFormat.JPEG, calidad, it) }.toByteArray()
    }

    /** Guarda la foto para la miniatura del chat y conserva solo las más recientes. */
    private fun guardar(jpeg: ByteArray): String? = runCatching {
        val carpeta = File(context.filesDir, "fotos").apply { mkdirs() }
        val archivo = File(carpeta, "foto_${System.currentTimeMillis()}.jpg")
        archivo.writeBytes(jpeg)
        carpeta.listFiles()?.sortedByDescending { it.lastModified() }?.drop(FOTOS_GUARDADAS)?.forEach { it.delete() }
        archivo.absolutePath
    }.getOrNull()

    companion object {
        private const val SESION_OCIOSA_MS = 2 * 60 * 1000L
        private const val SESION_PARCIAL_MS = 30 * 60 * 1000L
        private const val LADO_MAXIMO = 1280f

        // 1568 px es lo máximo que Claude aprovecha; más grande no aporta y solo pesa más
        private const val LADO_NITIDO = 1568f
        private const val FOTOS_GUARDADAS = 40
    }
}
