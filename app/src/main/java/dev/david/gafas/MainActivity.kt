package dev.david.gafas

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import dev.david.gafas.gafas.Gafas
import dev.david.gafas.herramientas.Catalogo
import dev.david.gafas.nucleo.Ajustes
import dev.david.gafas.nucleo.Diario
import dev.david.gafas.nucleo.Estado
import dev.david.gafas.servicio.AsistenteService
import dev.david.gafas.ui.Acciones
import dev.david.gafas.ui.Icono
import dev.david.gafas.ui.PantallaAjustes
import dev.david.gafas.ui.PantallaCapacidades
import dev.david.gafas.ui.PantallaChat
import dev.david.gafas.ui.PantallaInicio
import dev.david.gafas.ui.Pendiente
import dev.david.gafas.ui.Pestana
import dev.david.gafas.ui.Tema

/**
 * LA PANTALLA. Solo muestra y configura: el trabajo de verdad lo hace [AsistenteService],
 * que sigue vivo aunque cierres esto. Las dos partes se comunican por [Estado].
 */
class MainActivity : ComponentActivity() {

    private lateinit var ajustes: Ajustes
    private lateinit var catalogo: Catalogo

    /** Sube cada vez que algo de afuera pudo cambiar (volviste a la app, diste un permiso). */
    private var refresco = mutableIntStateOf(0)
    private var pestana = mutableStateOf(Pestana.INICIO)
    private var iniciarAlConceder = false

