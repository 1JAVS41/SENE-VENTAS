package com.sene.ventas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Cola durable de eventos de asistencia. Solo se elimina un evento tras ACK del servidor. */
object AttendanceEventOutbox {
    private const val PREFS = "sene_attendance_wire_outbox"
    private const val KEY = "events"
    private const val ACKED = "acked_event_ids"

    @Synchronized
    fun enqueue(
        context: Context, workerId: String, date: String, state: String,
        startedAt: Long, closedAt: Long, reason: String, source: String,
        eventId: String = UUID.randomUUID().toString()
    ): Boolean {
        if (workerId.isBlank() || date.isBlank() || eventId.isBlank()) return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val acked = try { JSONArray(prefs.getString(ACKED, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        if ((0 until acked.length()).any { acked.optString(it) == eventId }) return true
        val events = try { JSONArray(prefs.getString(KEY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        if ((0 until events.length()).any { events.optJSONObject(it)?.optString("e") == eventId }) return true
        events.put(JSONObject().put("v", SeneWireProtocol.VERSION).put("t", SeneWireProtocol.ATTENDANCE_SYNC).put("e", eventId)
            .put("w", workerId).put("d", date).put("x", state)
            .put("s", startedAt).put("f", closedAt).put("m", reason).put("o", source))
        return prefs.edit().putString(KEY, events.toString()).commit()
    }

    @Synchronized
    fun pending(context: Context): JSONArray {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return try { JSONArray(prefs.getString(KEY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
    }

    @Synchronized
    fun acknowledge(context: Context, ids: JSONArray) {
        if (ids.length() == 0) return
        val accepted = mutableSetOf<String>()
        for (i in 0 until ids.length()) {
            val id = ids.optString(i)
            if (id.isNotBlank()) accepted.add(id)
        }
        if (accepted.isEmpty()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = try { JSONArray(prefs.getString(KEY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val remaining = JSONArray()
        for (i in 0 until old.length()) {
            val event = old.optJSONObject(i) ?: continue
            if (event.optString("e") !in accepted) remaining.put(event)
        }
        val previous = try { JSONArray(prefs.getString(ACKED, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val acked = linkedSetOf<String>()
        for (i in 0 until previous.length()) {
            val id = previous.optString(i)
            if (id.isNotBlank()) acked.add(id)
        }
        acked.addAll(accepted)
        prefs.edit().putString(KEY, remaining.toString())
            .putString(ACKED, JSONArray(acked.toList().takeLast(500)).toString()).commit()
    }
}
