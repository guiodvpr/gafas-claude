package dev.david.gafas

import dev.david.gafas.audio.Frases
import dev.david.gafas.audio.Wav
import dev.david.gafas.ia.Ensamblador
import dev.david.gafas.ia.ErrorApi
import dev.david.gafas.ia.PerfilModelo
import dev.david.gafas.ia.Vision
import dev.david.gafas.nucleo.Texto
import dev.david.gafas.nucleo.Tiempo
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la lógica que no depende de Android. Se corren con: ./gradlew test
 * Cubren justo lo más frágil: partir frases para la voz, reconocer la frase de
 * activación y reconstruir la respuesta de Claude desde el streaming.
 */
class LogicaTest {

    // ── Texto ──

    @Test fun normalizaTildesYSignos() {
        assertEquals("oye claudio", Texto.normalizar("¡Oye, Cláudio!"))
        assertEquals("mañana a las 3", Texto.normalizar("Mañana, a las 3."))
    }

    @Test fun quitaLaFraseCompleta() {
        assertEquals("qué hora es", Texto.quitarFrase("oye claudio qué hora es", listOf("oye claudio")))
    }

    @Test fun quitaSoloElFinalDeLaFrase() {
        assertEquals("pon música", Texto.quitarFrase("claudio pon música", listOf("oye claudio")))
    }

    @Test fun quitaLaFraseComoLaEscribeLaNube() {
        assertEquals("¿me oyes?", Texto.quitarFrase("Oye, Claude: ¿me oyes?", listOf("oye claudio")))
        assertEquals("¿qué hora es?", Texto.quitarFrase("Claude, ¿qué hora es?", listOf("oye claudio")))
    }

    @Test fun noSeComePalabrasQueNoSonLaFrase() {
        assertEquals("oye qué pasó ayer", Texto.quitarFrase("oye qué pasó ayer", listOf("oye claudio")))
        assertEquals("audio de la reunión", Texto.quitarFrase("audio de la reunión", listOf("oye claudio")))
        assertEquals("", Texto.quitarFrase("oye claudio", listOf("oye claudio")))
    }

    @Test fun detectaLaFraseDentroDeUnParcial() {
        val frases = listOf("oye claudio")
        assertTrue(Texto.contieneFrase("oye claudio", frases))
        assertTrue(Texto.contieneFrase("unk oye claudio", frases))
        assertFalse(Texto.contieneFrase("oye", frases))
        assertFalse(Texto.contieneFrase("oye claudiooo", frases))
    }

    // ── Frases ──

    @Test fun sueltaLaPrimeraFraseApenasCierra() {
        val frases = Frases()
        assertEquals(emptyList<String>(), frases.agregar("Son las tres"))
        assertEquals(listOf("Son las tres y media."), frases.agregar(" y media. Tienes"))
        assertEquals("Tienes", frases.cortar())
        assertNull(frases.cortar())
    }

    @Test fun noParteDecimalesNiAbreviaturas() {
        val frases = Frases()
        val salida = frases.agregar("Cuesta 3.5 dólares según el Dr. Pérez, que lo revisó ayer. Listo. ")
        assertEquals(listOf("Cuesta 3.5 dólares según el Dr. Pérez, que lo revisó ayer.", "Listo."), salida + listOfNotNull(frases.cortar()))
    }

    @Test fun juntaFrasesMuyCortasDespuesDeLaPrimera() {
        val frases = Frases()
        val salida = frases.agregar("Claro que sí. Sí. No. Eso depende de varias cosas distintas. ")
        assertEquals("Claro que sí.", salida.first())
        assertTrue(salida.size <= 3)
    }

    @Test fun limpiaMarkdownParaLaVoz() {
        assertEquals("Hola mundo, mira esto", Frases.limpiarParaVoz("**Hola** _mundo_, mira [esto](https://x.co) 😀"))
        assertEquals("Primero", Frases.limpiarParaVoz("- Primero"))
        assertEquals("", Frases.limpiarParaVoz("https://ejemplo.com/pagina"))
    }

    // ── Tiempo ──

    @Test fun interpretaLasFechasQueMandaClaude() {
        val bogota = ZoneId.of("America/Bogota")
        assertEquals(15, Tiempo.interpretar("2026-11-17T15:00", bogota)?.hour)
        assertEquals(15, Tiempo.interpretar("2026-11-17 15:00:00", bogota)?.hour)
        assertEquals(15, Tiempo.interpretar("2026-11-17T20:00:00Z", bogota)?.hour)
        assertEquals(0, Tiempo.interpretar("2026-11-17", bogota)?.hour)
        assertNull(Tiempo.interpretar("mañana", bogota))
        assertTrue(Tiempo.esSoloFecha("2026-11-17"))
        assertFalse(Tiempo.esSoloFecha("2026-11-17T15:00"))
    }

    // ── Visión ──

