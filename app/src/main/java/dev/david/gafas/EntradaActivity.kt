package dev.david.gafas

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import dev.david.gafas.servicio.AsistenteService

/**
 * LA PUERTA DE ENTRADA. Es lo que abre el ícono de la app y lo que abre el botón lateral del
 * Samsung (pulsar dos veces → abrir app → Gafas + Claude). No dibuja nada; solo decide:
 *
 *  - Celular BLOQUEADO → "toma la foto y responde", vibra una vez y se cierra.
 *    No muestra nada ni pide desbloquear (showWhenLocked en el manifiesto): la respuesta
 *    llega a las gafas. Si el asistente está apagado, vibra dos veces: no pudo.
 *  - Celular DESBLOQUEADO → abre la app normal.
 *
 * Vive en su propia tarea (taskAffinity) para que Android la cree de nuevo en cada toque,
 * aunque la app esté abierta de fondo. Si compartiera tarea con la pantalla principal,
 * Android traería esa pantalla al frente y esta puerta nunca se enteraría.
 */
class EntradaActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bloqueado = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (bloqueado) {
            setShowWhenLocked(true)
            if (AsistenteService.instancia != null) {
                AsistenteService.enviar(this, AsistenteService.ACCION_FOTO)
                vibrar(longArrayOf(0, 40))
            } else {
                vibrar(longArrayOf(0, 60, 120, 60)) // dos toques: el asistente está apagado
            }
        } else {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    /** Se siente en la mano pero no se oye. */
    private fun vibrar(patron: LongArray) {
        runCatching { getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(patron, -1)) }
    }
}
