package com.sene.ventas

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

class SeneVentasApiException(val httpStatus: Int, val responseBody: String) :
    IOException("SENE API HTTP $httpStatus: $responseBody")

/**
 * Cliente HTTPS firmado para vinculación y operaciones de SENE VENTAS.
 * Ejecutar todos los métodos desde un hilo de trabajo, nunca desde el hilo principal.
 * No guarda tokens QR ni claves privadas; solo el ID del equipo asignado por el servidor.
 */
class SeneVentasApiClient(context: Context, baseUrl: String) {
    private val app = context.applicationContext
    private val root = baseUrl.trim().trimEnd('/')
    private val prefs = app.getSharedPreferences("sene_ventas_server", Context.MODE_PRIVATE)

    init {
        require(root.startsWith("https://")) {
            "La dirección del servidor SENE debe usar HTTPS"
        }
    }

    fun storedDeviceId(): String? =
        prefs.getString("device_id", null)?.takeIf { it.isNotBlank() }

    fun storedSlot(): String? =
        prefs.getString("device_slot", null)?.takeIf { it == "VENTAS_1" || it == "VENTAS_2" }

    /** Canjea el QR de un solo uso generado por SENE PATRÓN. */
    fun pairFromQr(qrToken: String): JSONObject {
        val scanned = qrToken.trim()
        // Los QR oficiales actuales usan SENEPAIR2:<token>; también se admite
        // SENEPAIR1 por compatibilidad con códigos antiguos. El Worker recibe solo el token hexadecimal.
        val token = when {
            scanned.startsWith("SENEPAIR2:", ignoreCase = true) -> scanned.substringAfter(':')
            scanned.startsWith("SENEPAIR1:", ignoreCase = true) -> scanned.substringAfter(':')
            else -> scanned
        }
        require(Regex("^[a-fA-F0-9]{64}$").matches(token)) {
            "El QR no contiene un código de vinculación SENE válido"
        }
        val identity = SeneVentasDeviceIdentity.get(app)
        val body = JSONObject()
            .put("qrToken", token)
            .put("installationId", identity.installationId)
            .put("continuityId", identity.continuityId)
            .put("publicKeyBase64", identity.publicKeyBase64)
            .put("fingerprint", identity.fingerprint)

        val result = bootstrapSigned(
            "VENTAS_PAIR", "/v1/ventas/pair", body.toString()
        )
        val deviceId = result.optString("deviceId")
        val slot = result.optString("slot")
        check(deviceId.isNotBlank() && (slot == "VENTAS_1" || slot == "VENTAS_2")) {
            "El servidor no devolvió una vinculación válida"
        }
        check(
            prefs.edit()
                .putString("device_id", deviceId)
                .putString("device_slot", slot)
                .putString("paired_business", result.optString("businessName"))
                .commit()
        ) { "No se pudo guardar la vinculación del equipo" }
        return result
    }

