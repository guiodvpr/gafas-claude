package dev.david.gafas.herramientas

import android.content.Context
import dev.david.gafas.datos.Memoria
import dev.david.gafas.datos.Notas
import dev.david.gafas.datos.Recordatorios
import dev.david.gafas.nucleo.Tiempo
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONObject

/** Recordatorios: "recuérdame en veinte minutos sacar la ropa". */
object HerramientasRecordatorio {

    class Crear(private val context: Context) : Herramienta {
        override val nombre = "crear_recordatorio"
        override val descripcion =
            "Programa un recordatorio: a la hora indicada suena una notificación en el celular y, si el asistente " +
                "está encendido, se lo dices al oído. Sirve también como temporizador. Usa 'en_minutos' para " +
                "tiempos relativos y 'cuando' para una fecha y hora concretas."
        override val parametros: JSONObject = Esquema.objeto(
            "texto" to Esquema.texto("Qué hay que recordar, redactado como se lo dirías a la persona"),
            "en_minutos" to Esquema.entero("Dentro de cuántos minutos debe sonar"),
            "cuando" to Esquema.texto("Fecha y hora local ISO 8601: 2026-11-17T07:30"),
            requeridos = listOf("texto"),
        )
        override val enCurso = "Programando el recordatorio…"
        override val hecho = "Programó un recordatorio"

        override suspend fun ejecutar(entrada: JSONObject): Resultado {
            val texto = entrada.optString("texto").trim()
            if (texto.isEmpty()) return Resultado.error("Falta qué hay que recordar.")
            val momento: ZonedDateTime = when {
                entrada.has("en_minutos") && entrada.optInt("en_minutos", 0) > 0 ->
                    ZonedDateTime.now().plusMinutes(entrada.optInt("en_minutos").toLong())
                entrada.optString("cuando").isNotBlank() -> Tiempo.interpretar(entrada.optString("cuando"))
                    ?: return Resultado.error("No entendí la fecha. Usa el formato 2026-11-17T07:30.")
                else -> return Resultado.error("Falta cuándo: manda 'en_minutos' o 'cuando'.")
            }
            if (momento.isBefore(ZonedDateTime.now())) return Resultado.error("Esa hora ya pasó.")
            val creado = Recordatorios.crear(context, texto, momento.toInstant().toEpochMilli())
            return Resultado("Recordatorio ${creado.id} programado para ${Tiempo.decir(momento)}.")
        }
    }

    class Listar : Herramienta {
        override val nombre = "listar_recordatorios"
        override val descripcion = "Lista los recordatorios pendientes con su número y su hora."
        override val parametros: JSONObject = Esquema.objeto()
        override val enCurso = "Revisando tus recordatorios…"
        override val hecho = "Revisó los recordatorios"

        override suspend fun ejecutar(entrada: JSONObject): Resultado {
            val pendientes = Recordatorios.pendientes.value
            if (pendientes.isEmpty()) return Resultado("No hay recordatorios pendientes.")
            return Resultado(
                pendientes.joinToString("\n") {
                    val momento = Instant.ofEpochMilli(it.cuando).atZone(ZoneId.systemDefault())
                    "- ${it.id}: ${it.texto} (${Tiempo.decir(momento)})"
                },
            )
        }
    }

    class Cancelar(private val context: Context) : Herramienta {
        override val nombre = "cancelar_recordatorio"
        override val descripcion = "Cancela un recordatorio pendiente por su número (sale de listar_recordatorios)."
        override val parametros: JSONObject = Esquema.objeto(
            "id" to Esquema.entero("Número del recordatorio"),
            requeridos = listOf("id"),
        )
        override val enCurso = "Cancelando el recordatorio…"
        override val hecho = "Canceló un recordatorio"

        override suspend fun ejecutar(entrada: JSONObject): Resultado =
            if (Recordatorios.cancelar(context, entrada.optInt("id", -1))) {
                Resultado("Recordatorio cancelado.")
            } else {
                Resultado.error("No hay un recordatorio con ese número.")
            }
    }
}

/** Notas rápidas: "anota que el carro quedó en el sótano dos". */
object HerramientasNotas {

