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

    fun hasKey(c: Context): Boolean = try { ownerKey(c).length >= 8 } catch (_: Exception) { false }

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
        p.getString(cacheKey, null)?.let { return it }

        val passphrase = ownerKey(c)
        val reportId = UUID.randomUUID().toString()
        // El consecutivo se conserva por trabajador en el dispositivo; el UUID evita colisiones incluso entre dispositivos.
        val sequenceKey = "sequence_" + workerId
        val reportNumber = p.getLong(sequenceKey, 0L) + 1L
        if (!p.edit().putLong(sequenceKey, reportNumber).commit())
            throw IllegalStateException("No se pudo reservar el número del reporte")

        val now = Date()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(now)
        val safeWorker = b64(workerId.toByteArray(Charsets.UTF_8))
        val header = listOf("SENE-ADMIN-REPORT|1", "ID=$reportId", "WORKER=$safeWorker",
            "DATE=$date", "TIME=$time", "NUMBER=$reportNumber", "TYPE=$type",
            "KEY_ID=" + keyId(passphrase)).joinToString("\n")

        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(passphrase, salt))
        cipher.updateAAD(header.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(body.toByteArray(Charsets.UTF_8))
        val message = "*COPIA Y PEGA en SeneAdmin*\n" + header + "\nSALT=" + b64(salt) + "\nIV=" + b64(cipher.iv) + "\nDATA=" + b64(encrypted)
        if (!p.edit().putString(cacheKey, message).commit())
            throw IllegalStateException("No se pudo guardar el identificador del reporte")
        return message
    }

    @Synchronized fun markSent(c: Context, eventKey: String) {
        prefs(c).edit().remove("pending_" + eventKey).commit()
    }
}

/** Pantallas para registrar la misma clave que después solicitará SENE ADMIN. */
object ReportKeySetup {
    fun ensureConfigured(activity: Activity, onComplete: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (ReportCrypto.hasKey(activity)) { onComplete(); return }
        chooseKey(activity, onComplete, false)
    }

    fun change(activity: Activity, onSaved: () -> Unit = {}) {
        chooseKey(activity, onSaved, true)
    }

    private fun chooseKey(activity: Activity, onSaved: () -> Unit, changing: Boolean) {
        val message = "CLAVE DE CIFRADO\nCONSERVA TU CLAVE, NO LA OLVIDES.\nSerá la clave que utilizarás para SENE ADMIN.\n\n¿Ya eres cliente SENE?\nSi ya tienes una clave de cifrado, introduce tu clave.\nSi no la tienes, genera una nueva."
        AlertDialog.Builder(activity)
            .setTitle(if (changing) "CAMBIAR CLAVE DE CIFRADO" else "CLAVE DE CIFRADO")
            .setMessage(message)
            .setPositiveButton("YA TENGO UNA CLAVE") { _, _ -> enterExisting(activity, onSaved, changing) }
            .setNeutralButton("GENERAR UNA NUEVA") { _, _ -> generateAndConfirm(activity, onSaved, changing) }
            .setNegativeButton(if (changing) "CANCELAR" else "SALIR") { _, _ -> if (!changing) activity.finish() }
            .setCancelable(changing).show()
    }

    private fun field(activity: Activity, hint: String, password: Boolean = false) =
        EditText(activity).apply {
            this.hint = hint; textSize = 15f; setSingleLine(true); setPadding(28, 24, 28, 24)
            if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

    private fun enterExisting(activity: Activity, onSaved: () -> Unit, changing: Boolean) {
        val key = field(activity, "Introduce tu clave de cifrado", true)
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 4); addView(key) }
        val dialog = AlertDialog.Builder(activity).setTitle("YA TENGO UNA CLAVE SENE")
            .setMessage("Introduce la clave que utilizarás también en SENE ADMIN.")
            .setView(box).setPositiveButton("GUARDAR CLAVE", null)
            .setNegativeButton("CANCELAR") { _, _ -> chooseKey(activity, onSaved, changing) }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = key.text.toString().trim()
                if (value.length < 8) { key.error = "La clave debe tener al menos 8 caracteres"; return@setOnClickListener }
                if (!ReportCrypto.saveKey(activity, value)) { key.error = "No se pudo guardar la clave"; return@setOnClickListener }
                dialog.dismiss()
                AlertDialog.Builder(activity).setTitle("CLAVE GUARDADA")
                    .setMessage("SENE VENTAS utilizará esta misma clave para preparar los reportes cifrados de SENE ADMIN.")
                    .setPositiveButton("CONTINUAR") { _, _ -> onSaved() }.setCancelable(false).show()
            }
        }
        dialog.show()
    }

    private fun generateAndConfirm(activity: Activity, onSaved: () -> Unit, changing: Boolean) {
        val generated = ReportCrypto.newKey()
        val confirm = field(activity, "Vuelve a escribir la clave para confirmarla", true)
        val shown = TextView(activity).apply {
            text = generated; textSize = 14f; typeface = Typeface.create("monospace", Typeface.BOLD)
            setTextIsSelectable(true); setPadding(20, 20, 20, 20)
        }
        val copy = android.widget.Button(activity).apply {
            text = "COPIAR CLAVE"; isAllCaps = false
            setOnClickListener {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Clave SENE", generated))
                android.widget.Toast.makeText(activity, "Clave copiada. Guárdala en un lugar seguro.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 4)
            addView(TextView(activity).apply {
                text = "Esta clave será necesaria para SENE ADMIN. Cópiala y consérvala. No podremos recuperarla si la pierdes."
                textSize = 14f
            })
            addView(shown); addView(copy); addView(confirm)
        }
        val dialog = AlertDialog.Builder(activity).setTitle("TU NUEVA CLAVE SENE")
            .setView(box).setPositiveButton("CONFIRMAR Y GUARDAR", null)
            .setNegativeButton("CANCELAR") { _, _ -> chooseKey(activity, onSaved, changing) }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (confirm.text.toString().trim() != generated) {
                    confirm.error = "La clave no coincide. Escríbela exactamente."; return@setOnClickListener
                }
                if (!ReportCrypto.saveKey(activity, generated)) {
                    confirm.error = "No se pudo guardar la clave"; return@setOnClickListener
                }
                dialog.dismiss()
                AlertDialog.Builder(activity).setTitle("CLAVE GUARDADA")
                    .setMessage("Conserva tu clave. Es la misma que deberás introducir en SENE ADMIN.")
                    .setPositiveButton("CONTINUAR") { _, _ -> onSaved() }.setCancelable(false).show()
            }
        }
        dialog.show()
    }
}
