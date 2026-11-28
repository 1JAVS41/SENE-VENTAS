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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

object ActivationApi {
    const val BASE_URL = "https://server-sene-api.nagavs28.workers.dev"
    private const val PREFS = "sene_server_link"
    private const val KEY_ALIAS = "SENE_SERVER_INSTALLATION_ID"
    private const val IV_SIZE = 12

    // Ahorro de solicitudes: la fecha de vencimiento guardada vale sin preguntar al servidor.
    // Se vuelve a preguntar solo si no hay fecha, si vence en menos de 2 días,
    // o si pasaron más de 24 horas desde la última consulta.
    const val LICENSE_CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L // 24 horas
    const val LICENSE_NEAR_EXPIRY_MILLIS = 2L * 24L * 60L * 60L * 1000L // 2 días

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
        if (expiry.isNotBlank() || result.has("active")) {
            // Se guarda también cuándo se preguntó, para no volver a preguntar tan seguido.
            // La fecha guardada solo se cambia si el servidor trae una nueva: una respuesta
            // sin fecha nunca debe borrar la fecha que ya estaba guardada.
            val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            if (expiry.isNotBlank()) {
                editor.putString("saved_license_expires_at", expiry)
                editor.putString("saved_license_business_name", result.optString("business_name"))
            }
            editor.putLong("saved_license_checked_at", System.currentTimeMillis())
            editor.apply()
        }
        return result
    }

    // Convierte la fecha de vencimiento del servidor ("2026-12-16T00:00:00.000Z") a milisegundos.
    // Devuelve 0 si está vacía o no se entiende. No toca ninguna fecha: solo la lee.
    fun parseLicenseExpiryMillis(raw: String): Long {
        if (raw.isBlank()) return 0L
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
        for (pattern in patterns) try {
            val parser = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val parsed = parser.parse(raw)
            if (parsed != null) return parsed.time
        } catch (_: Exception) { }
        return 0L
    }

    // Decisión pura (sin teléfono ni red): ¿hay que preguntar al servidor por la licencia?
    // Solo se pregunta si: no hay fecha guardada, la fecha vence en menos de 2 días,
    // nunca se ha preguntado, el reloj del teléfono retrocedió, o pasaron más de 24 horas.
    fun shouldRefreshLicenseStatus(expiryMillis: Long, checkedAtMillis: Long, nowMillis: Long): Boolean {
        if (expiryMillis <= 0L) return true
        if (expiryMillis - nowMillis < LICENSE_NEAR_EXPIRY_MILLIS) return true
        if (checkedAtMillis <= 0L) return true
        if (nowMillis < checkedAtMillis) return true
        return nowMillis - checkedAtMillis > LICENSE_CHECK_INTERVAL_MILLIS
    }

    // Cuándo se preguntó por última vez al servidor (milisegundos). 0 si nunca se preguntó.
    fun savedLicenseCheckedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("saved_license_checked_at", 0L)

    // Estado de licencia armado solo con lo guardado en el teléfono, sin llamar al servidor.
    // Devuelve null si no hay ninguna fecha guardada. La fecha de vencimiento no cambia.
    fun cachedLicenseStatus(context: Context): JSONObject? {
        val expiry = savedLicenseExpiry(context)
        val expiryMillis = parseLicenseExpiryMillis(expiry)
        if (expiry.isBlank() || expiryMillis <= 0L) return null
        val expired = expiryMillis <= System.currentTimeMillis()
        return JSONObject()
            .put("ok", true)
            .put("active", !expired)
            .put("expired", expired)
            .put("expires_at", expiry)
            .put("business_name", savedLicenseBusiness(context))
    }

    // Estado de la licencia ahorrando solicitudes: usa la fecha guardada y solo pregunta
    // al servidor cuando hace falta (ver shouldRefreshLicenseStatus). Si el servidor no
    // responde y hay fecha guardada, se sigue con esa fecha, como hacía la app hasta ahora.
    // Solo lanza excepción si no hay fecha guardada y el servidor tampoco responde.
    fun licenseStatus(context: Context): JSONObject {
        val now = System.currentTimeMillis()
        val expiryMillis = parseLicenseExpiryMillis(savedLicenseExpiry(context))
        if (!shouldRefreshLicenseStatus(expiryMillis, savedLicenseCheckedAt(context), now)) {
            cachedLicenseStatus(context)?.let { return it }
        }
        return try {
            subscriptionStatus(context)
        } catch (e: Exception) {
            cachedLicenseStatus(context) ?: throw e
        }
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

    // Transferencia de licencia desde YA SOY CLIENTE SENE.
    // El endpoint se implementará en SERVER-SENE en la siguiente etapa.
    fun transferAccountDevice(context: Context, folio: String, code: String): JSONObject {
        return post("/account/device/transfer", JSONObject()
            .put("folio", folio.trim().uppercase())
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
            .putLong("saved_license_checked_at", System.currentTimeMillis())
            .apply()
    }

    fun savedActivationCode(context: Context): String {
        val encrypted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("saved_activation_code_enc", null) ?: return ""
        return try { decryptIdentity(encrypted) } catch (_: Exception) { "" }
    }

    fun saveMuseToken(context: Context, token: String) {
        val encryptedToken = encryptIdentity(token.trim())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("saved_muse_token_enc", encryptedToken)
            .apply()
    }

    fun savedMuseToken(context: Context): String {
        val encrypted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("saved_muse_token_enc", null) ?: return ""
        return try { decryptIdentity(encrypted) } catch (_: Exception) { "" }
    }

    fun museEndpoint(context: Context): String =
        "https://sene-guardian-api.nagavs28.workers.dev/v1/muse/${installationId(context)}/mcp"

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
