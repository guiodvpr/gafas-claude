package dev.david.gafas.herramientas

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import dev.david.gafas.nucleo.Tiempo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * EL CALENDARIO. Lee y escribe en el calendario del celular con CalendarContract, la base
 * de datos de calendarios de Android. Como tu cuenta de Google ya se sincroniza ahí, lo
 * que Claude cree aparece en Google Calendar sin configurar llaves ni permisos de Google.
 */
object Calendario {
    private const val MAXIMO_EVENTOS = 25

    class Consultar(private val context: Context) : Herramienta {
        override val nombre = "consultar_calendario"
        override val descripcion =
            "Lista los eventos del calendario de la persona entre dos momentos. Úsala para saber qué tiene " +
                "agendado, si está libre, o para encontrar el id de un evento antes de borrarlo."
        override val parametros: JSONObject = Esquema.objeto(
            "desde" to Esquema.texto("Inicio del rango en hora local, ISO 8601: 2026-11-17T00:00"),
            "hasta" to Esquema.texto("Fin del rango en hora local, ISO 8601: 2026-11-17T23:59"),
            "buscar" to Esquema.texto("Opcional: solo eventos cuyo título o lugar contenga este texto"),
            requeridos = listOf("desde", "hasta"),
        )
        override val enCurso = "Revisando tu calendario…"
        override val hecho = "Revisó el calendario"

