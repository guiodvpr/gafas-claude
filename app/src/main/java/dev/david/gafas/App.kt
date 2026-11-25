package dev.david.gafas

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import dev.david.gafas.datos.Historial
import dev.david.gafas.datos.Memoria
import dev.david.gafas.datos.Notas
import dev.david.gafas.datos.Recordatorios
import dev.david.gafas.gafas.Gafas

/** Lo primero que corre al abrir el proceso: carga lo guardado y, si ya hay permiso, enlaza con las gafas. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Historial.iniciar(this)
        Memoria.iniciar(this)
        Notas.iniciar(this)
        Recordatorios.iniciar(this)
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            Gafas.inicializar(this)
        }
    }
}
