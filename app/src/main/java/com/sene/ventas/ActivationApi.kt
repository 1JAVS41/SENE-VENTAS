package com.sene.ventas

import android.content.Context
import org.json.JSONObject
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object ActivationApi {
    const val BASE_URL = "https://server-sene-api.nagavs28.workers.dev"
    private const val PREFS = "sene_server_link"
    private const val KEY_ALIAS = "SENE_SERVER_INSTALLATION_ID"
    private const val IV_SIZE = 12

    private fun identityKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun encryptIdentity(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, identityKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decryptIdentity(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        if (bytes.size <= IV_SIZE) throw IllegalStateException("Identificador cifrado inválido")
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, identityKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)), Charsets.UTF_8)
    }

    @Synchronized
    fun installationId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = prefs.getString("installation_id_enc", null)
        if (!encrypted.isNullOrBlank()) {
            try { return decryptIdentity(encrypted) } catch (_: Exception) { }
        }
        val legacy = prefs.getString("installation_id", null)
        val created = legacy?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val protected = encryptIdentity(created)
        prefs.edit().remove("installation_id").putString("installation_id_enc", protected).apply()
        return created
    }

    fun post(path: String, payload: JSONObject): JSONObject {
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 12000
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val result = if (body.isBlank()) JSONObject() else JSONObject(body)
            result.put("_http_status", status)
            return result
        } finally {
            connection.disconnect()
        }
    }

    fun subscriptionStatus(context: Context): JSONObject {
        val result = post("/subscription/status", JSONObject().put("installation_id", installationId(context)))
        val expiry = result.optString("expires_at")
        if (expiry.isNotBlank()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("saved_license_expires_at", expiry)
                .putString("saved_license_business_name", result.optString("business_name"))
                .apply()
        }
        return result
    }

    fun createRequest(
        context: Context,
        type: String,
        holder: String,
        whatsapp: String,
        business: String
    ): JSONObject {
        return post("/requests", JSONObject()
            .put("type", type)
            .put("holder_name", holder.trim())
            .put("whatsapp", whatsapp.filter { it.isDigit() })
            .put("business_name", business.trim())
            .put("installation_id", installationId(context)))
    }

    fun redeem(context: Context, folio: String, code: String): JSONObject {
        return post("/redeem", JSONObject()
            .put("folio", folio.trim())
            .put("code", code.trim().uppercase())
            .put("installation_id", installationId(context)))
    }

    fun requestAccountRecovery(context: Context, business: String, whatsapp: String): JSONObject {
        return post("/account/recovery/request", JSONObject()
            .put("business_name", business.trim())
            .put("whatsapp", whatsapp.filter { it.isDigit() })
            .put("installation_id", installationId(context)))
    }

    fun completeAccountRecovery(context: Context, folio: String, code: String): JSONObject {
        return post("/account/recovery/complete", JSONObject()
            .put("folio", folio.trim().uppercase())
            .put("code", code.trim().uppercase())
            .put("installation_id", installationId(context)))
    }

    fun savePendingRecovery(context: Context, folio: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("pending_recovery_folio", folio.trim().uppercase()).apply()
    }

    fun pendingRecoveryFolio(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pending_recovery_folio", "").orEmpty()

    fun clearPendingRecovery(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("pending_recovery_folio").apply()
    }

    fun savePending(context: Context, folio: String, amountCents: Int, expiresAt: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("pending_folio", folio)
            .putInt("pending_amount_cents", amountCents)
            .putString("pending_expires_at", expiresAt)
            .apply()
    }

    fun pendingFolio(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pending_folio", "").orEmpty()

    fun pendingAmount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("pending_amount_cents", 10000)

    fun pendingExpiresAt(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pending_expires_at", "").orEmpty()

    fun saveActivationCredential(context: Context, folio: String, code: String, expiresAt: String) {
        val encryptedCode = encryptIdentity(code.trim().uppercase())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("saved_activation_folio", folio.trim().uppercase())
            .putString("saved_activation_code_enc", encryptedCode)
            .putString("saved_license_expires_at", expiresAt)
            .apply()
    }

    fun savedActivationCode(context: Context): String {
        val encrypted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("saved_activation_code_enc", null) ?: return ""
        return try { decryptIdentity(encrypted) } catch (_: Exception) { "" }
    }

    fun savedActivationFolio(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("saved_activation_folio", "").orEmpty()

    fun savedLicenseExpiry(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("saved_license_expires_at", "").orEmpty()

    fun savedLicenseBusiness(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("saved_license_business_name", "").orEmpty()

    fun clearPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("pending_folio").remove("pending_amount_cents").remove("pending_expires_at").apply()
    }
}