        override suspend fun ejecutar(entrada: JSONObject): Resultado = withContext(Dispatchers.IO) {
            val desde = Tiempo.interpretar(entrada.optString("desde"))
                ?: return@withContext Resultado.error("No entendí la fecha 'desde'. Usa el formato 2026-11-17T09:00.")
            val hasta = Tiempo.interpretar(entrada.optString("hasta"))
                ?: return@withContext Resultado.error("No entendí la fecha 'hasta'. Usa el formato 2026-11-17T18:00.")
            val filtro = entrada.optString("buscar").trim().lowercase()

            val rango = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(rango, desde.toInstant().toEpochMilli())
            ContentUris.appendId(rango, hasta.toInstant().toEpochMilli())
            val columnas = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            )
            val lineas = mutableListOf<String>()
            // Instances (y no Events) porque ya trae resueltos los eventos que se repiten
            context.contentResolver.query(rango.build(), columnas, null, null, CalendarContract.Instances.BEGIN + " ASC")
                ?.use { cursor ->
                    while (cursor.moveToNext() && lineas.size < MAXIMO_EVENTOS) {
                        val titulo = cursor.getString(1).orEmpty().ifBlank { "(sin título)" }
                        val lugar = cursor.getString(5).orEmpty()
                        if (filtro.isNotEmpty() && filtro !in titulo.lowercase() && filtro !in lugar.lowercase()) continue
                        val todoElDia = cursor.getInt(4) == 1
                        // Los eventos de todo el día se guardan en UTC: se leen en UTC para no correr el día
                        val zona = if (todoElDia) ZoneOffset.UTC else ZoneId.systemDefault()
                        val inicio = Instant.ofEpochMilli(cursor.getLong(2)).atZone(zona)
                        val fin = Instant.ofEpochMilli(cursor.getLong(3)).atZone(zona)
                        lineas += buildString {
                            append("- ")
                            append(
                                if (todoElDia) "${Tiempo.decirDia(inicio)}, todo el día"
                                else "${Tiempo.decir(inicio)} a ${Tiempo.decirHora(fin)}",
                            )
                            append(": ").append(titulo)
                            if (lugar.isNotBlank()) append(" (en ").append(lugar).append(")")
                            append(" [id ").append(cursor.getLong(0)).append(", calendario ").append(cursor.getString(6).orEmpty()).append("]")
                        }
                    }
                }
            Resultado(if (lineas.isEmpty()) "No hay eventos en ese rango." else lineas.joinToString("\n"))
        }
    }

    class Crear(private val context: Context) : Herramienta {
        override val nombre = "crear_evento"
        override val descripcion =
            "Crea un evento en el calendario de la persona. Si no dice cuánto dura, asume una hora. " +
                "Para un evento de todo el día manda solo la fecha (2026-11-17) en 'inicio'."
        override val parametros: JSONObject = Esquema.objeto(
            "titulo" to Esquema.texto("Título del evento"),
            "inicio" to Esquema.texto("Cuándo empieza, hora local ISO 8601: 2026-11-17T15:00. Solo fecha = todo el día."),
            "fin" to Esquema.texto("Opcional: cuándo termina, mismo formato"),
            "lugar" to Esquema.texto("Opcional: dónde es"),
            "descripcion" to Esquema.texto("Opcional: notas del evento"),
            "aviso_minutos" to Esquema.entero("Opcional: minutos antes para la notificación. Por defecto 15."),
            requeridos = listOf("titulo", "inicio"),
        )
        override val enCurso = "Agendando…"
        override val hecho = "Creó un evento"

        override suspend fun ejecutar(entrada: JSONObject): Resultado = withContext(Dispatchers.IO) {
            val titulo = entrada.optString("titulo").trim()
            if (titulo.isEmpty()) return@withContext Resultado.error("Falta el título del evento.")
            val textoInicio = entrada.optString("inicio").trim()
            val inicio = Tiempo.interpretar(textoInicio)
                ?: return@withContext Resultado.error("No entendí la fecha de inicio. Usa el formato 2026-11-17T15:00.")
            val todoElDia = Tiempo.esSoloFecha(textoInicio)
            val calendario = calendarioPrincipal(context)
                ?: return@withContext Resultado.error("No encontré un calendario en el que se pueda escribir en este celular.")

            val valores = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendario.first)
                put(CalendarContract.Events.TITLE, titulo)
                entrada.optString("lugar").takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                entrada.optString("descripcion").takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                if (todoElDia) {
                    // Regla de Android: todo el día = medianoche UTC de ese día hasta la del siguiente
                    val dia = LocalDate.parse(textoInicio)
                    put(CalendarContract.Events.ALL_DAY, 1)
                    put(CalendarContract.Events.DTSTART, dia.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                    put(CalendarContract.Events.DTEND, dia.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                    put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                } else {
                    val fin = Tiempo.interpretar(entrada.optString("fin"))?.takeIf { it.isAfter(inicio) } ?: inicio.plusHours(1)
                    put(CalendarContract.Events.DTSTART, inicio.toInstant().toEpochMilli())
                    put(CalendarContract.Events.DTEND, fin.toInstant().toEpochMilli())
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                }
            }
            val creado = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, valores)
                ?: return@withContext Resultado.error("El calendario no aceptó el evento.")
            val id = ContentUris.parseId(creado)

            val aviso = if (entrada.has("aviso_minutos")) entrada.optInt("aviso_minutos", 15) else 15
            if (aviso >= 0) {
                runCatching {
                    context.contentResolver.insert(
                        CalendarContract.Reminders.CONTENT_URI,
                        ContentValues().apply {
                            put(CalendarContract.Reminders.EVENT_ID, id)
                            put(CalendarContract.Reminders.MINUTES, aviso)
                            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                        },
                    )
                }
            }
            val cuando = if (todoElDia) "${Tiempo.decirDia(inicio)}, todo el día" else Tiempo.decir(inicio)
            Resultado("Evento creado: \"$titulo\", $cuando, en el calendario ${calendario.second} [id $id].")
        }
    }

    class Borrar(private val context: Context) : Herramienta {
        override val nombre = "borrar_evento"
        override val descripcion =
            "Borra un evento del calendario por su id (sale de consultar_calendario). Es irreversible: " +
                "confirma antes con la persona diciéndole qué evento vas a borrar."
        override val parametros: JSONObject = Esquema.objeto(
            "id" to Esquema.entero("Id del evento"),
            requeridos = listOf("id"),
        )
        override val enCurso = "Borrando el evento…"
        override val hecho = "Borró un evento"

        override suspend fun ejecutar(entrada: JSONObject): Resultado = withContext(Dispatchers.IO) {
            val id = entrada.optLong("id", -1)
            if (id < 0) return@withContext Resultado.error("Falta el id del evento.")
            val borrados = context.contentResolver.delete(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null,
            )
            if (borrados > 0) Resultado("Evento borrado.") else Resultado.error("No encontré un evento con ese id.")
        }
    }

    /** El calendario donde se crean los eventos: el principal de la cuenta, o el primero en que se pueda escribir. */
    private fun calendarioPrincipal(context: Context): Pair<Long, String>? {
        val columnas = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.OWNER_ACCOUNT,
        )
        val escribibles = CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + " >= " + CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
        var mejor: Pair<Long, String>? = null
        var mejorPuntaje = -1
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, columnas, escribibles, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val puntaje = when {
                    cursor.getInt(2) == 1 -> 2
                    cursor.getString(3).orEmpty().equals(cursor.getString(4).orEmpty(), ignoreCase = true) -> 1
                    else -> 0
                }
                if (puntaje > mejorPuntaje) {
                    mejorPuntaje = puntaje
                    mejor = cursor.getLong(0) to cursor.getString(1).orEmpty()
                }
            }
        }
        return mejor
    }
}
