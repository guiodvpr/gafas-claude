package dev.david.gafas.gafas

import android.content.Context
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import dev.david.gafas.nucleo.Diario
import dev.david.gafas.nucleo.Estado
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * El SDK de Meta (Wearables Device Access Toolkit). Solo hace falta para la CÁMARA y para
 * saber la batería de las gafas: el micrófono y los parlantes van por Bluetooth normal.
 *
 * Aquí se arranca el SDK y se mantiene al día lo que la interfaz muestra de las gafas.
 */
object Gafas {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var seguimientoDeBateria: Job? = null

    @Volatile
    var listo = false
        private set

    /** Necesita el permiso de Bluetooth concedido. Llamarlo varias veces no hace daño. */
    fun inicializar(context: Context) {
        if (listo) return
        Wearables.initialize(context.applicationContext)
            .onSuccess {
                listo = true
                Estado.gafas.update { it.copy(sdkListo = true) }
                observar()
                Diario.info("SDK de Meta listo")
            }
            .onFailure { error, _ ->
                Estado.gafas.update { it.copy(registro = "Error del SDK: ${error.description}") }
                Diario.error("El SDK de Meta no arrancó: ${error.description}")
            }
    }

    private fun observar() {
        scope.launch {
            Wearables.registrationState.collect { estado ->
                val registrada = estado == RegistrationState.REGISTERED
                Estado.gafas.update {
                    it.copy(
                        registrada = registrada,
                        registro = when (estado) {
                            RegistrationState.REGISTERED -> "Conectada"
                            RegistrationState.REGISTERING -> "Conectando…"
                            RegistrationState.UNREGISTERING -> "Desconectando…"
                            else -> "Sin conectar"
                        },
                    )
                }
                if (registrada) revisarPermisoDeCamara()
            }
        }
        scope.launch {
            Wearables.devices.collect { dispositivos ->
                seguimientoDeBateria?.cancel()
                val primero = dispositivos.firstOrNull()
                if (primero == null) {
                    Estado.gafas.update { it.copy(nombre = "", bateria = null) }
                    return@collect
                }
                seguimientoDeBateria = scope.launch {
                    Wearables.devicesMetadata[primero]?.collect { datos ->
                        Estado.gafas.update {
                            it.copy(nombre = datos.name, bateria = porcentaje(datos.batteryLevel))
                        }
                    }
                }
            }
        }
    }

    private fun porcentaje(valor: Int?): Int? = valor?.takeIf { it in 0..100 }

    /** El permiso de cámara lo da la persona dentro de la app Meta AI; aquí solo se consulta. */
    fun revisarPermisoDeCamara() {
        if (!listo) return
        scope.launch {
            Wearables.checkPermissionStatus(Permission.CAMERA).onSuccess { estado ->
                Estado.gafas.update { it.copy(camaraPermitida = estado == PermissionStatus.Granted) }
            }
        }
    }
}
