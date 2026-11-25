package dev.david.gafas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.david.gafas.nucleo.Entrada
import dev.david.gafas.nucleo.Estado
import dev.david.gafas.nucleo.Fase
import dev.david.gafas.nucleo.RespuestaParcial
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Un paso que falta para que todo funcione, con el botón que lo resuelve. */
class Pendiente(val titulo: String, val detalle: String, val boton: String, val accion: () -> Unit)

/** Lo que las pantallas le pueden pedir a la actividad. */
class Acciones(
    val iniciar: () -> Unit,
    val detener: () -> Unit,
    val hablar: () -> Unit,
    val callar: () -> Unit,
    val preguntar: (String) -> Unit,
    val pedirPermisos: (List<String>) -> Unit,
    val conectarMeta: () -> Unit,
    val permitirCamara: () -> Unit,
    val irA: (Pestana) -> Unit,
    val cambiarParcial: (Boolean) -> Unit,
)

enum class Pestana(val titulo: String, val glifo: Glifo) {
    INICIO("Inicio", Glifo.ORBE),
    CHAT("Chat", Glifo.CHAT),
    CAPACIDADES("Capacidades", Glifo.CAPACIDADES),
    AJUSTES("Ajustes", Glifo.AJUSTES),
}

/**
 * INICIO. Una sola idea en pantalla: el orbe. Lo tocas para hablar, lo vuelves a tocar
 * para interrumpir. Debajo, lo que se está oyendo o diciendo en este momento.
 */
