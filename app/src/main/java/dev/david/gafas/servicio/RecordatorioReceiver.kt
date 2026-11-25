package dev.david.gafas.servicio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.david.gafas.MainActivity
import dev.david.gafas.R
import dev.david.gafas.datos.Recordatorios

/** Suena cuando llega la hora de un recordatorio: notificación siempre, y voz si el asistente está encendido. */
class RecordatorioReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Recordatorios.iniciar(context.applicationContext)
        val recordatorio = Recordatorios.cumplir(intent.getIntExtra(EXTRA_ID, -1)) ?: return

        val gestor = context.getSystemService(NotificationManager::class.java)
        gestor.createNotificationChannel(NotificationChannel(CANAL, "Recordatorios", NotificationManager.IMPORTANCE_HIGH))
        val abrir = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        gestor.notify(
            10_000 + recordatorio.id,
            Notification.Builder(context, CANAL)
                .setSmallIcon(R.drawable.ic_notificacion)
                .setContentTitle("Recordatorio")
                .setContentText(recordatorio.texto)
                .setStyle(Notification.BigTextStyle().bigText(recordatorio.texto))
                .setAutoCancel(true)
                .setContentIntent(abrir)
                .build(),
        )
        AsistenteService.instancia?.anunciar(recordatorio.texto)
    }

    companion object {
        const val EXTRA_ID = "id"
        private const val CANAL = "recordatorios"
    }
}

/** Al reiniciar el celular Android borra las alarmas: este receptor las vuelve a poner. */
class ArranqueReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Recordatorios.iniciar(context.applicationContext)
        Recordatorios.reprogramar(context.applicationContext)
    }
}
