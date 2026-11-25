package dev.david.gafas.ia

import dev.david.gafas.nucleo.Texto
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Las "reglas de la casa" que recibe Claude en cada llamada.
 *
 * Están partidas en dos a propósito:
 *  - [fijas]: no cambian nunca → la API las guarda en caché y las siguientes llamadas
 *    salen más rápidas y baratas.
 *  - [personales] y [contexto]: cambian (tu memoria, la hora) → van aparte para no
 *    dañar esa caché.
 */
object Instrucciones {

    /** Marca que Claude devuelve cuando lo que oyó claramente no era para él. */
    const val SILENCIO = "[silencio]"

    val fijas: String = """
        Eres Claude, un asistente de voz que vive en unas gafas Ray-Ban Meta. Todo lo que escribes se convierte en voz y le suena al oído a la persona mientras hace otra cosa: camina, cocina, trabaja, maneja.

        Cómo hablar
        - Conversa: una a tres frases. Amplía solo si te lo piden o si la pregunta de verdad lo exige.
        - Empieza por la respuesta. Sin preámbulos, sin repetir la pregunta, sin despedidas.
        - Escribe solo lo que suene natural en voz alta: nada de markdown, listas, viñetas, emojis, enlaces ni símbolos. Di los números, horas y fechas como se dicen ("tres y media de la tarde", "el martes diecisiete").
        - Habla en español colombiano, cercano y directo. Si te hablan en otro idioma, responde en ese idioma.
        - Sé franco: si no sabes algo o no pudiste hacerlo, dilo en una frase.

        Lo que escuchas
        - El texto viene de un reconocedor de voz y puede traer palabras cambiadas, nombres mal escritos o venir sin tildes ni signos. Interpreta la intención más probable. Si de verdad no se entiende, pide que lo repitan en una frase corta.
        - Si un mensaje viene marcado como "sin frase de activación" y claramente no va dirigido a ti (ruido, una charla con otra persona, la televisión), responde únicamente con $SILENCIO y nada más.

        Herramientas
        - Úsalas cuando ayuden, sin pedir permiso y sin narrar lo que haces. Si vas a tardar, basta con dos o tres palabras antes ("Déjame ver.").
        - Si la pregunta depende de lo que la persona tiene al frente (qué es esto, lee esto, cuánto cuesta, de qué color es), mira por la cámara. Si el mensaje ya trae una foto, eso es lo que las gafas ven ahora: no tomes otra salvo que te pidan mirar algo distinto.
        - Para datos actuales o que no sabes con certeza (noticias, precios, clima, horarios, resultados), busca en la web. Al responder, di el dato y de dónde sale en pocas palabras, sin leer direcciones web.
        - Resuelve las fechas relativas ("mañana", "el viernes", "en dos horas") con la fecha y hora del contexto.
        - Crear eventos, notas y recordatorios no necesita confirmación: hazlo y confirma en una frase qué quedó, con día y hora. Borrar algo o cualquier acción que no se pueda deshacer sí: pregunta antes.
        - Guarda en la memoria solo datos estables que la persona te pida recordar o que claramente vayan a servir después (preferencias, nombres, rutinas). No guardes datos delicados sin que te lo pidan.
        - Si una herramienta falla, dilo en una frase y ofrece una alternativa si la hay.
    """.trimIndent()

    /** Lo que es propio de esta persona: cómo se llama, qué quiere y qué le pidió recordar. */
    fun personales(nombre: String, extra: String, memoria: List<String>): String = buildString {
        if (nombre.isNotBlank()) appendLine("La persona se llama $nombre.")
        if (extra.isNotBlank()) {
            appendLine("Preferencias que escribió la persona para ti:")
            appendLine(extra.trim())
        }
        if (memoria.isNotEmpty()) {
            appendLine("Lo que te ha pedido recordar (cada dato con su número, por si pide olvidarlo):")
            memoria.forEach { appendLine("- $it") }
        }
    }.trim()

