package dev.david.gafas.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import dev.david.gafas.nucleo.Diario
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * EL ENRUTADOR. Las gafas se presentan ante el celular como un manos libres Bluetooth
 * (perfil HFP, el mismo de una llamada). Para oír por su micrófono y hablar por sus
 * parlantes hay que decirle a Android: "la voz entra y sale por este dispositivo".
 *
 * Es el mismo procedimiento que usa el ejemplo oficial de Meta, y no necesita su SDK.
 */
class RutaAudio(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var modoAnterior = AudioManager.MODE_NORMAL
    private var enrutado = false
    private var vigia: AudioDeviceCallback? = null

    /** ¿Hay unas gafas (o cualquier manos libres Bluetooth) conectadas ahora mismo? */
    fun hayGafas(): Boolean = dispositivoDeComunicacion() != null

    /** ¿Hay una llamada en curso? Mientras dure, el micrófono es del teléfono. */
    fun enLlamada(): Boolean = audio.mode == AudioManager.MODE_IN_CALL

    /**
     * Abre el canal de voz hacia las gafas. Devuelve el micrófono de las gafas, o null si no
     * hay gafas conectadas o el canal no abrió (entonces se usará el micrófono del celular).
     */
    suspend fun activar(): AudioDeviceInfo? {
        val gafas = dispositivoDeComunicacion() ?: return null
        val listo = try {
            modoAnterior = audio.mode
            enrutado = true
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            // El canal Bluetooth tarda un momento en abrirse: esperamos hasta 4 segundos
            audio.setCommunicationDevice(gafas) && withTimeoutOrNull(4_000) {
                while (audio.communicationDevice?.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) delay(100)
                true
            } == true
        } catch (e: CancellationException) {
            liberar()
            throw e
        } catch (e: Exception) {
            Diario.error("No pude enrutar el audio a las gafas", e)
            false
        }
        val microfono = if (listo) microfonoDeGafas() else null
        if (microfono == null) liberar()
        return microfono
    }

    fun liberar() {
        if (!enrutado) return
        runCatching { audio.clearCommunicationDevice() }
        runCatching { audio.mode = modoAnterior }
        enrutado = false
    }

    /** Avisa cuando se conectan o desconectan las gafas, para cambiar de micrófono en caliente. */
    fun vigilar(alCambiar: () -> Unit) {
        dejarDeVigilar()
        val nuevo = object : AudioDeviceCallback() {
            // Android llama una primera vez con todo lo que ya estaba conectado: eso no es un cambio
            private var primera = true

            override fun onAudioDevicesAdded(agregados: Array<out AudioDeviceInfo>) {
                if (primera) {
                    primera = false
                    return
                }
                if (agregados.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) alCambiar()
            }

            override fun onAudioDevicesRemoved(quitados: Array<out AudioDeviceInfo>) {
                if (quitados.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) alCambiar()
            }
        }
        vigia = nuevo
        audio.registerAudioDeviceCallback(nuevo, Handler(Looper.getMainLooper()))
    }

    fun dejarDeVigilar() {
        vigia?.let { audio.unregisterAudioDeviceCallback(it) }
        vigia = null
    }

    private fun dispositivoDeComunicacion(): AudioDeviceInfo? = runCatching {
        // Sin el permiso BLUETOOTH_CONNECT esto lanza SecurityException: lo tratamos como "no hay gafas"
        audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }.getOrNull()

    private fun microfonoDeGafas(): AudioDeviceInfo? = runCatching {
        audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }.getOrNull()
}
