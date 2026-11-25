package dev.david.gafas

import android.app.Activity
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import dev.david.gafas.servicio.AsistenteService

/**
 * EL ACCESO DIRECTO INVISIBLE. Aparece en el celular como una app aparte, "Foto parcial",
 * para poder asignarla al botón lateral del Samsung (pulsar dos veces → abrir app).
 *
 * No dibuja nada: se abre, le dice al asistente "toma la foto y responde", vibra un instante
 * para confirmar y se cierra, todo en menos de lo que tarda en prender la pantalla.
 * Puede abrirse con el celular bloqueado sin pedir desbloqueo (showWhenLocked en el manifiesto),
 * porque no muestra ningún dato: solo dispara la foto, y la respuesta llega a las gafas.
 */
class FotoRapidaActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        if (AsistenteService.instancia != null) {
            AsistenteService.enviar(this, AsistenteService.ACCION_FOTO)
            vibrar()
        } else {
            Toast.makeText(this, "Primero inicia el asistente en Gafas + Claude", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    /** Un toque corto que se siente en la mano pero no se oye. */
    private fun vibrar() {
        runCatching {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
