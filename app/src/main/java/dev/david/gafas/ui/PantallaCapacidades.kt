package dev.david.gafas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.david.gafas.herramientas.Catalogo
import dev.david.gafas.ia.Agente
import dev.david.gafas.nucleo.Ajustes
import dev.david.gafas.nucleo.Estado
import dev.david.gafas.nucleo.ServidorMcp

/**
 * CAPACIDADES. Lo que Claude puede hacer desde las gafas. Cada tarjeta es un interruptor;
 * si a una le falta un permiso de Android, ella misma ofrece el botón para darlo.
 *
 * [refresco] cambia cada vez que vuelves a la app (por ejemplo, después de dar un permiso)
 * y obliga a recalcular qué falta.
 */
@Composable
fun PantallaCapacidades(catalogo: Catalogo, ajustes: Ajustes, acciones: Acciones, refresco: Int, modifier: Modifier = Modifier) {
    val version by ajustes.version.collectAsState()
    val gafas by Estado.gafas.collectAsState()
    val colores = MaterialTheme.colorScheme
    var agregandoMcp by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Capacidades",
            Modifier.padding(start = 4.dp, top = 14.dp),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = colores.onSurface,
        )
        Text(
            "Claude decide solo cuándo usar cada una. Apaga las que no quieras.",
            Modifier.padding(start = 4.dp, bottom = 4.dp),
            style = MaterialTheme.typography.bodyMedium, color = colores.onSurfaceVariant,
        )

        TituloSeccion("En tus gafas y tu celular")
        catalogo.capacidades.forEach { capacidad ->
            // version y refresco se leen aquí para que la tarjeta se redibuje cuando cambian
            val activa = remember(version, capacidad.id) { catalogo.activa(capacidad) }
            val faltantes = remember(refresco, version, capacidad.id) { catalogo.permisosFaltantes(capacidad) }
            Tarjeta {
                FilaInterruptor(capacidad.titulo, capacidad.descripcion, activa) { ajustes.activarCapacidad(capacidad.id, it) }
                if (activa && faltantes.isNotEmpty()) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Falta un permiso de Android", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Paleta.Ambar)
                        BotonSuave("Dar permiso") { acciones.pedirPermisos(faltantes) }
                    }
                }
                if (capacidad.id == "camara" && activa) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Enlace con Meta AI: ${gafas.registro}", style = MaterialTheme.typography.bodySmall, color = colores.onSurfaceVariant)
                            if (gafas.registrada) {
                                Text(
                                    if (gafas.camaraPermitida) "Cámara permitida" else "Falta permitir la cámara",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (gafas.camaraPermitida) Paleta.Menta else Paleta.Ambar,
                                )
                            }
                        }
                        when {
                            !gafas.registrada -> BotonSuave("Conectar", alTocar = acciones.conectarMeta)
                            !gafas.camaraPermitida -> BotonSuave("Permitir", alTocar = acciones.permitirCamara)
                        }
                    }
                }
            }
        }

        TituloSeccion("En internet")
        Tarjeta {
            FilaInterruptor("Buscar en la web", "Noticias, precios, clima, datos al día. Se activa en la consola de Anthropic.", remember(version) { ajustes.busquedaWeb }) { ajustes.busquedaWeb = it }
            FilaInterruptor("Leer páginas", "Abre un enlace y lo resume o responde sobre él.", remember(version) { ajustes.leerWeb }) { ajustes.leerWeb = it }
        }

        TituloSeccion("Conectores (MCP)")
        Tarjeta {
            Text(
                "La puerta para sumar cualquier otra app: Notion, Gmail, tu propio servidor… Pega la dirección de un servidor MCP remoto y Claude podrá usar sus herramientas.",
                style = MaterialTheme.typography.bodySmall, color = colores.onSurfaceVariant,
            )
            val servidores = remember(version) { ajustes.servidoresMcp }
            servidores.forEach { servidor ->
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(servidor.nombre, style = MaterialTheme.typography.titleSmall, color = colores.onSurface)
                        Text(servidor.url, style = MaterialTheme.typography.bodySmall, color = colores.onSurfaceVariant, maxLines = 1)
                    }
                    TextButton(onClick = { ajustes.servidoresMcp = servidores - servidor }) { Text("Quitar") }
                }
            }
            Spacer(Modifier.height(10.dp))
            BotonSuave("Agregar conector") { agregandoMcp = true }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (agregandoMcp) {
        DialogoMcp(
            existentes = ajustes.servidoresMcp.map { Agente.nombreMcp(it.nombre) },
            alCerrar = { agregandoMcp = false },
            alGuardar = {
                ajustes.servidoresMcp = ajustes.servidoresMcp + it
                agregandoMcp = false
            },
        )
    }
}

@Composable
private fun DialogoMcp(existentes: List<String>, alCerrar: () -> Unit, alGuardar: (ServidorMcp) -> Unit) {
    var nombre by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    var token by remember { mutableStateOf("") }
    val repetido = Agente.nombreMcp(nombre) in existentes
    val valido = nombre.isNotBlank() && url.startsWith("https://") && url.length > 12 && !repetido

    AlertDialog(
        onDismissRequest = alCerrar,
        confirmButton = {
            TextButton(enabled = valido, onClick = { alGuardar(ServidorMcp(nombre.trim(), url.trim(), token.trim())) }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = alCerrar) { Text("Cancelar") } },
        title = { Text("Nuevo conector") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Campo(nombre, "Nombre", ayuda = if (repetido) "Ya hay uno con ese nombre" else "") { nombre = it }
                Campo(url, "Dirección (https://…)") { url = it }
                Campo(token, "Token de acceso (si lo pide)", secreto = true) { token = it }
            }
        },
    )
}
