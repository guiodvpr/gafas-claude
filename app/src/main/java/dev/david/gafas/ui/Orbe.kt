package dev.david.gafas.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.david.gafas.nucleo.Fase
import kotlin.math.PI
import kotlin.math.sin

/**
 * EL ORBE. La cara del asistente: de un vistazo sabes en qué anda.
 *
 *  - Esperando:  respira despacio.
 *  - Escuchando: crece y se encoge con el volumen de tu voz.
 *  - Pensando:   un arco gira a su alrededor.
 *  - Hablando:   late con un ritmo de voz.
 *
 * Todo se dibuja a mano en un Canvas (círculos con degradado), sin imágenes: por eso
 * se ve nítido en cualquier pantalla y las transiciones de color son continuas.
 */
@Composable
fun Orbe(fase: Fase, nivel: Float, modifier: Modifier = Modifier) {
    val color by animateColorAsState(Paleta.de(fase), tween(500), label = "color")
    val nivelSuave by animateFloatAsState(if (fase == Fase.ESCUCHANDO) nivel else 0f, tween(120), label = "nivel")
    val energia by animateFloatAsState(
        when (fase) {
            Fase.APAGADO, Fase.ERROR -> 0f
            Fase.ESPERANDO -> 0.35f
            else -> 1f
        },
        tween(600), label = "energia",
    )

    val ciclo = rememberInfiniteTransition(label = "orbe")
    val respiracion by ciclo.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart), label = "respiracion")
    val giro by ciclo.animateFloat(0f, 360f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart), label = "giro")
    val habla by ciclo.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "habla")

    Canvas(modifier) {
        val centro = Offset(size.width / 2f, size.height / 2f)
        val base = size.minDimension * 0.27f
        val onda = sin(respiracion * 2f * PI.toFloat())

        // Cuánto se infla el núcleo según el estado
        val pulso = when (fase) {
            Fase.ESCUCHANDO -> 0.06f + nivelSuave * 0.45f
            Fase.HABLANDO -> 0.10f + 0.07f * sin(habla * 2f * PI.toFloat()) + 0.05f * sin(habla * 6f * PI.toFloat())
            Fase.PENSANDO, Fase.ACTUANDO, Fase.PREPARANDO -> 0.04f + 0.02f * onda
            else -> 0.03f * onda * energia
        }
        val radio = base * (1f + pulso)

        // Halo: tres capas de luz difusa que le dan profundidad
        for (capa in 3 downTo 1) {
            val radioHalo = radio * (1f + capa * 0.33f + 0.04f * onda)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.16f * energia.coerceAtLeast(0.25f) / capa), Color.Transparent),
                    center = centro,
                    radius = radioHalo,
                ),
                radius = radioHalo,
                center = centro,
            )
        }

        // Núcleo: degradado descentrado para que parezca una esfera iluminada desde arriba
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.92f), color, color.copy(alpha = 0.55f)),
                center = Offset(centro.x - radio * 0.28f, centro.y - radio * 0.32f),
                radius = radio * 1.35f,
            ),
            radius = radio,
            center = centro,
        )
        drawCircle(color = Color.White.copy(alpha = 0.10f), radius = radio, center = centro, style = Stroke(width = radio * 0.02f))

        // Anillo exterior: quieto y tenue en reposo; un arco que gira cuando está trabajando
        val radioAnillo = radio * 1.42f
        val grosor = base * 0.035f
        val esquina = Offset(centro.x - radioAnillo, centro.y - radioAnillo)
        val caja = Size(radioAnillo * 2f, radioAnillo * 2f)
        drawCircle(color = color.copy(alpha = 0.14f), radius = radioAnillo, center = centro, style = Stroke(width = grosor))
        if (fase == Fase.PENSANDO || fase == Fase.ACTUANDO || fase == Fase.PREPARANDO) {
            drawArc(color = color, startAngle = giro, sweepAngle = 80f, useCenter = false, topLeft = esquina, size = caja, style = Stroke(width = grosor, cap = StrokeCap.Round))
            drawArc(color = color.copy(alpha = 0.5f), startAngle = giro + 180f, sweepAngle = 40f, useCenter = false, topLeft = esquina, size = caja, style = Stroke(width = grosor, cap = StrokeCap.Round))
        }
        if (fase == Fase.ESCUCHANDO) {
            // Una onda que se expande con tu voz
            val expansion = radioAnillo * (1f + nivelSuave * 0.35f)
            drawCircle(color = color.copy(alpha = 0.35f * (0.3f + nivelSuave)), radius = expansion, center = centro, style = Stroke(width = grosor))
        }
    }
}
