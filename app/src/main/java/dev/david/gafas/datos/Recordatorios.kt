package dev.david.gafas.datos

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.david.gafas.servicio.RecordatorioReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

data class Recordatorio(val id: Int, val texto: String, val cuando: Long)

/**
 * Recordatorios propios de la app. Cada uno es una alarma del sistema (AlarmManager):
 * suena aunque el celular esté dormido o la app cerrada, y si el asistente está
 * encendido, además te lo dice al oído.
 */
object Recordatorios {
    private var almacen: Almacen? = null

    val pendientes = MutableStateFlow<List<Recordatorio>>(emptyList())

    fun iniciar(context: Context) {
        if (almacen != null) return
        val nuevo = Almacen(context, "recordatorios.json")
        almacen = nuevo
        val guardados = nuevo.leer()
        pendientes.value = (0 until guardados.length()).mapNotNull { i ->
            val o = guardados.optJSONObject(i) ?: return@mapNotNull null
            Recordatorio(o.optInt("id"), o.optString("texto"), o.optLong("cuando"))
        }
    }

    fun crear(context: Context, texto: String, cuando: Long): Recordatorio {
        val recordatorio = Recordatorio((pendientes.value.maxOfOrNull { it.id } ?: 0) + 1, texto.trim(), cuando)
        pendientes.update { (it + recordatorio).sortedBy { r -> r.cuando } }
        persistir()
        programar(context, recordatorio)
        return recordatorio
    }

    fun cancelar(context: Context, id: Int): Boolean {
        val existe = pendientes.value.any { it.id == id }
        if (existe) {
            context.getSystemService(AlarmManager::class.java).cancel(intento(context, id))
            quitar(id)
        }
        return existe
    }

    /** Lo llama el receptor cuando la alarma suena: lo saca de la lista y lo devuelve. */
    fun cumplir(id: Int): Recordatorio? {
        val recordatorio = pendientes.value.firstOrNull { it.id == id }
        quitar(id)
        return recordatorio
    }

    /** Tras reiniciar el celular las alarmas se pierden: hay que volver a ponerlas. */
    fun reprogramar(context: Context) {
        pendientes.value.forEach { programar(context, it) }
    }

    private fun programar(context: Context, recordatorio: Recordatorio) {
        val alarmas = context.getSystemService(AlarmManager::class.java)
        val intento = intento(context, recordatorio.id)
        val cuando = maxOf(recordatorio.cuando, System.currentTimeMillis() + 1_000)
        try {
            if (alarmas.canScheduleExactAlarms()) {
                alarmas.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, intento)
            } else {
                // Sin permiso de alarmas exactas puede sonar con unos minutos de margen
                alarmas.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, intento)
            }
        } catch (e: SecurityException) {
            alarmas.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, intento)
        }
    }

    private fun intento(context: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, RecordatorioReceiver::class.java).putExtra(RecordatorioReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun quitar(id: Int) {
        pendientes.update { lista -> lista.filterNot { it.id == id } }
        persistir()
    }

    private fun persistir() {
        val lista = JSONArray()
        pendientes.value.forEach {
            lista.put(JSONObject().put("id", it.id).put("texto", it.texto).put("cuando", it.cuando))
        }
        almacen?.guardar(lista)
    }
}
