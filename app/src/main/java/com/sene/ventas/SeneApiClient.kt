package com.sene.ventas

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

private fun isStrictIsoDate(value: String): Boolean {
    if (!value.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) return false
    return try {
        val year = value.substring(0, 4).toInt()
        val month = value.substring(5, 7).toInt()
        val day = value.substring(8, 10).toInt()
        if (year !in 1..9999) return false
        java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"), java.util.Locale.ROOT).apply {
            isLenient = false
            clear()
            set(year, month - 1, day)
        }.timeInMillis
        true
    } catch (_: Exception) {
        false
    }
}

class SeneVentasApiException(val httpStatus: Int, val responseBody: String) :
    IOException("SENE API HTTP $httpStatus: $responseBody")

/**
 * Cliente HTTPS firmado para operaciones remotas SENE VENTAS.
 * Ventas, inventario, caja y gastos no se envían mediante esta clase.
 * Ejecutar las llamadas en un hilo de trabajo, nunca en el hilo principal.
 */
class SeneApiClient(context: Context, baseUrl: String) {
    private val app = context.applicationContext
    private val root = baseUrl.trim().trimEnd('/')
    private val prefs = app.getSharedPreferences("sene_ventas_server", Context.MODE_PRIVATE)

    init {
        require(root.startsWith("https://")) { "La dirección del servidor SENE debe usar HTTPS" }
    }

    fun storedDeviceId(): String? = prefs.getString("device_id", null)?.takeIf { it.isNotBlank() }

    fun storedSlot(): String? = prefs.getString("device_slot", null)
        ?.takeIf { it == "VENTAS_1" || it == "VENTAS_2" }

    fun pair(qrToken: String): JSONObject {
        val scanned = qrToken.trim()
        val token = when {
            scanned.startsWith("SENEPAIR2:", ignoreCase = true) -> scanned.substringAfter(':')
            scanned.startsWith("SENEPAIR1:", ignoreCase = true) -> scanned.substringAfter(':')
            else -> scanned
        }
        require(token.matches(Regex("(?i)^[0-9a-f]{64}$"))) {
            "El QR de SENE PATRÓN no es válido"
        }
        val identity = DeviceIdentity.get(app)
        val body = JSONObject()
            .put("qrToken", token)
            .put("installationId", identity.installationId)
            .put("continuityId", identity.continuityId)
            .put("publicKeyBase64", identity.publicKeyBase64)
            .put("fingerprint", identity.fingerprint)
        val result = bootstrapSigned("VENTAS_PAIR", "/v1/ventas/pair", body.toString(), identity)
        check(result.optBoolean("sameDeviceId", false)) {
            "El servidor no confirmó que se conserva la misma identidad del dispositivo"
        }
        val id = result.optString("deviceId")
        val slot = result.optString("slot")
        check(id.isNotBlank() && (slot == "VENTAS_1" || slot == "VENTAS_2")) {
            "El servidor no devolvió una vinculación válida"
        }
        // Confirma el rol y el espacio mediante una segunda solicitud firmada antes de guardar la vinculación.
        val verifiedSession = session(id)
        check(verifiedSession.optString("role") == "VENTAS") {
            "El servidor no confirmó el rol SENE VENTAS para este equipo"
        }
        val verifiedSlot = verifiedSession.optString("slot")
        check(verifiedSlot.isBlank() || verifiedSlot == slot) {
            "La sesión devolvió un espacio distinto al que se vinculó"
        }
        check(prefs.edit()
            .putString("device_id", id)
            .putString("device_slot", slot)
            .putString("paired_business", result.optString("businessName"))
            .commit()) {
            "No se pudo guardar la vinculación de SENE VENTAS"
        }
        return result
    }