    @Test fun reconocePreguntasVisuales() {
        assertTrue(Vision.pideVer("¿Qué es esto?"))
        assertTrue(Vision.pideVer("léeme lo que dice ahí"))
        assertTrue(Vision.pideVer("cuánto cuesta esto"))
        assertTrue(Vision.pideVer("qué estoy viendo"))
        assertFalse(Vision.pideVer("qué hora es"))
        assertFalse(Vision.pideVer("explícame esto del blockchain"))
        assertFalse(Vision.pideVer("qué tengo mañana en el calendario"))
    }

    // ── Perfil de modelo ──

    @Test fun cadaModeloRecibeSoloLoQueAcepta() {
        val haiku = JSONObject().also { PerfilModelo.de("claude-haiku-5-5").aplicar(it, "low") }
        assertEquals("disabled", haiku.getJSONObject("thinking").getString("type"))
        assertEquals("low", haiku.getJSONObject("output_config").getString("effort"))

        val sonnet = JSONObject().also { PerfilModelo.de("claude-sonnet-5-5").aplicar(it, "medium") }
        assertEquals("between_tools", sonnet.getJSONObject("thinking").getString("type"))

        val opus = JSONObject().also { PerfilModelo.de("claude-opus-5-5").aplicar(it, "raro") }
        assertFalse(opus.has("thinking"))
        assertEquals("low", opus.getJSONObject("output_config").getString("effort"))

        val otro = JSONObject().also { PerfilModelo.de("modelo-futuro").aplicar(it, "low") }
        assertFalse(otro.has("thinking"))
        assertFalse(otro.has("output_config"))
        assertTrue(otro.has("max_tokens"))
    }

    // ── Streaming ──

    private fun alimentar(ensamblador: Ensamblador, vararg eventos: String): List<Ensamblador.Novedad> =
        eventos.mapNotNull { ensamblador.procesar(JSONObject(it)) }

    @Test fun reconstruyeTextoYHerramientaDesdeElStreaming() {
        val e = Ensamblador()
        val novedades = alimentar(
            e,
            """{"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":25,"cache_read_input_tokens":900,"output_tokens":1}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"FIRMA123"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"ping"}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Déjame "}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"ver."}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"consultar_calendario","input":{}}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"desde\": \"2026-11-17T00:00\""}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":", \"hasta\": \"2026-11-17T23:59\"}"}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":48}}""",
            """{"type":"message_stop"}""",
        )
        assertEquals(3, novedades.size)
        assertEquals("Déjame ver.", e.texto())
        assertEquals("tool_use", e.razonDeParada)
        assertEquals(48, e.tokensSalida)
        assertEquals(900, e.tokensDeCache)

        val contenido = e.contenido()
        assertEquals(3, contenido.length())
        // El bloque de pensamiento vuelve con su firma intacta: sin eso la API rechaza el turno siguiente
        assertEquals("FIRMA123", contenido.getJSONObject(0).getString("signature"))
        assertEquals("Déjame ver.", contenido.getJSONObject(1).getString("text"))
        val herramienta = contenido.getJSONObject(2)
        assertEquals("toolu_1", herramienta.getString("id"))
        assertEquals("2026-11-17T23:59", herramienta.getJSONObject("input").getString("hasta"))
    }

    @Test fun conservaLosBloquesDeBusquedaTalCual() {
        val e = Ensamblador()
        val novedades = alimentar(
            e,
            """{"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{}}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"clima bogotá hoy\"}"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[{"type":"web_search_result","url":"https://x.co","title":"Clima","encrypted_content":"abc"}]}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"text","text":"","citations":[]}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"citations_delta","citation":{"type":"web_search_result_location","url":"https://x.co","title":"Clima","encrypted_index":"zz","cited_text":"18 grados"}}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"text_delta","text":"Hay dieciocho grados."}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"content_block_start","index":3,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_stop","index":3}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":20}}""",
        )
        assertTrue((novedades.first() as Ensamblador.Novedad.Herramienta).enServidor)
        val contenido = e.contenido()
        // El bloque de texto vacío del final se descarta: la API no los acepta de vuelta
        assertEquals(3, contenido.length())
        assertEquals("clima bogotá hoy", contenido.getJSONObject(0).getJSONObject("input").getString("query"))
        assertEquals("abc", contenido.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("encrypted_content"))
        assertEquals(1, contenido.getJSONObject(2).getJSONArray("citations").length())
        assertEquals("end_turn", e.razonDeParada)
    }

    @Test fun unEventoDeErrorSeVuelveExcepcion() {
        var atrapado: ErrorApi? = null
        try {
            Ensamblador().procesar(JSONObject("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))
        } catch (e: ErrorApi) {
            atrapado = e
        }
        assertNotNull(atrapado)
        assertTrue(atrapado!!.esPasajero)
    }

    // ── WAV ──

    @Test fun laCabeceraWavEsValida() {
        val wav = Wav.desdePcm(ByteArray(3200), 16_000)
        assertEquals(44 + 3200, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        // Tasa de muestreo en little-endian en el byte 24: 16000 = 0x3E80
        assertEquals(0x80, wav[24].toInt() and 0xFF)
        assertEquals(0x3E, wav[25].toInt() and 0xFF)
    }
}