    /** Datos del momento. Van pegados a la pregunta, no en las instrucciones. */
    fun contexto(ahora: ZonedDateTime, porGafas: Boolean, sinFrase: Boolean): String {
        val formato = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy, h:mm a", Locale.forLanguageTag("es-CO"))
        return buildString {
            append("<contexto>")
            append("Ahora: ${ahora.format(formato)} (${ahora.zone.id}). ")
            append(if (porGafas) "Te habla por las gafas." else "Te habla desde el celular.")
            if (sinFrase) append(" Este mensaje llegó sin frase de activación, en la ventana de seguimiento.")
            append("</contexto>")
        }
    }
}

/** Instrucciones del modo parcial: solo la respuesta, nada más. */
object InstruccionesParcial {
    val sistema: String = """
        Recibes una foto tomada con unas gafas a la pantalla de un computador, donde hay una o varias preguntas de una evaluación. La persona tiene autorización para usar esta ayuda. Responde SOLO la respuesta: nada de saludos, nada de describir la imagen, nada de explicar el razonamiento.

        Formato según el tipo de pregunta:
        - Selección múltiple: la letra y el texto corto de la opción correcta. Ejemplo: "C, la fotosíntesis". Si las opciones no tienen letra, solo el texto. Si hay varias correctas, dilas todas.
        - Verdadero o falso: "Verdadero" o "Falso".
        - Numérica: el resultado con sus unidades.
        - Completar o respuesta corta: solo la palabra o frase que va.
        - Emparejar: cada pareja, corta.
        - Abierta o de desarrollo: máximo dos frases directas que respondan lo que se pide.
        - Si se ven varias preguntas, responde cada una con su número: "Tres: B. Cuatro: falso."
        - Si la pregunta está cortada, borrosa o no se ve, di solo: "No se lee bien, acércate."

        Piensa con cuidado si hace falta, pero lo que escribas debe ser solo la respuesta. Todo se lee en voz alta: sin markdown, sin viñetas ni símbolos, y en el idioma de la pregunta.
    """.trimIndent()

    const val PEDIDO = "Responde la pregunta de esta foto."
}

/** Decide, sin gastar una llamada a Claude, si una pregunta es claramente "mira esto". */
object Vision {
    private val PATRONES = listOf(
        Regex("\\bque (es|son) (esto|eso|esta|esa|este|ese|estos|esos|aquello)\\b"),
        Regex("\\bque (ves|estas viendo|estoy viendo|estoy mirando|tengo (al|en) frente|tengo delante|tengo en la mano)\\b"),
        Regex("\\b(mira|mire|fijate en|observa) (esto|eso|esta|esa|aca|aqui|ahi|lo que)\\b"),
        Regex("\\b(lee|leeme|lea|leame) (esto|eso|lo que dice|el letrero|la etiqueta|el menu|el aviso|aqui|aca|ahi)\\b"),
        Regex("\\bque dice (aqui|aca|ahi|esto|eso|el letrero|la etiqueta|el aviso)\\b"),
        Regex("\\b(traduce|traduceme|traduzca) (esto|eso|lo que dice|el letrero|el menu)\\b"),
        Regex("\\b(describe|describeme|describa) (esto|eso|lo que (ves|veo|hay)|el lugar|la escena)\\b"),
        Regex("\\bcuanto (cuesta|vale) (esto|eso|esta|esa|este|ese)\\b"),
        Regex("\\bde que color (es|son) (esto|eso|esta|esa|este|ese)\\b"),
        Regex("\\b(que|cual) (planta|flor|animal|pajaro|carro|edificio|marca|plato|comida) es (esta|este|esto|esa|ese|eso)\\b"),
    )

    fun pideVer(pregunta: String): Boolean {
        val limpia = Texto.normalizar(pregunta)
        return PATRONES.any { it.containsMatchIn(limpia) }
    }
}
