package dev.david.gafas.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.david.gafas.datos.Historial
import dev.david.gafas.datos.Mensaje
import dev.david.gafas.datos.Rol
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val HORA = DateTimeFormatter.ofPattern("h:mm a")

/** CHAT. Todo lo hablado, con las fotos que tomaron las gafas y lo que Claude hizo en cada respuesta. */
@Composable
fun PantallaChat(modifier: Modifier = Modifier) {
    val mensajes by Historial.mensajes.collectAsState()
    var confirmarBorrado by remember { mutableStateOf(false) }
    val colores = MaterialTheme.colorScheme

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Conversación", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = colores.onSurface)
            if (mensajes.isNotEmpty()) TextButton(onClick = { confirmarBorrado = true }) { Text("Borrar") }
        }
        if (mensajes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Aquí va quedando lo que hables con Claude.\nDi la frase de activación o toca el orbe para empezar.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colores.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            // reverseLayout: lo más nuevo queda abajo y la lista arranca ahí, como en cualquier chat
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(mensajes.asReversed(), key = { it.id }) { Burbuja(it) }
            }
        }
    }

    if (confirmarBorrado) {
        AlertDialog(
            onDismissRequest = { confirmarBorrado = false },
            confirmButton = {
                TextButton(onClick = {
                    Historial.borrarTodo()
                    confirmarBorrado = false
                }) { Text("Borrar todo") }
            },
            dismissButton = { TextButton(onClick = { confirmarBorrado = false }) { Text("Cancelar") } },
            title = { Text("¿Borrar la conversación?") },
            text = { Text("Se borra de este celular. La memoria y las notas no se tocan.") },
        )
    }
}

@Composable
private fun Burbuja(mensaje: Mensaje) {
    val colores = MaterialTheme.colorScheme
    val esMio = mensaje.rol == Rol.USUARIO
    val fondo = when (mensaje.rol) {
        Rol.USUARIO -> colores.primaryContainer
        Rol.CLAUDE -> colores.surfaceContainerHigh
        Rol.SISTEMA -> Paleta.Rojo.copy(alpha = 0.14f)
    }
    val tinta = if (esMio) colores.onPrimaryContainer else colores.onSurface
    // La esquina "de la cola" va menos redonda, del lado de quien habla
    val forma = RoundedCornerShape(
        topStart = 20.dp, topEnd = 20.dp,
        bottomStart = if (esMio) 20.dp else 6.dp,
        bottomEnd = if (esMio) 6.dp else 20.dp,
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (esMio) Alignment.End else Alignment.Start) {
        Column(Modifier.widthIn(max = 320.dp).clip(forma).background(fondo).padding(horizontal = 14.dp, vertical = 10.dp)) {
            mensaje.foto?.let { ruta ->
                val foto by produceState<ImageBitmap?>(initialValue = null, ruta) {
                    value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(ruta)?.asImageBitmap() }.getOrNull() }
                }
                foto?.let {
                    Image(
                        bitmap = it,
                        contentDescription = "Foto tomada por las gafas",
                        modifier = Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            Text(mensaje.texto, style = MaterialTheme.typography.bodyLarge, color = tinta)
            if (mensaje.acciones.isNotEmpty()) {
                Text(
                    mensaje.acciones.joinToString(" · "),
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = colores.secondary,
                )
            }
        }
        Text(
            Instant.ofEpochMilli(mensaje.hora).atZone(ZoneId.systemDefault()).format(HORA),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = colores.onSurfaceVariant,
        )
    }
}