    class Guardar : Herramienta {
        override val nombre = "guardar_nota"
        override val descripcion =
            "Guarda una nota de texto que la persona dicta (una idea, un dato, una lista). Quedan en la app y se " +
                "pueden consultar después con buscar_notas."
        override val parametros: JSONObject = Esquema.objeto(
            "texto" to Esquema.texto("El contenido de la nota, bien redactado"),
            requeridos = listOf("texto"),
        )
        override val enCurso = "Anotando…"
        override val hecho = "Guardó una nota"

        override suspend fun ejecutar(entrada: JSONObject): Resultado {
            val texto = entrada.optString("texto").trim()
            if (texto.isEmpty()) return Resultado.error("La nota está vacía.")
            return Resultado("Nota ${Notas.agregar(texto).id} guardada.")
        }
    }

    class Buscar : Herramienta {
        override val nombre = "buscar_notas"
        override val descripcion =
            "Busca en las notas guardadas. Sin 'consulta' devuelve las más recientes. Úsala cuando la persona " +
                "pregunte por algo que anotó."
        override val parametros: JSONObject = Esquema.objeto(
            "consulta" to Esquema.texto("Opcional: palabras clave para filtrar"),
        )
        override val enCurso = "Buscando en tus notas…"
        override val hecho = "Revisó las notas"

        override suspend fun ejecutar(entrada: JSONObject): Resultado {
            val encontradas = Notas.buscar(entrada.optString("consulta")).takeLast(15).reversed()
            if (encontradas.isEmpty()) return Resultado("No encontré notas sobre eso.")
            return Resultado(
                encontradas.joinToString("\n") {
                    val dia = Tiempo.decirDia(Instant.ofEpochMilli(it.hora).atZone(ZoneId.systemDefault()))
                    "- ${it.id} ($dia): ${it.texto}"
                },
            )
        }
    }

    class Borrar : Herramienta {
        override val nombre = "borrar_nota"
        override val descripcion = "Borra una nota por su número. Confirma antes con la persona cuál vas a borrar."
        override val parametros: JSONObject = Esquema.objeto(
            "id" to Esquema.entero("Número de la nota"),
            requeridos = listOf("id"),
        )
        override val enCurso = "Borrando la nota…"
        override val hecho = "Borró una nota"

        override suspend fun ejecutar(entrada: JSONObject): Resultado =
            if (Notas.quitar(entrada.optInt("id", -1))) Resultado("Nota borrada.") else Resultado.error("No hay una nota con ese número.")
    }
}

/** Memoria: lo que Claude sabe de ti de una conversación a otra. */
object HerramientasMemoria {

    class Recordar : Herramienta {
        override val nombre = "recordar"
        override val descripcion =
            "Guarda en tu memoria permanente un dato estable sobre la persona (una preferencia, un nombre, una " +
                "rutina) para tenerlo presente en conversaciones futuras. No es para notas ni tareas."
        override val parametros: JSONObject = Esquema.objeto(
            "dato" to Esquema.texto("El dato en una frase corta y clara: 'Prefiere el café sin azúcar'"),
            requeridos = listOf("dato"),
        )
        override val enCurso = "Guardando en memoria…"
        override val hecho = "Guardó algo en memoria"

        override suspend fun ejecutar(entrada: JSONObject): Resultado {
            val dato = entrada.optString("dato").trim()
            if (dato.isEmpty()) return Resultado.error("No hay dato que guardar.")
            return Resultado("Guardado en memoria con el número ${Memoria.agregar(dato).id}.")
        }
    }

    class Olvidar : Herramienta {
        override val nombre = "olvidar"
        override val descripcion = "Borra de tu memoria permanente el dato con ese número (los números están en tus instrucciones)."
        override val parametros: JSONObject = Esquema.objeto(
            "id" to Esquema.entero("Número del dato"),
            requeridos = listOf("id"),
        )
        override val enCurso = "Borrando de memoria…"
        override val hecho = "Olvidó un dato"

        override suspend fun ejecutar(entrada: JSONObject): Resultado =
            if (Memoria.quitar(entrada.optInt("id", -1))) Resultado("Olvidado.") else Resultado.error("No tengo un dato con ese número.")
    }
}