    private val permisosAndroid = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresco.intValue++
        // El SDK de Meta necesita el permiso de Bluetooth antes de poder arrancar
        if (tiene(Manifest.permission.BLUETOOTH_CONNECT)) Gafas.inicializar(applicationContext)
        if (iniciarAlConceder) {
            iniciarAlConceder = false
            if (tiene(Manifest.permission.RECORD_AUDIO)) iniciar() else Estado.avisar("Sin permiso de micrófono no puedo escucharte")
        }
    }

    // El permiso de cámara de las gafas no es de Android: se concede dentro de la app Meta AI.
    // Meta AI abre su propia ventana y Android puede responder "cancelado" antes de que elijas,
    // así que solo un "sí" cuenta aquí; el resto lo confirma onResume preguntándoles a las gafas.
    private val permisoCamaraGafas = registerForActivityResult(Wearables.RequestPermissionContract()) { resultado ->
        resultado
            .onSuccess { if (it == PermissionStatus.Granted) Gafas.camaraPermitidaEnMetaAi() }
            .onFailure { error, _ ->
                Diario.error("Meta AI no pudo dar el permiso de cámara: ${error.description}")
                Estado.avisar("Meta AI no respondió: ${error.description}")
            }
        refresco.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ajustes = Ajustes.de(this)
        catalogo = Catalogo(this)
        enableEdgeToEdge()

        val acciones = Acciones(
            iniciar = ::iniciar,
            detener = { AsistenteService.detener(this) },
            hablar = { AsistenteService.enviar(this, AsistenteService.ACCION_HABLAR) },
            callar = { AsistenteService.enviar(this, AsistenteService.ACCION_CALLAR) },
            preguntar = { AsistenteService.enviar(this, AsistenteService.ACCION_TEXTO, it) },
            pedirPermisos = { permisosAndroid.launch(it.toTypedArray()) },
            conectarMeta = {
                if (Gafas.listo) Wearables.startRegistration(this) else pedirBasicos()
            },
            permitirCamara = {
                if (Gafas.listo) permisoCamaraGafas.launch(Permission.CAMERA) else pedirBasicos()
            },
            irA = { pestana.value = it },
            cambiarParcial = { activo ->
                ajustes.modoParcial = activo
                Estado.respuestaParcial.value = null
                if (activo) {
                    Estado.avisar(
                        if (Estado.gafas.value.camaraPermitida) "Modo parcial: cada foto trae solo la respuesta"
                        else "Modo parcial activo, pero falta conectar la cámara de las gafas",
                    )
                }
            },
        )
        setContent { Tema { Aplicacion(acciones) } }
    }

    override fun onResume() {
        super.onResume()
        refresco.intValue++
        Gafas.revisarPermisoDeCamara()
    }

    override fun onDestroy() {
        catalogo.cerrar()
        super.onDestroy()
    }

    private fun tiene(permiso: String) = checkSelfPermission(permiso) == PackageManager.PERMISSION_GRANTED

    private fun pedirBasicos() = permisosAndroid.launch(PERMISOS_BASICOS)

    private fun iniciar() {
        when {
            ajustes.apiKey.isBlank() -> {
                Estado.avisar("Primero pon tu llave de Claude")
                pestana.value = Pestana.AJUSTES
            }
            !tiene(Manifest.permission.RECORD_AUDIO) -> {
                iniciarAlConceder = true
                pedirBasicos()
            }
            else -> AsistenteService.iniciar(this)
        }
    }

    /** Lo que falta para que todo funcione, en el orden en que conviene resolverlo. */
    private fun pendientes(acciones: Acciones, gafasRegistrada: Boolean, camaraPermitida: Boolean, sdkListo: Boolean): List<Pendiente> = buildList {
        if (ajustes.apiKey.isBlank()) {
            add(Pendiente("Pon tu llave de Claude", "Sin ella no hay con quién hablar.", "Ajustes") { acciones.irA(Pestana.AJUSTES) })
        }
        if (PERMISOS_BASICOS.any { !tiene(it) }) {
            add(Pendiente("Permisos básicos", "Micrófono, Bluetooth y notificaciones.", "Permitir") { pedirBasicos() })
        }
        val camara = catalogo.capacidades.first { it.id == "camara" }
        if (catalogo.activa(camara) && sdkListo) {
            if (!gafasRegistrada) {
                add(Pendiente("Conecta la cámara", "Enlaza esta app con Meta AI (modo desarrollador).", "Conectar", acciones.conectarMeta))
            } else if (!camaraPermitida) {
                add(Pendiente("Permite la cámara", "Un toque en Meta AI y Claude podrá ver.", "Permitir", acciones.permitirCamara))
            }
        }
        val calendario = catalogo.capacidades.first { it.id == "calendario" }
        if (catalogo.activa(calendario)) {
            val faltan = catalogo.permisosFaltantes(calendario)
            if (faltan.isNotEmpty()) {
                add(Pendiente("Permiso de calendario", "Para consultar tu agenda y crear eventos.", "Permitir") { acciones.pedirPermisos(faltan) })
            }
        }
    }

    @Composable
    private fun Aplicacion(acciones: Acciones) {
        val colores = MaterialTheme.colorScheme
        val actual by pestana
        val marca by refresco
        val version by ajustes.version.collectAsState()
        val gafas by Estado.gafas.collectAsState()
        val avisos = remember { SnackbarHostState() }
        LaunchedEffect(Unit) { Estado.avisos.collect { avisos.showSnackbar(it) } }

        val pendientes = remember(marca, version, gafas) {
            pendientes(acciones, gafas.registrada, gafas.camaraPermitida, gafas.sdkListo)
        }

        Box(Modifier.fillMaxSize().background(colores.background)) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (actual) {
                        Pestana.INICIO -> PantallaInicio(acciones, pendientes, remember(version) { ajustes.modoParcial })
                        Pestana.CHAT -> PantallaChat()
                        Pestana.CAPACIDADES -> PantallaCapacidades(catalogo, ajustes, acciones, marca)
                        Pestana.AJUSTES -> PantallaAjustes(ajustes)
                    }
                    SnackbarHost(avisos, Modifier.align(Alignment.BottomCenter))
                }
                BarraInferior(actual) { pestana.value = it }
            }
        }
    }

    @Composable
    private fun BarraInferior(actual: Pestana, alElegir: (Pestana) -> Unit) {
        val colores = MaterialTheme.colorScheme
        Row(Modifier.fillMaxWidth().background(colores.surfaceContainer).padding(vertical = 8.dp)) {
            Pestana.entries.forEach { pestana ->
                val elegida = pestana == actual
                val tinta = if (elegida) colores.primary else colores.onSurfaceVariant
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { alElegir(pestana) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icono(pestana.glifo, tinta)
                    Text(
                        pestana.titulo,
                        modifier = Modifier.padding(top = 3.dp),
                        color = tinta,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (elegida) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }

    private companion object {
        val PERMISOS_BASICOS = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}