    /**
     * Recupera el espacio VENTAS original después de reinstalar en el mismo teléfono.
     * El Worker valida continuidad física, empresa y datos registrados; no crea una licencia
     * ni consume un espacio adicional.
     */
    fun restoreSamePhone(
        businessName: String,
        registeredEmail: String,
        registeredPhone: String
    ): JSONObject {
        val name = businessName.trim()
        val email = registeredEmail.trim().lowercase(Locale.ROOT)
        val phone = registeredPhone.filter { it.isDigit() }
        require(name.isNotBlank() && name.length <= 100 &&
            android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() &&
            phone.length in 10..15) {
            "Revisa el nombre, correo y teléfono registrados de la empresa"
        }
        val identity = DeviceIdentity.get(app)
        val body = JSONObject()
            .put("businessName", name)
            .put("registeredEmail", email)
            .put("registeredPhone", phone)
            .put("installationId", identity.installationId)
            .put("continuityId", identity.continuityId)
            .put("publicKeyBase64", identity.publicKeyBase64)
            .put("fingerprint", identity.fingerprint)
        val result = bootstrapSigned("VENTAS_RESTORE", "/v1/ventas/restore", body.toString(), identity)
        val id = result.optString("deviceId")
        val slot = result.optString("slot")
        check(id.isNotBlank() && (slot == "VENTAS_1" || slot == "VENTAS_2")) {
            "El servidor no devolvió una restauración válida"
        }
        // No persistir la recuperación hasta comprobar una sesión firmada del espacio recuperado.
        val verifiedSession = session(id)
        check(verifiedSession.optString("role") == "VENTAS") {
            "El servidor restauró el espacio, pero no confirmó el rol SENE VENTAS"
        }
        val verifiedSlot = verifiedSession.optString("slot")
        check(verifiedSlot.isBlank() || verifiedSlot == slot) {
            "La sesión devolvió un espacio distinto al que se restauró"
        }
        check(prefs.edit()
            .putString("device_id", id)
            .putString("device_slot", slot)
            .putString("paired_business", result.optString("businessName"))
            .commit()) {
            "No se pudo guardar la vinculación restaurada"
        }
        return result
    }

    fun session(deviceId: String = storedDeviceId()
        ?: throw IllegalStateException("SENE VENTAS todavía no está vinculado")): JSONObject {
        val result = deviceSigned("DEVICE_AUTH", "/v1/device/session", "{}", deviceId)
        check(result.optString("role") == "VENTAS") {
            "La licencia no reconoce este equipo como SENE VENTAS"
        }
        val slot = result.optString("slot")
        if (slot == "VENTAS_1" || slot == "VENTAS_2") {
            check(prefs.edit().putString("device_slot", slot).commit()) {
                "No se pudo guardar el equipo asignado"
            }
        }
        return result
    }

    fun submitWorkRequest(
        type: String,
        details: JSONObject,
        idempotencyKey: String,
        deviceId: String = storedDeviceId()
            ?: throw IllegalStateException("SENE VENTAS todavía no está vinculado")
    ): JSONObject {
        require(idempotencyKey.matches(Regex("(?i)^[0-9a-f-]{36}$"))) {
            "La solicitud necesita una clave de idempotencia UUID guardada localmente"
        }
        val body = JSONObject()
            .put("type", type)
            .put("details", details)
            .put("idempotencyKey", idempotencyKey)
        return deviceSigned("VENTAS_REQUEST", "/v1/ventas/requests", body.toString(), deviceId)
    }

    /** Recupera el historial completo del equipo para reconstruirlo tras reinstalar o perder datos locales. */
    fun requestHistory(
        deviceId: String = storedDeviceId()
            ?: throw IllegalStateException("SENE VENTAS todavía no está vinculado")
    ): JSONObject = deviceSigned(
        "VENTAS_REQUEST_HISTORY", "/v1/ventas/requests/history", "{}", deviceId
    )

    fun requestUpdates(
        after: Long = 0L,
        deviceId: String = storedDeviceId()
            ?: throw IllegalStateException("SENE VENTAS todavía no está vinculado")
    ): JSONObject {
        val body = JSONObject().put("since", after)
        return deviceSigned("VENTAS_REQUEST_UPDATES", "/v1/ventas/requests/updates", body.toString(), deviceId)
    }

    fun sendShiftReport(
        shiftKey: String,
        report: JSONObject,
        deviceId: String = storedDeviceId()
            ?: throw IllegalStateException("SENE VENTAS todavía no está vinculado")
    ): JSONObject {
        val body = JSONObject().put("shiftKey", shiftKey).put("report", report)
        return deviceSigned("VENTAS_SHIFT_REPORT", "/v1/ventas/shift-reports", body.toString(), deviceId)
    }

