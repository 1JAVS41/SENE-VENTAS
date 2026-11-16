package com.sene.ventas

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object ActivationApi {
    const val BASE_URL = "https://server-sene-api.nagavs28.workers.dev"
    private const val PREFS = "sene_server_link"

    fun installationId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString("installation_id", null)
        if (!saved.isNullOrBlank()) return saved
        val created = UUID.randomUUID().toString()
        prefs.edit().putString("installation_id", created).apply()
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
        return post("/subscription/status", JSONObject().put("installation_id", installationId(context)))
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

    fun clearPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("pending_folio").remove("pending_amount_cents").remove("pending_expires_at").apply()
    }
}
