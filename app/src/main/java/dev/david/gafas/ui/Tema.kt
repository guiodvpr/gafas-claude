package dev.david.gafas.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.david.gafas.nucleo.Fase

/** La identidad visual: fondos casi negros, un coral cálido como acento y un color por cada estado. */
object Paleta {
    val Coral = Color(0xFFE8825C)
    val CoralSuave = Color(0xFFF2B299)
    val Violeta = Color(0xFF9B8CFF)
    val Menta = Color(0xFF5ED6B0)
    val Ambar = Color(0xFFF2C14E)
    val Rojo = Color(0xFFFF6B6B)
    val Gris = Color(0xFF6B6B76)

    /** El color del orbe y de los acentos según lo que esté haciendo el asistente. */
    fun de(fase: Fase): Color = when (fase) {
        Fase.APAGADO -> Gris
        Fase.PREPARANDO -> Ambar
        Fase.ESPERANDO -> CoralSuave
        Fase.ESCUCHANDO -> Coral
        Fase.PENSANDO -> Violeta
        Fase.ACTUANDO -> Ambar
        Fase.HABLANDO -> Menta
        Fase.ERROR -> Rojo
    }
}

private val Oscuro = darkColorScheme(
    primary = Paleta.Coral,
    onPrimary = Color(0xFF2A1208),
    primaryContainer = Color(0xFF4A2414),
    onPrimaryContainer = Color(0xFFFFDBCD),
    secondary = Paleta.Violeta,
    onSecondary = Color(0xFF16123A),
    tertiary = Paleta.Menta,
    background = Color(0xFF0D0D10),
    onBackground = Color(0xFFEDEDF0),
    surface = Color(0xFF0D0D10),
    onSurface = Color(0xFFEDEDF0),
    surfaceVariant = Color(0xFF1B1B21),
    onSurfaceVariant = Color(0xFFA3A3AF),
    surfaceContainer = Color(0xFF16161B),
    surfaceContainerHigh = Color(0xFF1E1E25),
    surfaceContainerHighest = Color(0xFF27272F),
    outline = Color(0xFF3A3A44),
    outlineVariant = Color(0xFF26262E),
    error = Paleta.Rojo,
    onError = Color(0xFF2A0A0A),
)

private val Claro = lightColorScheme(
    primary = Color(0xFFC25A34),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCD),
    onPrimaryContainer = Color(0xFF3A1505),
    secondary = Color(0xFF5B4FCF),
    onSecondary = Color.White,
    tertiary = Color(0xFF1F8F6E),
    background = Color(0xFFFAF7F4),
    onBackground = Color(0xFF1B1A19),
    surface = Color(0xFFFAF7F4),
    onSurface = Color(0xFF1B1A19),
    surfaceVariant = Color(0xFFEFEAE5),
    onSurfaceVariant = Color(0xFF66615C),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF3EEE9),
    surfaceContainerHighest = Color(0xFFE9E3DD),
    outline = Color(0xFFCFC8C1),
    outlineVariant = Color(0xFFE4DED8),
    error = Color(0xFFC0392B),
    onError = Color.White,
)

@Composable
fun Tema(contenido: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Oscuro else Claro, content = contenido)
}
