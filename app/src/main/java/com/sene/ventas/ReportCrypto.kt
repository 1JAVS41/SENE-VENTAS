package com.sene.ventas

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Typeface
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Protocolo compartido SENE VENTAS <-> SENE ADMIN, versión 1.
 * Formato: SENE-ADMIN-REPORT|1 + ID, WORKER, DATE, TIME, NUMBER, TYPE y KEY_ID,
 * seguidos por SALT, IV y DATA en Base64 URL-safe. Los metadatos forman parte
 * del AAD de AES-256-GCM; cualquier cambio invalida la autenticación.
 * DATA contiene el reporte completo en UTF-8. La clave se deriva con PBKDF2-HMAC-SHA256.
 */
object ReportCrypto {
    private const val PREFS = "sene_report_crypto_v1"
    private const val KEY_BLOB = "current_key_wrapped"
    private const val WRAP_ALIAS = "SENE_REPORT_KEY_WRAP_V1"
    private const val ITERATIONS = 210000
    private const val IV_SIZE = 12

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(WRAP_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun wrap(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private fun unwrap(value: String): String {
        val packed = Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE)
        require(packed.size > IV_SIZE + 16) { "La clave guardada está dañada" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, packed.copyOfRange(0, IV_SIZE)))
        return cipher.doFinal(packed.copyOfRange(IV_SIZE, packed.size)).toString(Charsets.UTF_8)
    }

    fun hasKey(c: Context): Boolean = ReportPublicKeyStore.hasKey(c)

    @Synchronized fun saveKey(c: Context, value: String): Boolean {
        val clean = value.trim()
        if (clean.length < 8 || clean.length > 256) return false
        return try {
            val p = prefs(c)
            val oldBlob = p.getString(KEY_BLOB, null)
            val history = p.getStringSet("historical_keys_wrapped", emptySet())?.toMutableSet() ?: mutableSetOf()
            val oldValue = if (oldBlob.isNullOrBlank()) null else try { unwrap(oldBlob) } catch (_: Exception) { null }
            if (!oldBlob.isNullOrBlank() && oldValue != null && oldValue != clean) history.add(oldBlob)
            p.edit().putString(KEY_BLOB, wrap(clean))
                .putStringSet("historical_keys_wrapped", history).putBoolean("configured", true).commit()
        } catch (_: Exception) { false }
    }

    fun newKey(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun ownerKey(c: Context): String {
        val blob = prefs(c).getString(KEY_BLOB, null) ?: throw IllegalStateException("Falta configurar la clave de cifrado")
        return unwrap(blob)
    }

    private fun derive(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }

    private fun b64(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun keyId(passphrase: String): String =
        MessageDigest.getInstance("SHA-256").digest(passphrase.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }

    /** Reutiliza el mismo ID, número y mensaje cifrado cuando se reintenta el mismo evento. */
    @Synchronized fun prepare(c: Context, workerId: String, type: String, body: String, eventKey: String): String {
        val p = prefs(c)
        val cacheKey = "pending_" + eventKey
        p.getString(cacheKey, null)?.let {
            val instruction = "*COPIA Y PEGA en SeneAdmin*\n"
            return if (it.startsWith(instruction)) it else instruction + it
        }

        // Los reportes nuevos solo usan la clave pública entregada por SERVER-SENE
        // durante la vinculación autenticada. Nunca solicitar ni depender de una clave manual.
        if (!ReportPublicKeyStore.hasKey(c)) {
            throw IllegalStateException("Vincula SENE VENTAS con SERVER-SENE para habilitar el cifrado automático de reportes.")
        }
        return ReportEnvelopeCrypto.prepare(c, workerId, type, body, eventKey)
    }

    @Synchronized fun markSent(c: Context, eventKey: String) {
        prefs(c).edit().remove("pending_" + eventKey).commit()
        ReportEnvelopeCrypto.markSent(c, eventKey)
    }
}

/** Pantallas para registrar la misma clave que después solicitará SENE ADMIN. */
object ReportKeySetup {
    /** El cifrado se configura automáticamente al vincular el negocio con SERVER-SENE. */
    fun ensureConfigured(activity: Activity, onComplete: () -> Unit) {
        if (!activity.isFinishing && !activity.isDestroyed) onComplete()
    }

    /** La clave de cifrado ya no es una configuración manual del usuario. */
    fun change(activity: Activity, onSaved: () -> Unit = {}) {
        android.widget.Toast.makeText(
            activity,
            "El cifrado de reportes se administra automáticamente al vincular con SERVER-SENE.",
            android.widget.Toast.LENGTH_LONG
        ).show()
        onSaved()
    }
}
