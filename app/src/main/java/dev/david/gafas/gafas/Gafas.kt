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
import kotlinx.coroutines.delay
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
    private var revision: Job? = null

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

    /**
     * El permiso de cámara lo da la persona dentro de la app Meta AI; aquí solo se consulta.
     * Al volver de Meta AI las gafas tardan unos segundos en enterarse del permiso nuevo,
     * así que si dicen que no se les vuelve a preguntar un par de veces antes de creerles.
     */
    fun revisarPermisoDeCamara() {
        if (!listo) return
        revision?.cancel()
        revision = scope.launch {
            repeat(INTENTOS_PERMISO) { intento ->
                if (intento > 0) delay(ESPERA_PERMISO_MS)
                if (consultarPermisoDeCamara().getOrNull() == true) return@launch
            }
        }
    }

    /**
     * Les pregunta a las gafas si la cámara está permitida. Falla cuando no se pudo preguntar
     * (gafas apagadas, sin conexión, tiempo agotado), que no es lo mismo que un "no": en ese
     * caso se conserva lo último que se supo.
     */
    suspend fun consultarPermisoDeCamara(): Result<Boolean> {
        var respuesta: Result<Boolean> = Result.failure(IllegalStateException("el SDK no respondió"))
        Wearables.checkPermissionStatus(Permission.CAMERA)
            .onSuccess { estado -> respuesta = Result.success(estado == PermissionStatus.Granted) }
            .onFailure { error, _ -> respuesta = Result.failure(IllegalStateException(error.description)) }
        respuesta
            .onSuccess { anotarPermiso(it, "las gafas") }
            .onFailure { Diario.error("No pude preguntarles a las gafas por la cámara: ${it.message}") }
        return respuesta
    }

    /** Meta AI confirmó que la persona permitió la cámara. */
    fun camaraPermitidaEnMetaAi() = anotarPermiso(true, "Meta AI")

    private fun anotarPermiso(permitido: Boolean, fuente: String) {
        val antes = Estado.gafas.value.camaraPermitida
        Estado.gafas.update { it.copy(camaraPermitida = permitido) }
        if (permitido != antes) Diario.info("Cámara de las gafas ${if (permitido) "permitida" else "sin permiso"} (según $fuente)")
    }

    private const val INTENTOS_PERMISO = 4
    private const val ESPERA_PERMISO_MS = 2_500L
}
