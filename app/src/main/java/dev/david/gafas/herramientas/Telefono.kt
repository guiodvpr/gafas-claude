package dev.david.gafas.herramientas

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.CancellationSignal
import android.view.KeyEvent
import dev.david.gafas.R
import dev.david.gafas.nucleo.Estado
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Dónde estás: coordenadas del celular convertidas a una dirección. */
class Ubicacion(private val context: Context) : Herramienta {
    override val nombre = "ubicacion_actual"
    override val descripcion =
        "Dice dónde está la persona ahora (dirección aproximada, barrio, ciudad y coordenadas). Úsala para " +
            "preguntas como '¿dónde estoy?' o antes de buscar en la web algo 'cerca de aquí'."
    override val parametros: JSONObject = Esquema.objeto()
    override val enCurso = "Ubicándote…"
    override val hecho = "Consultó tu ubicación"

    @SuppressLint("MissingPermission") // el Catálogo solo ofrece esta herramienta si el permiso está concedido
    override suspend fun ejecutar(entrada: JSONObject): Resultado {
        val gestor = context.getSystemService(LocationManager::class.java)
        val proveedor = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { gestor.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return Resultado.error("La ubicación del celular está apagada.")

        val fresca: Location? = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<Location?> { continuacion ->
                val cancelar = CancellationSignal()
                continuacion.invokeOnCancellation { cancelar.cancel() }
                gestor.getCurrentLocation(proveedor, cancelar, context.mainExecutor) { lugar ->
                    if (continuacion.isActive) continuacion.resume(lugar)
                }
            }
        }
        val lugar = fresca ?: runCatching { gestor.getLastKnownLocation(proveedor) }.getOrNull()
            ?: return Resultado.error("No logré obtener la ubicación. Puede que estés bajo techo o sin señal.")

        val direccion = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION") // la variante asíncrona nueva no aporta nada dentro de una corrutina
                Geocoder(context, Locale.forLanguageTag("es-CO")).getFromLocation(lugar.latitude, lugar.longitude, 1)
                    ?.firstOrNull()
            }.getOrNull()
        }
        val descripcion = buildString {
            if (direccion != null) {
                val partes = listOfNotNull(
                    direccion.getAddressLine(0),
                    direccion.subLocality,
                    direccion.locality,
                    direccion.adminArea,
                    direccion.countryName,
                ).distinct()
                append(partes.joinToString(", ")).append(". ")
            }
            append("Coordenadas: %.5f, %.5f (precisión de unos %d metros).".format(Locale.US, lugar.latitude, lugar.longitude, lugar.accuracy.toInt()))
        }
        return Resultado(descripcion)
    }
}

/** Controla lo que esté sonando (Spotify, YouTube Music, un pódcast) como si oprimieras los botones. */
class Medios(private val context: Context) : Herramienta {
    override val nombre = "control_multimedia"
    override val descripcion =
        "Controla la música o el audio que suena en el celular: pausar, reanudar, pasar de canción, devolverla " +
            "o cambiar el volumen. No elige canciones; solo maneja los controles."
    override val parametros: JSONObject = Esquema.objeto(
        "accion" to Esquema.opciones(
            "Qué hacer",
            "reproducir", "pausar", "siguiente", "anterior", "subir_volumen", "bajar_volumen",
        ),
        requeridos = listOf("accion"),
    )
    override val enCurso = "Controlando la música…"
    override val hecho = "Controló la música"

    override suspend fun ejecutar(entrada: JSONObject): Resultado {
        val audio = context.getSystemService(AudioManager::class.java)
        val accion = entrada.optString("accion")
        val tecla = when (accion) {
            "reproducir" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pausar" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "siguiente" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "anterior" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "subir_volumen" -> {
                repeat(2) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0) }
                return Resultado("Volumen arriba.")
            }
            "bajar_volumen" -> {
                repeat(2) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0) }
                return Resultado("Volumen abajo.")
            }
            else -> return Resultado.error("No conozco la acción '$accion'.")
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, tecla))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, tecla))
        return Resultado("Hecho: $accion.")
    }
}

/** Batería del celular y de las gafas, y qué conexión hay. */
class Dispositivo(private val context: Context) : Herramienta {
    override val nombre = "estado_dispositivos"
    override val descripcion = "Dice la batería del celular y de las gafas, y si hay conexión por wifi o datos."
    override val parametros: JSONObject = Esquema.objeto()
    override val enCurso = "Revisando los dispositivos…"
    override val hecho = "Revisó los dispositivos"

    override suspend fun ejecutar(entrada: JSONObject): Resultado {
        val bateria = context.getSystemService(BatteryManager::class.java)
        val celular = bateria.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val gafas = Estado.gafas.value
        val red = runCatching {
            val conexion = context.getSystemService(ConnectivityManager::class.java)
            val capacidades = conexion.getNetworkCapabilities(conexion.activeNetwork)
            when {
                capacidades == null -> "sin conexión"
                capacidades.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capacidades.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "datos móviles"
                else -> "otra red"
            }
        }.getOrDefault("desconocida")
        return Resultado(
            buildString {
                append("Celular: $celular % de batería")
                if (bateria.isCharging) append(", cargando")
                append(". ")
                append(
                    when {
                        gafas.bateria != null -> "Gafas${gafas.nombre.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}: ${gafas.bateria} % de batería. "
                        else -> "No tengo el dato de batería de las gafas. "
                    },
                )
                append("Conexión: $red.")
            },
        )
    }
}

/** "Mándame eso al celular": deja un texto o un enlace en una notificación para verlo después. */
class EnviarAlCelular(private val context: Context) : Herramienta {
    override val nombre = "enviar_al_celular"
    override val descripcion =
        "Deja en el celular una notificación con un texto (y opcionalmente un enlace que se abre al tocarla). " +
            "Úsala cuando la persona pida que le mandes o le guardes algo para verlo después en pantalla: un " +
            "enlace, una dirección, un resumen, una lista."
    override val parametros: JSONObject = Esquema.objeto(
        "titulo" to Esquema.texto("Título corto"),
        "texto" to Esquema.texto("El contenido"),
        "enlace" to Esquema.texto("Opcional: dirección web https que se abre al tocar la notificación"),
        requeridos = listOf("titulo", "texto"),
    )
    override val enCurso = "Enviando al celular…"
    override val hecho = "Envió algo al celular"

    override suspend fun ejecutar(entrada: JSONObject): Resultado {
        val titulo = entrada.optString("titulo").trim().ifEmpty { "De Claude" }
        val texto = entrada.optString("texto").trim()
        if (texto.isEmpty()) return Resultado.error("No hay nada que enviar.")
        val enlace = entrada.optString("enlace").trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }

        val gestor = context.getSystemService(NotificationManager::class.java)
        gestor.createNotificationChannel(NotificationChannel(CANAL, "Enviado por Claude", NotificationManager.IMPORTANCE_DEFAULT))
        val notificacion = Notification.Builder(context, CANAL)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(if (enlace != null) "$texto\n$enlace" else texto))
            .setAutoCancel(true)
        if (enlace != null) {
            val abrir = Intent(Intent.ACTION_VIEW, Uri.parse(enlace)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            notificacion.setContentIntent(
                PendingIntent.getActivity(context, enlace.hashCode(), abrir, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        gestor.notify((System.currentTimeMillis() % 100_000).toInt() + 1_000, notificacion.build())
        return Resultado("Listo, quedó en las notificaciones del celular.")
    }

    private companion object {
        const val CANAL = "enviados"
    }
}
