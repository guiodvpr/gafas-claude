# Gafas + Claude · v2

Le hablas a tus Ray-Ban Meta y Claude te responde al oído. Ve por la cámara de las gafas, maneja tu calendario, te pone recordatorios, toma notas, busca en la web y se conecta con otras apps por MCP.

```
"oye claudio, ¿qué tengo mañana?"
        │
        ▼
Microfono ─▶ Oido (Vosk, offline) ─▶ Agente ──streaming──▶ Frases ─▶ Voz ─▶ parlantes de las gafas
(Bluetooth)   frase + orden            │  (Claude)          (habla la 1ª frase mientras
                                       │                     Claude escribe la 3ª)
                                       └─▶ Capacidades: cámara · calendario · recordatorios · notas
                                           memoria · ubicación · música · web · conectores MCP
```

## Qué cambió frente a la v1

| | v1 | v2 |
|---|---|---|
| **Velocidad** | Esperaba la respuesta completa para hablar | Streaming: habla frase por frase. Pensamiento mínimo por modelo, caché de instrucciones, "vista rápida" (toma la foto sin esperar a que Claude la pida) |
| **Conversación** | Un turno a la vez | La interrumpes diciendo la frase o tocando el orbe. Recuerda la última foto para preguntas de seguimiento |
| **Oído** | Perdía el inicio de la orden | Memoria de 2 s de audio: "oye claudio qué hora es" de corrido funciona. Transcripción en la nube opcional (Whisper) |
| **Integraciones** | Cámara + web | 9 capacidades locales, búsqueda y lectura web, conectores MCP |
| **Robustez** | Si se caía el micrófono, se caía todo | Cambia solo entre gafas y celular, espera durante llamadas, reintenta errores de red |
| **Interfaz** | Un formulario | Orbe animado, chat con fotos, capacidades con interruptores, guía de puesta en marcha |
| **Seguridad** | Llave en texto plano | Llaves cifradas con el Android Keystore |

## Puesta en marcha (verifica cada paso antes de seguir)

**1. Que compile.** Abre la carpeta en Android Studio, espera el *Gradle sync* y dale ▶ con el celular conectado. Como el paquete es el mismo de la v1, se instala encima.
✅ Abre en la pantalla del orbe con una tarjeta amarilla que te guía.

**2. Claude por el celular (sin gafas).** Sigue la tarjeta: llave de Claude → permisos → toca el orbe. La primera vez baja el modelo de voz (~40 MB). Di *"oye claudio, ¿qué día es hoy?"*.
✅ Suena un tono, el orbe se pone coral mientras hablas, violeta mientras piensa y verde mientras responde.

**3. Por las gafas.** Conéctalas por Bluetooth y vuelve a iniciar.
✅ Arriba dice "Escuchando por las gafas" y la voz sale por ellas.

**4. La cámara.** En Meta AI: Ajustes → Información de la app → 5 toques en la versión → **Developer Mode**. En la app, la tarjeta te lleva a *Conectar* y luego a *Permitir*. Pregunta *"oye claudio, ¿qué es esto?"*.
✅ La foto aparece en la pestaña Chat junto a la respuesta.

**5. El resto.** En Capacidades da el permiso de calendario y prueba: *"¿qué tengo mañana?"*, *"agéndame dentista el viernes a las 3"*, *"recuérdame en diez minutos apagar el horno"*, *"anota que el carro quedó en el sótano dos"*, *"recuerda que prefiero el café sin azúcar"*, *"pausa la música"*, *"mándame ese enlace al celular"*.

## Modo parcial

Foto → respuesta directa, sin explicaciones. Se prende con la pastilla **Parcial** arriba a la derecha en Inicio.

- **Disparar:** toca el orbe, di la frase de activación o usa el botón **Foto** de la notificación. Un tono = tomando la foto; segundo tono = ya la tomó, puedes mover la cabeza.
- **Siguiente pregunta:** toca el orbe otra vez, aunque todavía esté leyendo la respuesta.
- **La respuesta:** la dice al oído y queda grande en pantalla con la miniatura de la foto y cuánto tardó. También queda en el Chat.
- **Sin gafas conectadas** no habla por el parlante del celular: la respuesta solo aparece en pantalla.
- **Rápido o Preciso** (en Ajustes): Haiku 5.5 o Sonnet 5.5. Los dos piensan solo si la pregunta lo necesita.