    fun sendPayrollReport(
        periodKey: String,
        report: JSONObject,
        deviceId: String = storedDeviceId()
            ?: throw IllegalStateException("SENE VENTAS todavía no está vinculado")
    ): JSONObject {
        val body = JSONObject().put("periodKey", periodKey).put("report", report)
        return deviceSigned("VENTAS_PAYROLL_REPORT", "/v1/ventas/payroll-reports", body.toString(), deviceId)
    }

    fun sendDailyInventory(
        inventoryDate: String,
        snapshot: JSONObject,
        deviceId: String = storedDeviceId()
            ?: throw IllegalStateException("SENE VENTAS todavía no está vinculado")
    ): JSONObject {
        require(isStrictIsoDate(inventoryDate)) { "Fecha de inventario no válida" }
        require(snapshot.toString().length <= 32768) { "El inventario supera el tamaño permitido" }
        val body = JSONObject().put("inventoryDate", inventoryDate).put("snapshot", snapshot)
        return deviceSigned("VENTAS_DAILY_INVENTORY", "/v1/ventas/inventory/daily", body.toString(), deviceId)
    }

    fun createCorrectionRequest(
        saleId: String,
        reason: String,
        idempotencyKey: String,
        planHash: String,
        reasons: JSONArray,
        cashImpact: Boolean,
        cashReason: String,
        deviceId: String = storedDeviceId()
            ?: throw IllegalStateException("SENE VENTAS todavía no está vinculado")
    ): JSONObject {
        require(saleId.isNotBlank() && saleId.length <= 120) { "El identificador de la venta no es válido" }
        require(reason.isNotBlank() && reason.length <= 100) { "El motivo no es válido" }
        require(idempotencyKey.matches(Regex("(?i)^[0-9a-f-]{36}$"))) { "La solicitud necesita una clave UUID de idempotencia" }
        require(planHash.matches(Regex("(?i)^[a-f0-9]{64}$"))) { "El plan de corrección no tiene una huella válida" }
        require(reasons.length() in 1..7) { "Selecciona al menos un motivo de corrección" }
        require(if (cashImpact) cashReason.isNotBlank() else cashReason.isBlank()) { "El motivo del ajuste de caja no coincide con el total" }
        val body = JSONObject()
            .put("saleId", saleId)
            .put("reason", reason)
            .put("reasons", reasons)
            .put("planHash", planHash)
            .put("cashImpact", cashImpact)
            .put("cashReason", cashReason)
            .put("idempotencyKey", idempotencyKey)
        return deviceSigned("VENTAS_CORRECTION_CREATE", "/v1/ventas/corrections", body.toString(), deviceId)
    }

    fun consumeCorrectionGrant(
        requestId: String,
        grantId: String,
        saleId: String,
        planHash: String,
        deviceId: String = storedDeviceId()
            ?: throw IllegalStateException("SENE VENTAS todavía no está vinculado")
    ): JSONObject {
        require(planHash.matches(Regex("(?i)^[a-f0-9]{64}$"))) { "Falta la huella del plan autorizado" }
        val body = JSONObject()
            .put("requestId", requestId)
            .put("grantId", grantId)
            .put("saleId", saleId)
            .put("planHash", planHash)
        return deviceSigned("VENTAS_CORRECTION_CONSUME", "/v1/ventas/corrections/consume", body.toString(), deviceId)
    }


    /**
     * Confirma la persistencia local de una entrega remota. El llamador debe
     * guardar el lote completo y persistir ackId antes de invocar este método.
     * Reutilizar el mismo ackId y cuerpo hace seguro el reintento.
     */
    fun acknowledgeDelivery(
        stream: String,
        deliveryId: String,
        payloadSha256: String,
        cursor: Long,
        ackId: String,
        deviceId: String = storedDeviceId()
            ?: throw IllegalStateException("SENE VENTAS todavía no está vinculado")
    ): JSONObject {
        require(stream.matches(Regex("^[A-Z][A-Z0-9_.-]{1,47}$"))) { "Flujo de entrega no válido" }
        require(deliveryId.matches(Regex("^[A-Za-z0-9._:-]{1,120}$"))) { "Identificador de entrega no válido" }
        require(payloadSha256.matches(Regex("(?i)^[a-f0-9]{64}$"))) { "Huella de entrega no válida" }
        require(cursor >= 0L) { "Cursor de entrega no válido" }
        require(ackId.matches(Regex("(?i)^[0-9a-f-]{36}$"))) { "El ACK necesita un UUID persistido" }
        val body = JSONObject()
            .put("stream", stream)
            .put("deliveryId", deliveryId)
            .put("payloadSha256", payloadSha256.lowercase())
            .put("cursor", cursor)
            .put("ackId", ackId.lowercase())
            .put("recipientDeviceId", deviceId)
        return deviceSigned("DELIVERY_ACK", "/v1/deliveries/ack", body.toString(), deviceId)
    }


