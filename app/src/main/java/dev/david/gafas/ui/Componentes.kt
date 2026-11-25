package dev.david.gafas.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ───────────────────────── Piezas reutilizables ─────────────────────────
// Toda la app se arma con estas pocas piezas; por eso se ve consistente.

val FormaTarjeta = RoundedCornerShape(22.dp)
val FormaCampo = RoundedCornerShape(16.dp)

/** El contenedor base: fondo ligeramente elevado y un borde finísimo. */
@Composable
fun Tarjeta(modifier: Modifier = Modifier, alTocar: (() -> Unit)? = null, contenido: @Composable ColumnScope.() -> Unit) {
    val colores = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(FormaTarjeta)
            .background(colores.surfaceContainer)
            .border(1.dp, colores.outlineVariant, FormaTarjeta)
            .then(if (alTocar != null) Modifier.clickable(onClick = alTocar) else Modifier)
            .padding(18.dp),
        content = contenido,
    )
}

/** Título de sección en mayúsculas pequeñas. */
@Composable
fun TituloSeccion(texto: String, modifier: Modifier = Modifier) {
    Text(
        text = texto.uppercase(),
        modifier = modifier.padding(start = 6.dp, top = 10.dp, bottom = 2.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

/** Etiqueta redondeada con un punto de color: "● Gafas", "● 82 %". */
@Composable
fun Pildora(texto: String, color: Color, modifier: Modifier = Modifier, alTocar: (() -> Unit)? = null) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.13f))
            .then(if (alTocar != null) Modifier.clickable(onClick = alTocar) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(texto, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Botón principal: relleno, alto y con esquinas muy redondas. */
@Composable
fun BotonPrincipal(texto: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, activo: Boolean = true, alTocar: () -> Unit) {
    val fondo = if (activo) color else MaterialTheme.colorScheme.surfaceContainerHighest
    val tinta = if (activo) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .height(54.dp)
            .clip(CircleShape)
            .background(fondo)
            .clickable(enabled = activo, onClick = alTocar)
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(texto, color = tinta, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Botón secundario: solo borde. */
@Composable
fun BotonSuave(texto: String, modifier: Modifier = Modifier, activo: Boolean = true, alTocar: () -> Unit) {
    val colores = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(CircleShape)
            .border(1.dp, colores.outline, CircleShape)
            .clickable(enabled = activo, onClick = alTocar)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            texto,
            color = if (activo) colores.onSurface else colores.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

/** Una fila con título, explicación y un interruptor a la derecha. */
@Composable
fun FilaInterruptor(titulo: String, detalle: String, activo: Boolean, modifier: Modifier = Modifier, alCambiar: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(titulo, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            if (detalle.isNotEmpty()) {
                Text(detalle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = activo, onCheckedChange = alCambiar)
    }
}

/** Selector de una opción entre pocas, tipo "pastilla" (Rápido | Equilibrado | Profundo). */
@Composable
fun <T> Selector(opciones: List<Pair<T, String>>, elegida: T, modifier: Modifier = Modifier, alElegir: (T) -> Unit) {
    val colores = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(colores.surfaceContainerHighest)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        opciones.forEach { (valor, etiqueta) ->
            val marcada = valor == elegida
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(CircleShape)
                    .background(if (marcada) colores.primary else Color.Transparent)
                    .clickable { alElegir(valor) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    etiqueta,
                    color = if (marcada) colores.onPrimary else colores.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (marcada) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Campo de texto con el estilo de la app. */
@Composable
fun Campo(
    valor: String,
    etiqueta: String,
    modifier: Modifier = Modifier,
    ayuda: String = "",
    secreto: Boolean = false,
    unaLinea: Boolean = true,
    alCambiar: (String) -> Unit,
) {
    OutlinedTextField(
        value = valor,
        onValueChange = alCambiar,
        modifier = modifier.fillMaxWidth(),
        label = { Text(etiqueta) },
        supportingText = if (ayuda.isEmpty()) null else ({ Text(ayuda) }),
        visualTransformation = if (secreto) PasswordVisualTransformation() else VisualTransformation.None,
        singleLine = unaLinea,
        minLines = if (unaLinea) 1 else 3,
        shape = FormaCampo,
    )
}

// ───────────────────────── Íconos ─────────────────────────
// Dibujados a mano con líneas: no dependen de ninguna librería de íconos.

enum class Glifo { ORBE, CHAT, CAPACIDADES, AJUSTES, ENVIAR, PARAR, MICROFONO, CHECK, ALERTA, CERRAR, MAS }

@Composable
fun Icono(glifo: Glifo, color: Color, modifier: Modifier = Modifier, lado: Dp = 24.dp) {
    Canvas(modifier.size(lado)) {
        val u = size.minDimension / 24f // el dibujo está pensado en una cuadrícula de 24 × 24
        val trazo = Stroke(width = 1.9f * u, cap = StrokeCap.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun linea(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, p(x1, y1), p(x2, y2), strokeWidth = 1.9f * u, cap = StrokeCap.Round)

        when (glifo) {
            Glifo.ORBE -> {
                drawCircle(color, radius = 4.2f * u, center = p(12f, 12f))
                drawCircle(color.copy(alpha = 0.55f), radius = 8.6f * u, center = p(12f, 12f), style = trazo)
            }
            Glifo.CHAT -> {
                drawRoundRect(color, topLeft = p(3.5f, 4.5f), size = Size(17f * u, 12.5f * u), cornerRadius = CornerRadius(4f * u), style = trazo)
                linea(8f, 17f, 6.5f, 20.5f)
                linea(6.5f, 20.5f, 11.5f, 17f)
                linea(8f, 9.2f, 16f, 9.2f)
                linea(8f, 12.6f, 13.5f, 12.6f)
            }
            Glifo.CAPACIDADES -> {
                cuadrito(color, trazo, u, 4f, 4f)
                cuadrito(color, trazo, u, 13.5f, 4f)
                cuadrito(color, trazo, u, 4f, 13.5f)
                drawCircle(color, radius = 3.3f * u, center = p(16.8f, 16.8f))
            }
            Glifo.AJUSTES -> {
                linea(4f, 7f, 20f, 7f)
                linea(4f, 12f, 20f, 12f)
                linea(4f, 17f, 20f, 17f)
                perilla(color, u, 9f, 7f)
                perilla(color, u, 15.5f, 12f)
                perilla(color, u, 7.5f, 17f)
            }
            Glifo.ENVIAR -> {
                linea(12f, 19f, 12f, 5.5f)
                linea(12f, 5.5f, 6.5f, 11f)
                linea(12f, 5.5f, 17.5f, 11f)
            }
            Glifo.PARAR -> drawRoundRect(color, topLeft = p(7f, 7f), size = Size(10f * u, 10f * u), cornerRadius = CornerRadius(2.2f * u))
            Glifo.MICROFONO -> {
                drawRoundRect(color, topLeft = p(9f, 3.5f), size = Size(6f * u, 10.5f * u), cornerRadius = CornerRadius(3f * u))
                drawArc(color, startAngle = 0f, sweepAngle = 180f, useCenter = false, topLeft = p(5.8f, 4.6f), size = Size(12.4f * u, 12.4f * u), style = trazo)
                linea(12f, 17.2f, 12f, 20.5f)
                linea(8.8f, 20.5f, 15.2f, 20.5f)
            }
            Glifo.CHECK -> {
                linea(5.5f, 12.5f, 10f, 17f)
                linea(10f, 17f, 18.5f, 7.5f)
            }
            Glifo.ALERTA -> {
                drawCircle(color, radius = 8.6f * u, center = p(12f, 12f), style = trazo)
                linea(12f, 7.5f, 12f, 12.8f)
                drawCircle(color, radius = 1.15f * u, center = p(12f, 16.2f))
            }
            Glifo.CERRAR -> {
                linea(6.5f, 6.5f, 17.5f, 17.5f)
                linea(17.5f, 6.5f, 6.5f, 17.5f)
            }
            Glifo.MAS -> {
                linea(12f, 5.5f, 12f, 18.5f)
                linea(5.5f, 12f, 18.5f, 12f)
            }
        }
    }
}

private fun DrawScope.cuadrito(color: Color, trazo: Stroke, u: Float, x: Float, y: Float) {
    drawRoundRect(color, topLeft = Offset(x * u, y * u), size = Size(6.5f * u, 6.5f * u), cornerRadius = CornerRadius(1.8f * u), style = trazo)
}

private fun DrawScope.perilla(color: Color, u: Float, x: Float, y: Float) {
    drawCircle(color, radius = 2.4f * u, center = Offset(x * u, y * u))
}
