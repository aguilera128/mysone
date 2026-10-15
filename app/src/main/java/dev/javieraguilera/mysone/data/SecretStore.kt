package dev.javieraguilera.mysone.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.secretsStore by preferencesDataStore("secrets")
private val API_KEY = stringPreferencesKey("llm_api_key")

/**
 * Clave del proveedor de IA: AES-256-GCM con clave no exportable en Android Keystore y el blob
 * Base64(IV‖cifrado) en DataStore. Sin autenticación de usuario: el servicio la lee con la pantalla
 * bloqueada. La clave nunca va a logs, Sentry ni exportaciones.
 */
class SecretStore(private val context: Context) {

    val hasApiKey: Flow<Boolean> = context.secretsStore.data.map { it[API_KEY] != null }.distinctUntilChanged()

    suspend fun setApiKey(key: String) {
        context.secretsStore.edit { it[API_KEY] = encrypt(key) }
    }

    suspend fun clearApiKey() {
        context.secretsStore.edit { it.remove(API_KEY) }
    }

    /** null si no hay clave o si ya no se puede descifrar (restauración, credenciales borradas): se borra y hay que pedirla. */
    suspend fun apiKey(): String? {
        val blob = context.secretsStore.data.first()[API_KEY] ?: return null
        return try {
            decrypt(blob)
        } catch (_: Exception) {
            clearApiKey()
            null
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain.encodeToByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val b = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, IV_LEN))
        return c.doFinal(b, IV_LEN, b.size - IV_LEN).decodeToString()
    }

    private companion object {
        const val ALIAS = "llm_api_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