@Composable
fun PantallaInicio(acciones: Acciones, pendientes: List<Pendiente>, modoParcial: Boolean, modifier: Modifier = Modifier) {
    val fase by Estado.fase.collectAsState()
    val detalle by Estado.detalle.collectAsState()
    val parcial by Estado.parcial.collectAsState()
    val respuesta by Estado.respuestaViva.collectAsState()
    val nivel by Estado.nivel.collectAsState()
    val entrada by Estado.entrada.collectAsState()
    val gafas by Estado.gafas.collectAsState()
    val descarga by Estado.descargaModelo.collectAsState()
    val respuestaParcial by Estado.respuestaParcial.collectAsState()
    val colores = MaterialTheme.colorScheme

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Encabezado: nombre y estado de las conexiones
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Gafas + Claude", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = colores.onSurface)
                Text(
                    when (entrada) {
                        Entrada.GAFAS -> "Escuchando por las gafas"
                        Entrada.TELEFONO -> "Escuchando por el celular"
                        Entrada.NINGUNA -> if (fase.encendido) "Micrófono en reposo" else "Asistente apagado"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colores.onSurfaceVariant,
                )
            }
            gafas.bateria?.let {
                Pildora("$it %", if (it > 20) Paleta.Menta else Paleta.Rojo)
                Spacer(Modifier.width(8.dp))
            }
            // El interruptor del modo parcial vive aquí, a un toque, porque se prende y apaga seguido
            Pildora(
                texto = if (modoParcial) "Parcial" else "Parcial: no",
                color = if (modoParcial) Paleta.Coral else Paleta.Gris,
                alTocar = { acciones.cambiarParcial(!modoParcial) },
            )
        }

        // El orbe ocupa todo el espacio libre y es el botón principal
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    when {
                        !fase.encendido -> acciones.iniciar()
                        // En modo parcial, tocar mientras lee la respuesta = siguiente pregunta.
                        // Mientras toma la foto o piensa, el toque se ignora para no repetir sin querer.
                        modoParcial -> if (fase == Fase.ESPERANDO || fase == Fase.HABLANDO) acciones.hablar()
                        fase.enTurno -> acciones.callar()
                        fase != Fase.PREPARANDO -> acciones.hablar()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Orbe(fase, nivel, Modifier.fillMaxSize())
        }

        Text(fase.etiqueta, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = Paleta.de(fase))
        Spacer(Modifier.height(6.dp))
        val ultima = respuestaParcial
        if (modoParcial && fase.encendido && ultima != null) {
            TarjetaParcial(ultima)
        } else {
            LineaDeContexto(fase, respuesta, parcial, detalle)
        }
        descarga?.let { porcentaje ->
            LinearProgressIndicator(progress = { porcentaje / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        }

        // Puesta a punto: aparece solo mientras falte algo, de a un paso por vez
        pendientes.firstOrNull()?.let { paso ->
            Tarjeta(Modifier.padding(top = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icono(Glifo.ALERTA, Paleta.Ambar)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(paso.titulo, style = MaterialTheme.typography.titleSmall, color = colores.onSurface)
                        Text(paso.detalle, style = MaterialTheme.typography.bodySmall, color = colores.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    BotonSuave(paso.boton, alTocar = paso.accion)
                }
                if (pendientes.size > 1) {
                    Text(
                        "Faltan ${pendientes.size} pasos para dejar todo listo",
                        modifier = Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = colores.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        BarraDeEntrada(fase, acciones)
        Spacer(Modifier.height(12.dp))
    }
}

/** Abajo: escribirle a Claude (cuando no puedes hablar) y el botón de encender o apagar. */
@Composable
private fun BarraDeEntrada(fase: Fase, acciones: Acciones) {
    val colores = MaterialTheme.colorScheme
    var borrador by remember { mutableStateOf("") }

    if (!fase.encendido) {
        BotonPrincipal("Iniciar asistente", Modifier.fillMaxWidth(), alTocar = acciones.iniciar)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(54.dp)
                .clip(CircleShape)
                .background(colores.surfaceContainerHigh)
                .padding(start = 20.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (borrador.isEmpty()) {
                    Text("Escríbele a Claude…", color = colores.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                }
                BasicTextField(
                    value = borrador,
                    onValueChange = { borrador = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colores.onSurface),
                    singleLine = true,
                    cursorBrush = SolidColor(colores.primary),
                )
            }
            val hayTexto = borrador.isNotBlank()
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (hayTexto) colores.primary else colores.surfaceContainerHighest)
                    .clickable(enabled = hayTexto && fase != Fase.PREPARANDO) {
                        acciones.preguntar(borrador.trim())
                        borrador = ""
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icono(Glifo.ENVIAR, if (hayTexto) colores.onPrimary else colores.onSurfaceVariant, lado = 22.dp)
            }
        }
        // Apagar: pequeño y a un lado, para no tocarlo sin querer
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(colores.surfaceContainerHigh)
                .clickable(onClick = acciones.detener),
            contentAlignment = Alignment.Center,
        ) {
            Icono(Glifo.PARAR, Paleta.Rojo)
        }
    }
}

/** Una sola línea de contexto bajo el orbe: lo que dice Claude, lo que dices tú, o una pista. */
@Composable
private fun LineaDeContexto(fase: Fase, respuesta: String, parcial: String, detalle: String) {
    val colores = MaterialTheme.colorScheme
    val (texto, tinta) = when {
        respuesta.isNotBlank() && fase.enTurno -> respuesta.takeLast(260) to colores.onSurface
        parcial.isNotBlank() && (fase == Fase.ESCUCHANDO || fase.enTurno) -> "“$parcial”" to colores.onSurface
        fase == Fase.APAGADO -> "Toca el orbe para empezar" to colores.onSurfaceVariant
        else -> detalle to colores.onSurfaceVariant
    }
    Text(
        texto,
        modifier = Modifier.fillMaxWidth().height(72.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = tinta,
        textAlign = TextAlign.Center,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * La respuesta del modo parcial, grande y legible de un vistazo, con la miniatura de la foto
 * para confirmar que leyó la pregunta correcta. Se queda en pantalla hasta la siguiente foto.
 */
@Composable
private fun TarjetaParcial(respuesta: RespuestaParcial) {
    val colores = MaterialTheme.colorScheme
    Tarjeta(Modifier.padding(bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            respuesta.foto?.let { ruta ->
                val foto by produceState<ImageBitmap?>(initialValue = null, ruta) {
                    value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(ruta)?.asImageBitmap() }.getOrNull() }
                }
                foto?.let {
                    Image(
                        bitmap = it,
                        contentDescription = "Foto de la pregunta",
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(14.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    respuesta.texto.ifBlank { "…" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colores.onSurface,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                if (respuesta.lista && respuesta.segundos > 0f) {
                    Text(
                        "en ${"%.1f".format(respuesta.segundos)} s",
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = colores.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
