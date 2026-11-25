package dev.david.gafas.nucleo

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** En qué anda el asistente. La pantalla, el orbe y la notificación solo leen esto. */
enum class Fase(val etiqueta: String) {
    APAGADO("Apagado"),
    PREPARANDO("Preparando"),
    ESPERANDO("Listo"),
    ESCUCHANDO("Te escucho"),
    PENSANDO("Pensando"),
    ACTUANDO("Trabajando"),
    HABLANDO("Hablando"),
    ERROR("Necesito atención"),
    ;

    val encendido: Boolean get() = this != APAGADO && this != ERROR
    val enTurno: Boolean get() = this == PENSANDO || this == ACTUANDO || this == HABLANDO
}

/** Por dónde está entrando la voz ahora mismo. */
enum class Entrada { NINGUNA, GAFAS, TELEFONO }

/** Una respuesta del modo parcial: el texto, la foto de donde salió y cuánto tardó. */
data class RespuestaParcial(val texto: String, val foto: String?, val segundos: Float, val lista: Boolean)

/** Lo que sabemos de las gafas por el SDK de Meta (solo hace falta para la cámara). */
data class InfoGafas(
    val sdkListo: Boolean = false,
    val registrada: Boolean = false,
    val registro: String = "Sin iniciar",
    val nombre: String = "",
    val bateria: Int? = null,
    val camaraPermitida: Boolean = false,
)

/**
 * Tablero compartido: el servicio escribe, la interfaz lee.
 * Todo vive en un solo proceso, así que basta un objeto global con flujos.
 */
object Estado {
    val fase = MutableStateFlow(Fase.APAGADO)

    /** Línea de ayuda bajo el orbe ("Di «oye claudio»", "Buscando en la web…"). */
    val detalle = MutableStateFlow("")

    /** Lo que el reconocedor va entendiendo mientras hablas. */
    val parcial = MutableStateFlow("")

    /** La respuesta de Claude mientras se va escribiendo. */
    val respuestaViva = MutableStateFlow("")

    /** Volumen del micrófono, de 0 a 1. Mueve el orbe. */
    val nivel = MutableStateFlow(0f)

    val entrada = MutableStateFlow(Entrada.NINGUNA)
    val gafas = MutableStateFlow(InfoGafas())

    /** La última respuesta del modo parcial: se queda en pantalla hasta la siguiente foto. */
    val respuestaParcial = MutableStateFlow<RespuestaParcial?>(null)

    /** Porcentaje de descarga del modelo de voz; null si no se está descargando. */
    val descargaModelo = MutableStateFlow<Int?>(null)

    /** Mensajes cortos para mostrar una vez (snackbar). */
    val avisos = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun avisar(texto: String) {
        avisos.tryEmit(texto)
    }
}
