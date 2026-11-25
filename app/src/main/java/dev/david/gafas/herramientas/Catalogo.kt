package dev.david.gafas.herramientas

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import dev.david.gafas.nucleo.Ajustes

/**
 * EL CATÁLOGO. La lista de todo lo que Claude puede hacer desde las gafas.
 *
 * PARA AGREGAR UNA CAPACIDAD NUEVA:
 *   1. Escribe una clase que implemente [Herramienta] (mira [Medios]: es la más corta).
 *   2. Súmala aquí abajo dentro de una [Capacidad].
 * Eso es todo: aparece sola en la pestaña Capacidades con su interruptor y su botón de
 * permisos, y Claude empieza a usarla cuando le sirva.
 */
class Catalogo(private val context: Context) {
    private val ajustes = Ajustes.de(context)

    val camara = CamaraGafas(context)

    val capacidades: List<Capacidad> = listOf(
        Capacidad(
            id = "camara",
            titulo = "Ver por las gafas",
            descripcion = "Claude toma una foto con las gafas cuando la pregunta depende de lo que tienes al frente.",
            herramientas = listOf(camara),
        ),
        Capacidad(
            id = "calendario",
            titulo = "Calendario",
            descripcion = "Consulta tu agenda y crea o borra eventos en el calendario del celular (el de Google incluido).",
            permisos = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            herramientas = listOf(Calendario.Consultar(context), Calendario.Crear(context), Calendario.Borrar(context)),
        ),
        Capacidad(
            id = "recordatorios",
            titulo = "Recordatorios y temporizadores",
            descripcion = "\"Recuérdame en veinte minutos…\". Suena en el celular y te lo dice al oído.",
            herramientas = listOf(
                HerramientasRecordatorio.Crear(context),
                HerramientasRecordatorio.Listar(),
                HerramientasRecordatorio.Cancelar(context),
            ),
        ),
        Capacidad(
            id = "notas",
            titulo = "Notas",
            descripcion = "Dicta ideas y datos sueltos; después pregúntale por ellos.",
            herramientas = listOf(HerramientasNotas.Guardar(), HerramientasNotas.Buscar(), HerramientasNotas.Borrar()),
        ),
        Capacidad(
            id = "memoria",
            titulo = "Memoria",
            descripcion = "Claude recuerda de una conversación a otra lo que le pidas tener presente.",
            herramientas = listOf(HerramientasMemoria.Recordar(), HerramientasMemoria.Olvidar()),
        ),
        Capacidad(
            id = "ubicacion",
            titulo = "Ubicación",
            descripcion = "Sabe dónde estás para responder \"¿dónde estoy?\" o buscar cosas cerca.",
            permisos = listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            herramientas = listOf(Ubicacion(context)),
        ),
        Capacidad(
            id = "medios",
            titulo = "Música y audio",
            descripcion = "Pausa, reanuda, cambia de canción o de volumen lo que esté sonando.",
            herramientas = listOf(Medios(context)),
        ),
        Capacidad(
            id = "enviar",
            titulo = "Enviar al celular",
            descripcion = "\"Mándame ese enlace\": te deja textos y enlaces en una notificación para verlos después.",
            herramientas = listOf(EnviarAlCelular(context)),
        ),
        Capacidad(
            id = "dispositivos",
            titulo = "Batería y conexión",
            descripcion = "Batería del celular y de las gafas, y si hay wifi o datos.",
            herramientas = listOf(Dispositivo(context)),
        ),
    )

    fun activa(capacidad: Capacidad): Boolean = ajustes.capacidadActiva(capacidad.id, capacidad.porDefecto)

    fun permisosFaltantes(capacidad: Capacidad): List<String> =
        capacidad.permisos.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /** Lo que se le ofrece a Claude en esta pregunta: lo encendido y con sus permisos concedidos. */
    fun herramientasActivas(): List<Herramienta> =
        capacidades.filter { activa(it) && permisosFaltantes(it).isEmpty() }.flatMap { it.herramientas }

    fun cerrar() = camara.cerrar()
}