    /** Guarda el ackId antes de enviarlo; el lote debe estar persistido localmente. */
    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonicalJson(value.get(it))
        }
        is org.json.JSONArray -> (0 until value.length()).joinToString(",", "[", "]") {
            canonicalJson(value.get(it))
        }
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    fun acknowledgePersistedDelivery(stream: String, payload: JSONObject): JSONObject {
        val stablePayload = JSONObject(payload.toString()).apply { remove("serverTime") }
        val payloadHash = sha256(canonicalJson(stablePayload))
        val deliveryId = "$stream:${payloadHash.take(48)}"
        val cursor = payload.optLong("cursor", 0L).coerceAtLeast(0L)
        val key = "delivery_ack_${stream}_${payloadHash}"
        val savedAckId = prefs.getString(key, null)?.takeIf {
            it.matches(Regex("(?i)^[0-9a-f-]{36}$"))
        }
        val ackId = savedAckId ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString(key, it).commit()) { "No se pudo guardar el identificador de ACK" }
        }
        val result = acknowledgeDelivery(stream, deliveryId, payloadHash, cursor, ackId)
        check(result.optBoolean("acknowledged", false)) { "El servidor no confirmó el ACK" }
        check(prefs.edit().remove(key).commit()) { "No se pudo cerrar el recibo local de ACK" }
        return result
    }

    /** Obtiene y verifica criptográficamente los contactos oficiales AYUDA. */
    fun officialSupportContacts(): JSONObject {
        val signed = get("/v1/support/contacts")
        return OfficialSupportVerifier.verifyAndRemember(app, signed)
    }

    private fun get(path: String): JSONObject {
        val connection = (URL(root + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 15000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-SENE-Version", "1")
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw SeneVentasApiException(status, response)
            return JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun bootstrapSigned(
        purpose: String,
        path: String,
        body: String,
        identity: DeviceIdentitySnapshot
    ): JSONObject {
        val challengeBody = JSONObject()
            .put("purpose", purpose)
            .put("installationId", identity.installationId)
            .put("continuityId", identity.continuityId)
            .put("publicKeyBase64", identity.publicKeyBase64)
            .put("fingerprint", identity.fingerprint)
            .put("targetMethod", "POST")
            .put("targetPath", path)
            .put("bodySha256", sha256(body))
        val challenge = post("/v1/challenges", challengeBody.toString())
        val message = decodeSigningMessage(challenge)
        val signature = DeviceIdentity.signChallenge(app, message)
        return post(path, body, challenge.getString("challengeId"), signature, null)
    }

    private fun deviceSigned(purpose: String, path: String, body: String, deviceId: String): JSONObject {
        require(deviceId.isNotBlank()) { "Falta el identificador del equipo" }
        val challengeBody = JSONObject()
            .put("purpose", purpose)
            .put("deviceId", deviceId)
            .put("targetMethod", "POST")
            .put("targetPath", path)
            .put("bodySha256", sha256(body))
        val challenge = post("/v1/challenges", challengeBody.toString())
        val message = decodeSigningMessage(challenge)
        val signature = DeviceIdentity.signChallenge(app, message)
        return post(path, body, challenge.getString("challengeId"), signature, deviceId)
    }

    private fun decodeSigningMessage(challenge: JSONObject): ByteArray =
        Base64.decode(
            challenge.getString("signingMessage"),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )

    private fun post(
        path: String,
        body: String,
        challengeId: String? = null,
        signature: String? = null,
        deviceId: String? = null
    ): JSONObject {
        val connection = (URL(root + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 15000
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-SENE-Version", "1")
            if (challengeId != null) setRequestProperty("X-SENE-Challenge", challengeId)
            if (signature != null) setRequestProperty("X-SENE-Signature", signature)
            if (deviceId != null) setRequestProperty("X-SENE-Device", deviceId)
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw SeneVentasApiException(status, response)
            return if (response.isBlank()) JSONObject() else JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