    fun session(): JSONObject {
        val result = deviceSigned("DEVICE_AUTH", "/v1/device/session", "{}")
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

    fun createWorkRequest(
        type: String,
        details: JSONObject,
        idempotencyKey: String = UUID.randomUUID().toString()
    ): JSONObject {
        val allowed = setOf(
            "LOAN", "CONSUMPTION", "ABSENCE_PERMISSION", "LATE_ARRIVAL",
            "SCHEDULE_CHANGE", "VACATION", "INCIDENT", "SHORTAGE",
            "REST_DAY_CHANGE", "SALARY_ADVANCE", "DAMAGED_EQUIPMENT", "SUGGESTION"
        )
        require(type in allowed) { "Tipo de solicitud no válido" }
        require(idempotencyKey.matches(Regex("^[0-9a-fA-F-]{36}$"))) {
            "Clave de operación no válida"
        }
        require(details.toString().length <= 8192) { "La solicitud es demasiado grande" }
        val body = JSONObject()
            .put("type", type)
            .put("details", details)
            .put("idempotencyKey", idempotencyKey)
        return deviceSigned("VENTAS_REQUEST", "/v1/ventas/requests", body.toString())
    }

    /** Historial propio de solicitudes, incluidos pendientes y respuestas del patrón. */
    fun requestHistory(): JSONObject = deviceSigned(
        "VENTAS_REQUEST_HISTORY", "/v1/ventas/requests/history", "{}"
    )

    fun requestUpdates(since: Long = 0L): JSONObject {
        val body = JSONObject().put("since", since.coerceAtLeast(0L))
        return deviceSigned(
            "VENTAS_REQUEST_UPDATES", "/v1/ventas/requests/updates", body.toString()
        )
    }

    fun saveShiftReport(shiftKey: String, report: JSONObject): JSONObject {
        require(shiftKey.matches(Regex("^[A-Za-z0-9._:-]{1,100}$"))) {
            "Identificador de turno no válido"
        }
        require(report.toString().length <= 16384) { "El reporte es demasiado grande" }
        val body = JSONObject().put("shiftKey", shiftKey).put("report", report)
        return deviceSigned("VENTAS_SHIFT_REPORT", "/v1/ventas/shift-reports", body.toString())
    }

    /** Lee únicamente la configuración de nómina del espacio al que pertenece este equipo. */
    fun payrollSettings(): JSONObject = deviceSigned(
        "VENTAS_PAYROLL_SETTINGS_GET", "/v1/ventas/payroll/settings/get", "{}"
    )

    /** El servidor solo acepta este reporte en una fecha de pago configurada para el slot. */
    fun savePayrollReport(periodKey: String, report: JSONObject): JSONObject {
        require(periodKey.matches(Regex("^[A-Za-z0-9._:-]{1,100}$"))) {
            "Fecha de nómina no válida"
        }
        require(report.toString().length <= 16384) { "El reporte es demasiado grande" }
        val body = JSONObject().put("periodKey", periodKey).put("report", report)
        return deviceSigned("VENTAS_PAYROLL_REPORT", "/v1/ventas/payroll-reports", body.toString())
    }

    /**
     * Solicita autorización para corregir una venta concreta.
     * El servidor recibe únicamente categorías, motivo e identificador/hash del plan,
     * no los renglones, cantidades ni precios de la venta.
     */
    fun requestSaleCorrection(
        saleId: String,
        reason: String,
        reasons: List<String>,
        planHash: String,
        cashImpact: Boolean,
        cashReason: String = "",
        idempotencyKey: String = UUID.randomUUID().toString()
    ): JSONObject {
        require(saleId.isNotBlank() && saleId.length <= 120) { "Venta no válida" }
        require(reason in setOf(
            "Devolución", "Ajuste de precio", "Eliminación de producto",
            "Corrección de cantidad", "Producto equivocado", "Producto omitido", "Otro"
        )) { "Motivo de corrección no válido" }
        require(reasons.isNotEmpty() && reasons.size <= 7) { "Selecciona al menos un motivo" }
        require(planHash.matches(Regex("^[a-fA-F0-9]{64}$"))) { "Huella del plan no válida" }
        require(idempotencyKey.matches(Regex("^[0-9a-fA-F-]{36}$"))) {
            "Clave de operación no válida"
        }
        val allowedCashReasons = setOf(
            "Devolución al cliente", "Diferencia por precio vigente",
            "Cantidad corregida", "Producto eliminado o incorrecto",
            "Producto omitido", "Otro"
        )
        require(!cashImpact || cashReason in allowedCashReasons) {
            "Selecciona el motivo del cambio en caja"
        }
        require(cashImpact || cashReason.isEmpty()) {
            "No se debe enviar un motivo de caja si no cambia el efectivo"
        }
        val body = JSONObject()
            .put("saleId", saleId)
            .put("reason", reason)
            .put("reasons", org.json.JSONArray(reasons.distinct()))
            .put("planHash", planHash.lowercase())
            .put("cashImpact", cashImpact)
            .put("cashReason", cashReason)
            .put("idempotencyKey", idempotencyKey)
        return deviceSigned("VENTAS_CORRECTION_CREATE", "/v1/ventas/corrections", body.toString())
    }

    /** Consume un permiso aprobado; el Worker impide reutilizarlo en otra venta. */
    fun consumeSaleCorrection(
        requestId: String,
        grantId: String,
        saleId: String,
        planHash: String
    ): JSONObject {
        require(requestId.matches(Regex("^[0-9a-fA-F-]{36}$")) &&
            grantId.matches(Regex("^[0-9a-fA-F-]{36}$"))) {
            "Permiso de corrección no válido"
        }
        require(saleId.isNotBlank() && planHash.matches(Regex("^[a-fA-F0-9]{64}$"))) {
            "La venta o la huella de corrección no es válida"
        }
        val body = JSONObject()
            .put("requestId", requestId)
            .put("grantId", grantId)
            .put("saleId", saleId)
            .put("planHash", planHash.lowercase())
        return deviceSigned(
            "VENTAS_CORRECTION_CONSUME", "/v1/ventas/corrections/consume", body.toString()
        )
    }

    private fun bootstrapSigned(purpose: String, path: String, body: String): JSONObject {
        val identity = SeneVentasDeviceIdentity.get(app)
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
        val signature = sign(challenge)
        return post(path, body, challenge.getString("challengeId"), signature, null)
    }

    private fun deviceSigned(purpose: String, path: String, body: String): JSONObject {
        val deviceId = storedDeviceId()
            ?: throw IllegalStateException("Este SENE VENTAS todavía no está vinculado a SENE PATRÓN")
        val challengeBody = JSONObject()
            .put("purpose", purpose)
            .put("deviceId", deviceId)
            .put("targetMethod", "POST")
            .put("targetPath", path)
            .put("bodySha256", sha256(body))
        val challenge = post("/v1/challenges", challengeBody.toString())
        val signature = sign(challenge)
        return post(path, body, challenge.getString("challengeId"), signature, deviceId)
    }

    private fun sign(challenge: JSONObject): String {
        val encoded = challenge.getString("signingMessage")
        val message = Base64.decode(
            encoded, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        return SeneVentasDeviceIdentity.signChallenge(app, message)
    }

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
            setRequestProperty("Accept", "application/json")
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
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
