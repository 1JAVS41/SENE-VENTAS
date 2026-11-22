package com.sene.ventas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cola durable de reportes cifrados para transporte al servidor.
 * Los eventos no se eliminan hasta recibir el ACK explícito del servidor.
 * El contenido sigue cifrado con el formato histórico para conservar compatibilidad
 * con las instalaciones que todavía usan el lector heredado.
 */
object ReportEventOutbox {
    private const val PREFS = "sene_report_wire_outbox"
    private const val KEY = "pending"

    @Synchronized
    fun enqueue(
        context: Context,
        eventId: String,
        workerId: String,
        reportType: String,
        encryptedPayload: String,
        createdAt: Long = System.currentTimeMillis()
    ): Boolean {
        if (eventId.isBlank() || encryptedPayload.isBlank()) return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = read(prefs.getString(KEY, null))
        if ((0 until current.length()).any { current.optJSONObject(it)?.optString("e") == eventId }) return true
        current.put(JSONObject()
            .put("v", SeneWireProtocol.VERSION)
            .put("t", SeneWireProtocol.REPORT)
            .put("e", eventId)
            .put("w", workerId)
            .put("k", reportType)
            .put("i", createdAt)
            .put("b", encryptedPayload))
        return prefs.edit().putString(KEY, current.toString()).commit()
    }

    @Synchronized
    fun pending(context: Context): JSONArray {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return read(prefs.getString(KEY, null))
    }

    @Synchronized
    fun acknowledge(context: Context, acknowledgements: JSONArray?) {
        if (acknowledgements == null || acknowledgements.length() == 0) return
        val accepted = mutableSetOf<String>()
        for (i in 0 until acknowledgements.length()) {
            val item = acknowledgements.opt(i)
            val id = when (item) {
                is JSONObject -> item.optString("e", item.optString("id"))
                else -> item?.toString().orEmpty()
            }
            if (id.isNotBlank()) accepted.add(id)
        }
        if (accepted.isEmpty()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = read(prefs.getString(KEY, null))
        val remaining = JSONArray()
        for (i in 0 until current.length()) {
            val event = current.optJSONObject(i) ?: continue
            if (event.optString("e") !in accepted) remaining.put(event)
        }
        prefs.edit().putString(KEY, remaining.toString()).commit()
    }

    private fun read(raw: String?): JSONArray = try {
        JSONArray(raw ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }
}