Lo que lo hace rápido: la foto va en la primera llamada (sin ida y vuelta), sin historial ni herramientas, y la sesión con la cámara se abre apenas prendes el modo y se mantiene abierta.

## Cómo está armado

```
nucleo/        Estado (lo que ve la interfaz) · Ajustes · Boveda (cifrado) · Texto · Tiempo · Diario
audio/         RutaAudio (canal a las gafas) · Microfono · Oido (Vosk) · Transcriptor (nube) · Frases · Voz
ia/            ClaudeApi (streaming) · Ensamblador (reconstruye la respuesta) · PerfilModelo · Instrucciones · Agente
herramientas/  Herramienta (la interfaz) · Catalogo · una clase por capacidad
gafas/         SDK de Meta: registro, permiso de cámara, batería
datos/         Historial · Memoria · Notas · Recordatorios (archivos JSON)
servicio/      AsistenteService (el director) · receptores de alarmas
ui/            Tema · Orbe · las cuatro pantallas
```

Cada archivo abre con un comentario que explica qué problema resuelve y por qué existe.

## Cómo seguir creciendo

**Una capacidad nueva en el celular** — dos pasos:
1. Una clase que implemente `Herramienta` (copia `Medios` en `herramientas/Telefono.kt`: es la más corta).
2. Sumarla al `Catalogo` dentro de una `Capacidad`.

Aparece sola en la pestaña Capacidades con su interruptor y su botón de permisos, y Claude empieza a usarla.

**Cualquier otra app (Notion, Gmail, Slack, lo tuyo)** — sin tocar código: Capacidades → Conectores → pega la dirección `https://` de un servidor MCP remoto y su token. Claude usa sus herramientas del lado de Anthropic.

**Ideas que ya caben en esta arquitectura:** una voz más natural (cambiar `Voz` por un servicio de voz en streaming), "Hey Meta, abre la app" y botones de las gafas (el SDK 1.0 los trae como experimentales; requieren activarlos en el Wearables Developer Center), mensajes de WhatsApp vía un conector MCP.

## Qué está verificado y qué no

Esto se construyó sin un celular ni el SDK de Android completo a la mano. Lo que sí se hizo:

- ✅ Todo el código compila con Kotlin 2.2.21 contra el `android.jar` **real** de Android 16 (API 36), las corrutinas reales y las fuentes reales de Vosk.
- ✅ El verificador del compilador de Compose no encontró errores en la interfaz.
- ✅ Cada función de Compose y AndroidX usada se contrastó con los archivos de firmas oficiales de AndroidX.
- ✅ 17 pruebas ejecutadas y en verde sobre la lógica más frágil: reconstrucción del streaming, partición de frases, frase de activación, fechas (`./gradlew test`).
- ✅ El formato de la API de Claude (modelos, pensamiento, herramientas web, MCP, caché) se revisó contra la documentación vigente.

Lo que **solo se puede comprobar en tu celular**:

- ⚠️ Las llamadas al SDK de Meta se escribieron siguiendo su ejemplo oficial, pero no se compilaron contra el SDK. Lo más incierto es la batería de las gafas (`Gafas.kt`, `batteryLevel`): si Gradle se queja ahí, borra ese bloque y lo demás no se afecta.
- ⚠️ Nada se ha ejecutado en un dispositivo: audio por Bluetooth, tiempos reales y aspecto visual están sin probar.
- ⚠️ No pude confirmar que "claudio" esté en el diccionario del modelo de voz. Por eso la frase por defecto incluye el respaldo *"oye asistente"*.

Si algo falla, **Ajustes → Diagnóstico** muestra qué hizo la app y cuánto tardó cada paso.

## Si algo no funciona

| Síntoma | Qué hacer |
|---|---|
| Gradle no sincroniza | Pega el error tal cual; casi siempre es una versión de librería |
| No despierta con la frase | Prueba "oye asistente", cambia la frase en Ajustes, o usa "Solo al tocar" |
| Entiende mal nombres o inglés | Ajustes → Transcripción → En la nube (Groq tiene plan gratuito) |
| "La búsqueda web no está habilitada" | Actívala en console.anthropic.com o apágala en Capacidades |
| La música suena mal con el asistente encendido | Es el canal de llamada de Bluetooth. Usa el modo "Solo al tocar" |
| "Hey Meta" no responde mientras está encendido | Mismo motivo: el canal de voz lo tiene esta app |
| Responde a conversaciones ajenas | Apaga "Seguir la conversación" |
