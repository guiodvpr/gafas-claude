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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.david.gafas.datos.Memoria
import dev.david.gafas.datos.Recordatorios
import dev.david.gafas.nucleo.Ajustes
import dev.david.gafas.nucleo.Diario
import dev.david.gafas.nucleo.Estado
import dev.david.gafas.nucleo.ModoEscucha
import dev.david.gafas.nucleo.MotorTranscripcion
import dev.david.gafas.nucleo.Tiempo
import java.time.Instant
import java.time.ZoneId

/** AJUSTES. Cada cambio se guarda al instante; los de voz y escucha aplican al reiniciar el asistente. */
@Composable
fun PantallaAjustes(ajustes: Ajustes, modifier: Modifier = Modifier) {
    val colores = MaterialTheme.colorScheme
    val fase by Estado.fase.collectAsState()

    // Copias locales para que escribir sea fluido; cada cambio se guarda de inmediato
    var apiKey by remember { mutableStateOf(ajustes.apiKey) }
    var modelo by remember { mutableStateOf(ajustes.modelo) }
    var esfuerzo by remember { mutableStateOf(ajustes.esfuerzo) }
    var frases by remember { mutableStateOf(ajustes.frases) }
    var modo by remember { mutableStateOf(ajustes.modoEscucha) }
    var seguir by remember { mutableStateOf(ajustes.seguirConversacion) }
    var interrumpible by remember { mutableStateOf(ajustes.interrumpible) }
    var sonidos by remember { mutableStateOf(ajustes.sonidos) }
    var vistaRapida by remember { mutableStateOf(ajustes.vistaRapida) }
    var modoParcial by remember { mutableStateOf(ajustes.modoParcial) }
    var parcialPreciso by remember { mutableStateOf(ajustes.parcialPreciso) }
    var velocidad by remember { mutableStateOf(ajustes.velocidadVoz) }
    var transcripcion by remember { mutableStateOf(ajustes.transcripcion) }
    var sttKey by remember { mutableStateOf(ajustes.sttKey) }
    var sttUrl by remember { mutableStateOf(ajustes.sttUrl) }
    var sttModelo by remember { mutableStateOf(ajustes.sttModelo) }
    var nombre by remember { mutableStateOf(ajustes.nombre) }
    var instrucciones by remember { mutableStateOf(ajustes.instruccionesExtra) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Ajustes",
            Modifier.padding(start = 4.dp, top = 14.dp),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = colores.onSurface,
        )
        if (fase.encendido) {
            Text(
                "Los cambios de escucha y voz aplican la próxima vez que inicies el asistente.",
                Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = colores.onSurfaceVariant,
            )
        }

        TituloSeccion("Claude")
        Tarjeta {
            Campo(apiKey, "Llave de la API de Claude", ayuda = "Se crea en console.anthropic.com y se guarda cifrada en este celular.", secreto = true) {
                apiKey = it
                ajustes.apiKey = it
            }
            Spacer(Modifier.height(10.dp))
            Text("Modelo", style = MaterialTheme.typography.titleSmall, color = colores.onSurface)
            Spacer(Modifier.height(6.dp))
            Selector(
                listOf(Ajustes.MODELO_RAPIDO to "Rápido", Ajustes.MODELO_EQUILIBRADO to "Equilibrado", Ajustes.MODELO_PROFUNDO to "Profundo"),
                modelo,
            ) {
                modelo = it
                ajustes.modelo = it
            }
            Text(
                when (modelo) {
                    Ajustes.MODELO_RAPIDO -> "Haiku 5.5: responde casi al instante. El mejor para hablar."
                    Ajustes.MODELO_EQUILIBRADO -> "Sonnet 5.5: más criterio, un poco más de espera."
                    Ajustes.MODELO_PROFUNDO -> "Opus 5.5: el más capaz y el más lento; siempre piensa antes de hablar."
                    else -> "Modelo personalizado."
                },
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = colores.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Campo(modelo, "ID del modelo", ayuda = "Para probar otro modelo, escribe aquí su ID.") {
                modelo = it
                ajustes.modelo = it
            }
            Spacer(Modifier.height(6.dp))
            Text("Cuánto lo piensa", style = MaterialTheme.typography.titleSmall, color = colores.onSurface)
            Spacer(Modifier.height(6.dp))
            Selector(listOf("low" to "Ágil", "medium" to "Medio", "high" to "A fondo"), esfuerzo) {
                esfuerzo = it
                ajustes.esfuerzo = it
            }
        }

        TituloSeccion("Modo parcial")
        Tarjeta {
            FilaInterruptor(
                "Modo parcial",
                "Cada foto trae solo la respuesta, sin explicaciones. También se prende desde Inicio.",
                modoParcial,
            ) {
                modoParcial = it
                ajustes.modoParcial = it
            }
            Spacer(Modifier.height(8.dp))
            Selector(listOf(false to "Rápido", true to "Preciso"), parcialPreciso) {
                parcialPreciso = it
                ajustes.parcialPreciso = it
            }
            Text(
                if (parcialPreciso) {
                    "Sonnet 5.5: piensa un poco más en cálculos y preguntas con trampa. Tarda unos segundos más."
                } else {
                    "Haiku 5.5: la respuesta más rápida. Igual se detiene a pensar si la pregunta lo necesita."
                },
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = colores.onSurfaceVariant,
            )
        }

        TituloSeccion("Escucha")
        Tarjeta {
            Selector(listOf(ModoEscucha.SIEMPRE to "Siempre atento", ModoEscucha.BOTON to "Solo al tocar"), modo) {
                modo = it
                ajustes.modoEscucha = it
            }
            Text(
                if (modo == ModoEscucha.SIEMPRE) {
                    "Manos libres: oye la frase todo el tiempo por las gafas. Gasta más batería y, mientras está encendido, la música suena con calidad de llamada."
                } else {
                    "El micrófono solo se abre cuando tocas el orbe o el botón Hablar de la notificación. Cuida la batería y no afecta la música."
                },
                Modifier.padding(top = 8.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = colores.onSurfaceVariant,
            )
            Campo(frases, "Frase de activación", ayuda = "Palabras en español. Puedes poner varias separadas por coma.") {
                frases = it
                ajustes.frases = it
            }
            FilaInterruptor("Seguir la conversación", "Tras cada respuesta, unos segundos para seguir sin repetir la frase.", seguir) {
                seguir = it
                ajustes.seguirConversacion = it
            }
            FilaInterruptor("Poder interrumpir", "Decir la frase mientras Claude habla lo calla y te escucha.", interrumpible) {
                interrumpible = it
                ajustes.interrumpible = it
            }
            FilaInterruptor("Vista rápida", "Si preguntas \"¿qué es esto?\", toma la foto de una vez para responder antes.", vistaRapida) {
                vistaRapida = it
                ajustes.vistaRapida = it
            }
            FilaInterruptor("Sonidos", "Un tono corto al empezar a escucharte y al entender.", sonidos) {
                sonidos = it
                ajustes.sonidos = it
            }
        }

        TituloSeccion("Voz")
        Tarjeta {
            Text("Velocidad: ${"%.2f".format(velocidad)}×", style = MaterialTheme.typography.titleSmall, color = colores.onSurface)
            Slider(
                value = velocidad,
                onValueChange = { velocidad = it },
                valueRange = 0.7f..1.6f,
                onValueChangeFinished = { ajustes.velocidadVoz = velocidad },
            )
            Text(
                "Usa la voz en español del celular. Para una mejor, instala voces de alta calidad en Ajustes del sistema → Texto a voz.",
                style = MaterialTheme.typography.bodySmall, color = colores.onSurfaceVariant,
            )
        }

        TituloSeccion("Transcripción")
        Tarjeta {
            Selector(listOf(MotorTranscripcion.LOCAL to "En el celular", MotorTranscripcion.NUBE to "En la nube"), transcripcion) {
                transcripcion = it
                ajustes.transcripcion = it
            }
            Text(
                if (transcripcion == MotorTranscripcion.LOCAL) {
                    "Sin internet y privado. Suficiente para órdenes claras; se enreda con nombres propios e inglés."
                } else {
                    "Whisper en la nube: entiende mucho mejor nombres, inglés mezclado y ruido. La frase de activación sigue siendo local."
                },
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = colores.onSurfaceVariant,
            )
            if (transcripcion == MotorTranscripcion.NUBE) {
                Spacer(Modifier.height(8.dp))
                Campo(sttKey, "Llave del servicio", ayuda = "Por defecto Groq (console.groq.com), que tiene plan gratuito.", secreto = true) {
                    sttKey = it
                    ajustes.sttKey = it
                }
                Campo(sttUrl, "Dirección del servicio") {
                    sttUrl = it
                    ajustes.sttUrl = it
                }
                Campo(sttModelo, "Modelo") {
                    sttModelo = it
                    ajustes.sttModelo = it
                }
            }
        }

        TituloSeccion("Sobre ti")
        Tarjeta {
            Campo(nombre, "Cómo te llamas") {
                nombre = it
                ajustes.nombre = it
            }
            Spacer(Modifier.height(6.dp))
            Campo(instrucciones, "Cómo quieres que te hable", ayuda = "Ej.: \"Tutéame, sé breve y dime las cosas sin rodeos.\"", unaLinea = false) {
                instrucciones = it
                ajustes.instruccionesExtra = it
            }
        }

        val memoria by Memoria.apuntes.collectAsState()
        TituloSeccion("Memoria de Claude")
        Tarjeta {
            if (memoria.isEmpty()) {
                Text("Vacía. Dile \"recuerda que…\" y aparecerá aquí.", style = MaterialTheme.typography.bodySmall, color = colores.onSurfaceVariant)
            }
            memoria.forEach { dato ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(dato.texto, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = colores.onSurface)
                    TextButton(onClick = { Memoria.quitar(dato.id) }) { Text("Olvidar") }
                }
            }
        }

        val recordatorios by Recordatorios.pendientes.collectAsState()
        if (recordatorios.isNotEmpty()) {
            TituloSeccion("Recordatorios pendientes")
            Tarjeta {
                recordatorios.forEach { recordatorio ->
                    val cuando = Tiempo.decir(Instant.ofEpochMilli(recordatorio.cuando).atZone(ZoneId.systemDefault()))
                    Text(recordatorio.texto, style = MaterialTheme.typography.bodyMedium, color = colores.onSurface)
                    Text(cuando, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = colores.onSurfaceVariant)
                }
            }
        }

        val diario by Diario.lineas.collectAsState()
        TituloSeccion("Diagnóstico")
        Tarjeta {
            if (diario.isEmpty()) {
                Text("Aquí verás qué hace la app por dentro y cuánto tarda cada paso.", style = MaterialTheme.typography.bodySmall, color = colores.onSurfaceVariant)
            }
            diario.takeLast(40).forEach { linea ->
                Text(linea, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = colores.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
