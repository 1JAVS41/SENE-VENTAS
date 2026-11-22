package com.sene.ventas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cliente de sincronización de solicitudes de nómina.
 * La cola local es la fuente durable: un fallo de red nunca elimina ni autoriza una solicitud.
 * El endpoint se implementará en SERVER-SENE en la etapa final.
 */
object PayrollRequestSync {
    private const val PREFS = "sene_payroll_request_sync"
    private const val CURSOR = "server_cursor"
    private const val LAST_ATTEMPT = "last_attempt"
    private val running = AtomicBoolean(false)

    fun sync(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(LAST_ATTEMPT, 0L) < 15L * 60L * 1000L) return
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                val pending = EmployeePayroll.entries(app).filter {
                    it.status == "PENDING" && !it.settled &&
                        (it.type == EmployeePayroll.TYPE_LOAN || it.type == EmployeePayroll.TYPE_CONSUMPTION)
                }
                val requests = JSONArray()
                pending.forEach { entry ->
                    val typeCode = if (entry.type == EmployeePayroll.TYPE_LOAN)
                        SeneWireProtocol.LOAN else SeneWireProtocol.CONSUMPTION
                    requests.put(SeneWireProtocol.request(
                        typeCode, entry.id, entry.employeeId, entry.amountCents
                    ).put("n", entry.employee)
                        .put("r", entry.reason)
                        .put("z", entry.description)
                        .put("p", entry.productCode)
                        .put("q", entry.quantity)
                        .put("i", entry.timestamp)
                        .put("k", entry.periodKey))
                }
                val payload = JSONObject()
                    .put("v", SeneWireProtocol.VERSION)
                    .put("t", "[SYNC]")
                    .put("i", ActivationApi.installationId(app))
                    .put("c", prefs.getString(CURSOR, "").orEmpty())
                    .put("r", requests)
                prefs.edit().putLong(LAST_ATTEMPT, now).commit()
                val response = ActivationApi.post("/payroll/requests/sync", payload)
                val status = response.optInt("_http_status", 0)
                if (status !in 200..299) return@Thread
                val decisions = response.optJSONArray("d") ?: response.optJSONArray("decisions") ?: JSONArray()
                for (i in 0 until decisions.length()) {
                    val decision = decisions.optJSONObject(i) ?: continue
                    val id = decision.optString("e", decision.optString("request_id"))
                    val state = when(decision.optString("x", decision.optString("status")).uppercase()) {
                        "[OK]" -> "APPROVED"
                        "[NO]" -> "REJECTED"
                        else -> decision.optString("status").uppercase()
                    }
                    if (id.isBlank() || state !in setOf("APPROVED", "REJECTED")) continue
                    val decidedAt = when (val raw = decision.opt("at")?.takeIf { it !is org.json.JSONObject && it != JSONObject.NULL } ?: decision.opt("decided_at")) {
                        is Number -> raw.toLong().let { if (it < 100000000000L) it * 1000L else it }
                        is String -> raw.toLongOrNull() ?: 0L
                        else -> 0L
                    }
                    EmployeePayroll.applyDecision(app, id, state == "APPROVED",
                        decision.optString("by", decision.optString("decided_by")), decidedAt,
                        decision.optString("rr", decision.optString("rejection_reason")))
                }
                val cursor = response.optString("n", response.optString("next_cursor"))
                if (cursor.isNotBlank()) prefs.edit().putString(CURSOR, cursor).commit()
            } catch (_: Exception) {
                // La solicitud permanece pendiente y se reintentará en la siguiente sincronización.
            } finally {
                running.set(false)
            }
        }.start()
    }
}
