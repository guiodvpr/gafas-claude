package dev.david.gafas.nucleo

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * LA CAJA FUERTE. Cifra tus llaves (Claude, transcripción, tokens MCP) con una clave AES
 * que vive dentro del Android Keystore: el chip seguro del celular. La clave nunca sale de
 * ahí, así que aunque alguien copie los archivos de la app, las llaves quedan ilegibles.
 */
object Boveda {
    private const val ALIAS = "gafas_claude_boveda"
    private const val TRANSFORMACION = "AES/GCM/NoPadding"
    private const val BYTES_IV = 12

    private fun clave(): SecretKey {
        val almacen = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (almacen.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generador.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generador.generateKey()
    }

    /** Devuelve iv + texto cifrado en Base64, o null si el Keystore falla. */
    fun cifrar(texto: String): String? = runCatching {
        val cifrador = Cipher.getInstance(TRANSFORMACION).apply { init(Cipher.ENCRYPT_MODE, clave()) }
        val cifrado = cifrador.doFinal(texto.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cifrador.iv + cifrado, Base64.NO_WRAP)
    }.getOrNull()

    fun descifrar(guardado: String): String? = runCatching {
        val bytes = Base64.decode(guardado, Base64.NO_WRAP)
        val cifrador = Cipher.getInstance(TRANSFORMACION)
        cifrador.init(Cipher.DECRYPT_MODE, clave(), GCMParameterSpec(128, bytes, 0, BYTES_IV))
        String(cifrador.doFinal(bytes, BYTES_IV, bytes.size - BYTES_IV), Charsets.UTF_8)
    }.getOrNull()
}
