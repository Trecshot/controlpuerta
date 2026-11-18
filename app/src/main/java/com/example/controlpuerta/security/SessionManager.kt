package com.example.controlpuerta.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.example.controlpuerta.model.Usuario
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONException
import org.json.JSONObject

/**
 * Almacena la sesión vigente cifrada con AES-256-GCM respaldada por Android Keystore.
 * Nunca guarda contraseñas ni hashes en el dispositivo.
 */
class SessionManager(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "ControlPuertaIoT"
        private const val PREFS_NAME = "iot_secure_session"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "control_puerta_session_aes_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128

        private const val KEY_PAYLOAD = "encrypted_session"
        private const val KEY_IV = "encrypted_session_iv"
        private const val JSON_USERNAME = "saved_username"
        private const val JSON_ROL = "saved_rol"
        private const val JSON_EXPIRES_AT = "expires_at"

        const val SESSION_TTL_MS: Long = SessionPolicy.SESSION_TTL_MS
    }

    fun guardarSesion(usuario: Usuario, ttlMillis: Long = SESSION_TTL_MS) {
        val expiresAt = SessionPolicy.calculateExpiresAt(System.currentTimeMillis(), ttlMillis)
        val json = JSONObject().apply {
            put(JSON_USERNAME, usuario.username)
            put(JSON_ROL, usuario.rol)
            put(JSON_EXPIRES_AT, expiresAt)
        }.toString()

        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val encryptedBytes = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
            val ivBase64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val payloadBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)

            prefs.edit {
                putString(KEY_IV, ivBase64)
                putString(KEY_PAYLOAD, payloadBase64)
            }
            Log.i(TAG, "SESSION_SAVED: Sesión persistente cifrada guardada para '${usuario.username}' (TTL 12h).")
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "SESSION_ENCRYPT_ERROR: No se pudo cifrar la sesión: ${e.message}", e)
            cerrarSesion()
        }
    }

    fun obtenerSesionVigente(): StoredSession? {
        val ivBase64 = prefs.getString(KEY_IV, null) ?: return null
        val payloadBase64 = prefs.getString(KEY_PAYLOAD, null) ?: return null

        return try {
            val secretKey = getExistingSecretKey()
            if (secretKey == null) {
                Log.w(TAG, "SESSION_KEY_MISSING: Llave de Keystore ausente; limpiando sesión local.")
                cerrarSesion()
                return null
            }

            val ivBytes = Base64.decode(ivBase64, Base64.NO_WRAP)
            val encryptedBytes = Base64.decode(payloadBase64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, ivBytes))
            val plainJson = String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)

            val json = JSONObject(plainJson)
            val valid = SessionPolicy.validateStoredSession(
                username = json.optString(JSON_USERNAME, ""),
                rol = json.optString(JSON_ROL, ""),
                expiresAtMillis = json.optLong(JSON_EXPIRES_AT, 0L),
                nowMillis = System.currentTimeMillis()
            )
            if (valid == null) {
                Log.i(TAG, "SESSION_EXPIRED: Sesión expirada o inválida; cerrando sesión local.")
                cerrarSesion()
            }
            valid
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "SESSION_DECRYPT_WARN: No se pudo descifrar sesión guardada: ${e.message}", e)
            cerrarSesion()
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "SESSION_FORMAT_WARN: Sesión local corrupta: ${e.message}", e)
            cerrarSesion()
            null
        } catch (e: JSONException) {
            Log.w(TAG, "SESSION_JSON_WARN: Payload de sesión inválido: ${e.message}", e)
            cerrarSesion()
            null
        }
    }

    fun cerrarSesion() {
        prefs.edit { clear() }
    }

    private fun getExistingSecretKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun getOrCreateSecretKey(): SecretKey {
        getExistingSecretKey()?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
