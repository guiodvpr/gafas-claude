package dev.david.gafas.nucleo

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Fechas que van y vienen entre Claude (texto ISO 8601) y Android (milisegundos). */
object Tiempo {
    private val SOLO_FECHA = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val ES = Locale.forLanguageTag("es-CO")
    private val DIA_Y_HORA = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM, h:mm a", ES)
    private val SOLO_DIA = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ES)
    private val SOLO_HORA = DateTimeFormatter.ofPattern("h:mm a", ES)

    fun esSoloFecha(texto: String): Boolean = SOLO_FECHA.matches(texto.trim())

    /**
     * Entiende lo que Claude suele mandar: "2026-11-17T15:00", "2026-11-17 15:00:00",
     * "2026-11-17T15:00:00-05:00" o solo "2026-11-17". Sin zona = hora local del celular.
     */
    fun interpretar(texto: String, zona: ZoneId = ZoneId.systemDefault()): ZonedDateTime? {
        val limpio = texto.trim().replace(' ', 'T')
        if (limpio.isEmpty()) return null
        runCatching { return OffsetDateTime.parse(limpio).atZoneSameInstant(zona) }
        runCatching { return ZonedDateTime.parse(limpio).withZoneSameInstant(zona) }
        runCatching { return LocalDateTime.parse(limpio).atZone(zona) }
        runCatching { return LocalDate.parse(limpio).atStartOfDay(zona) }
        return null
    }

    fun decir(momento: ZonedDateTime): String = momento.format(DIA_Y_HORA)

    fun decirDia(momento: ZonedDateTime): String = momento.format(SOLO_DIA)

    fun decirHora(momento: ZonedDateTime): String = momento.format(SOLO_HORA)

    fun aIso(momento: ZonedDateTime): String = momento.toLocalDateTime().withNano(0).toString()
}
