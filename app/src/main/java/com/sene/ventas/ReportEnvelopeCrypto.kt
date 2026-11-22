package com.sene.ventas

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** Clave pública del patrón, recibida del servidor y usada solo para cifrar reportes. */
object ReportPublicKeyStore {
    private const val PREFS = "sene_report_public_key_v2"
    private const val VALUE = "rsa_public_key"

    fun saveFromServer(c: Context, encoded: String): Boolean {
        if (encoded.isBlank()) return false
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP or Base64.URL_SAFE)
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))
            require(key.algorithm.equals("RSA", true))
            c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(VALUE, encoded).commit()
        } catch (_: Exception) { false }
    }

    fun encoded(c: Context): String? =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(VALUE, null)

    fun hasKey(c: Context): Boolean = !encoded(c).isNullOrBlank()

    fun publicKey(c: Context) = KeyFactory.getInstance("RSA").generatePublic(
        X509EncodedKeySpec(Base64.decode(encoded(c) ?: throw IllegalStateException("Falta la clave pública del patrón"),
            Base64.NO_WRAP or Base64.URL_SAFE)))
}

/** Cifrado E2E: clave aleatoria AES-256-GCM por reporte, envuelta con RSA-OAEP del patrón. */
object ReportEnvelopeCrypto {
    private const val PREFS = "sene_report_crypto_v2"
    private const val ITERATIONS_UNUSED = 0
    private val oaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)

    @Synchronized fun prepare(c: Context, workerId: String, type: String, body: String, eventKey: String): String {
        val p = prefs(c)
        val cacheKey = "pending_" + eventKey
        p.getString(cacheKey, null)?.let { return it }
        val publicKey = ReportPublicKeyStore.publicKey(c)
        val reportId = UUID.randomUUID().toString()
        val sequenceKey = "sequence_" + workerId
        val number = p.getLong(sequenceKey, 0L) + 1L
        check(p.edit().putLong(sequenceKey, number).commit()) { "No se pudo reservar el número del reporte" }
        val now = Date()
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val time = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(now)
        val header = listOf("SENE-ADMIN-REPORT|2", "ID=" + reportId,
            "WORKER=" + b64(workerId.toByteArray(Charsets.UTF_8)), "DATE=" + date,
            "TIME=" + time, "NUMBER=" + number, "TYPE=" + type,
            "ALG=RSA-OAEP-SHA256+A256GCM").joinToString("\n")
        val contentKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val aes = Cipher.getInstance("AES/GCM/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(128, iv))
        aes.updateAAD(header.toByteArray(Charsets.UTF_8))
        val encrypted = aes.doFinal(body.toByteArray(Charsets.UTF_8))
        val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        rsa.init(Cipher.ENCRYPT_MODE, publicKey, oaep)
        val wrapped = rsa.doFinal(contentKey)
        val envelope = header + "\nWRAPPED_KEY=" + b64(wrapped) + "\nIV=" + b64(iv) + "\nDATA=" + b64(encrypted)
        check(p.edit().putString(cacheKey, envelope).commit()) { "No se pudo guardar el reporte cifrado" }
        return envelope
    }

    @Synchronized fun markSent(c: Context, eventKey: String) {
        prefs(c).edit().remove("pending_" + eventKey).commit()
    }
}
