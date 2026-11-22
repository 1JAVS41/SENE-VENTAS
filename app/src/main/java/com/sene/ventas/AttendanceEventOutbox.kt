package com.sene.ventas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Cola durable de eventos de asistencia. Solo se elimina un evento tras ACK del servidor. */
object AttendanceEventOutbox {
    private const val PREFS = "sene_attendance_wire_outbox"
    private const val KEY = "events"

    @Synchronized
    fun enqueue(
        context: Context, workerId: String, date: String, state: String,
        startedAt: Long, closedAt: Long, reason: String, source: String
    ) {
        if (workerId.isBlank() || date.isBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val events = try { JSONArray(prefs.getString(KEY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val id = UUID.randomUUID().toString()
        events.put(JSONObject().put("v", SeneWireProtocol.VERSION).put("e", id)
            .put("w", workerId).put("d", date).put("x", state)
            .put("s", startedAt).put("f", closedAt).put("m", reason).put("o", source))
        prefs.edit().putString(KEY, events.toString()).commit()
    }

    @Synchronized
    fun pending(context: Context): JSONArray {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return try { JSONArray(prefs.getString(KEY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
    }

    @Synchronized
    fun acknowledge(context: Context, ids: JSONArray) {
        if (ids.length() == 0) return
        val accepted = buildSet { for (i in 0 until ids.length()) ids.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
        if (accepted.isEmpty()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = try { JSONArray(prefs.getString(KEY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val remaining = JSONArray()
        for (i in 0 until old.length()) {
            val event = old.optJSONObject(i) ?: continue
            if (event.optString("e") !in accepted) remaining.put(event)
        }
        prefs.edit().putString(KEY, remaining.toString()).commit()
    }
}
