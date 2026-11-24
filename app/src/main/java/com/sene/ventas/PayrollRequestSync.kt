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

    /**
     * Convierte reportes que se guardaron localmente antes de recibir la clave pública.
     * El archivo original solo se elimina después de persistir el sobre RSA en la cola SQLite.
     */
    private fun migrateLocalPendingReports(app: Context): Boolean {
        if (!ReportPublicKeyStore.hasKey(app)) return false
        val folder = java.io.File(app.filesDir, "REPORTES-PENDIENTES-SERVER")
        val files = folder.listFiles { file -> file.isFile && file.extension.equals("sene", true) }
            ?.sortedBy { it.name } ?: return false
        var migrated = false
        for (file in files) {
            try {
                val plain = InventoryCrypto.decrypt(file.readBytes()).toString(Charsets.UTF_8)
                val stored = try { JSONObject(plain) } catch (_: Exception) { null }
                val eventId = stored?.optString("e").orEmpty().ifBlank {
                    file.nameWithoutExtension.replace("_START", ":START")
                }
                val activeShift = Store.shift(app)
                val workerId = stored?.optString("w").orEmpty().ifBlank {
                    val shiftId = eventId.substringBefore(":")
                    if (activeShift?.optString("shiftId") == shiftId) activeShift.optString("employeeId") else ""
                }
                val reportType = stored?.optString("k").orEmpty().ifBlank { SeneWireProtocol.IN_PROGRESS }
                val body = stored?.optString("b").orEmpty().ifBlank {
                    // Compatibilidad con respaldos creados por APK anteriores.
                    if (stored == null) plain else ""
                }
                if (eventId.isBlank() || workerId.isBlank() || body.isBlank()) continue
                val encrypted = ReportCrypto.prepare(app, workerId, reportType, body, eventId)
                if (ReportEventOutbox.enqueue(app, eventId, workerId, reportType, encrypted) && file.delete()) {
                    migrated = true
                } else if (ReportEventOutbox.pending(app).let { queue ->
                    (0 until queue.length()).any { queue.optJSONObject(it)?.optString("e") == eventId }
                }) {
                    // La cola durable ya tiene el evento; el archivo local puede retirarse sin perderlo.
                    if (file.delete()) migrated = true
                }
            } catch (_: Exception) {
                // Un archivo que no se pueda descifrar o convertir permanece intacto para recuperación.
            }
        }
        return migrated
    }

    /** SERVER-SENE devuelve decided_at en ISO-8601 UTC; acepta también epoch legado. */
    private fun parseDecisionTimestamp(value: Any?): Long = when (value) {
        is Number -> value.toLong().let { if (it in 1L until 100000000000L) it * 1000L else it }
        is String -> {
            val raw = value.trim()
            raw.toLongOrNull()?.let { if (it in 1L until 100000000000L) it * 1000L else it }
                ?: try {
                    val pattern = if (raw.contains(".")) "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
                        else "yyyy-MM-dd'T'HH:mm:ss'Z'"
                    java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                        isLenient = false
                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }.parse(raw)?.time ?: 0L
                } catch (_: Exception) { 0L }
        }
        else -> 0L
    }

    /** Publica una fotografía completa de inventario si esta instalación aún no la ha entregado. */
    private fun ensureInventorySnapshots(app: Context) {
        val now=System.currentTimeMillis()
        fun enqueue(scope:String,products:JSONArray) {
            val rows=JSONArray()
            for(i in 0 until products.length()) {
                val item=products.optJSONObject(i)?:continue
                val code=item.optString("code")
                if(code.isBlank())continue
                rows.put(JSONObject().put("c",code).put("n",item.optString("name"))
                    .put("a",(item.optDouble("price",0.0)*100.0).toLong().coerceAtLeast(0L))
                    .put("s",item.optInt("stock",0).coerceAtLeast(0))
                    .put("l",item.optString("aisle")).put("h",item.optString("shelf")))
            }
            InventoryEventOutbox.ensureFullSnapshot(app,scope,rows,now)
        }
        // El inventario maestro se conserva aparte del inventario operativo del turno.
        enqueue("M",Store.masterProducts(app))
        if(Store.shift(app)!=null) enqueue("S",Store.products(app))
    }

    fun sync(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(LAST_ATTEMPT, 0L) < 15L * 60L * 1000L) return
        if (!running.compareAndSet(false, true)) return
        Thread {
            var retryAfterKey = false
            try {
                migrateLocalPendingReports(app)
                // Si el inventario ya existía antes de activar el protocolo compacto,
                // envía su fotografía inicial en esta misma sincronización.
                ensureInventorySnapshots(app)
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
                    .put(SeneWireProtocol.FIELD_VERSION, SeneWireProtocol.VERSION)
                    .put(SeneWireProtocol.FIELD_TYPE, SeneWireProtocol.SYNC)
                    .put(SeneWireProtocol.FIELD_INSTALLATION_ID, ActivationApi.installationId(app))
                    .put(SeneWireProtocol.FIELD_CURSOR, prefs.getString(CURSOR, "").orEmpty())
                    .put(SeneWireProtocol.FIELD_REQUESTS, requests)
                    .put(SeneWireProtocol.FIELD_ATTENDANCE_EVENTS, AttendanceEventOutbox.pending(app))
                    .put(SeneWireProtocol.FIELD_INVENTORY_EVENTS, InventoryEventOutbox.pending(app))
                    .put(SeneWireProtocol.FIELD_REPORT_EVENTS, ReportEventOutbox.pending(app))
                prefs.edit().putLong(LAST_ATTEMPT, now).commit()
                val response = ActivationApi.post("/payroll/requests/sync", payload)
                val status = response.optInt("_http_status", 0)
                if (status !in 200..299) return@Thread
                if(response.optInt(SeneWireProtocol.FIELD_VERSION,SeneWireProtocol.VERSION)!=SeneWireProtocol.VERSION)return@Thread
                val reportPublicKey = response.optString("rp", response.optString("report_public_key"))
                if (reportPublicKey.isNotBlank()) {
                    ReportPublicKeyStore.saveFromServer(app, reportPublicKey)
                    // Si esta respuesta acaba de entregar la clave, sube los respaldos
                    // anteriores en una segunda sincronización inmediata, sin esperar 15 minutos.
                    if (ReportPublicKeyStore.hasKey(app)) retryAfterKey = migrateLocalPendingReports(app)
                }
                val decisions = response.optJSONArray("d") ?: response.optJSONArray("decisions") ?: JSONArray()
                for (i in 0 until decisions.length()) {
                    val decision = decisions.optJSONObject(i) ?: continue
                    val id = decision.optString("e", decision.optString("request_id"))
                    val state = when(decision.optString("x", decision.optString("status")).uppercase()) {
                        SeneWireProtocol.APPROVED, "APPROVED" -> "APPROVED"
                        SeneWireProtocol.REJECTED, "REJECTED" -> "REJECTED"
                        else -> ""
                    }
                    if (id.isBlank() || state !in setOf("APPROVED", "REJECTED")) continue
                    val decidedAt = parseDecisionTimestamp(
                        decision.opt("at")?.takeIf { it !is org.json.JSONObject && it != JSONObject.NULL }
                            ?: decision.opt("decided_at")
                    )
                    EmployeePayroll.applyDecision(app, id, state == "APPROVED",
                        decision.optString("by", decision.optString("decided_by")), decidedAt,
                        decision.optString("rr", decision.optString("rejection_reason")))
                }
                val attendanceAcks = response.optJSONArray(SeneWireProtocol.FIELD_ATTENDANCE_ACKS) ?: response.optJSONArray("attendance_acks") ?: JSONArray()
                AttendanceEventOutbox.acknowledge(app, attendanceAcks)
                val inventoryAcks = response.optJSONArray(SeneWireProtocol.FIELD_SALES_INVENTORY_ACKS) ?: response.optJSONArray("inventory_acks") ?: JSONArray()
                InventoryEventOutbox.acknowledge(app, inventoryAcks)
                val reportAcks = response.optJSONArray(SeneWireProtocol.FIELD_REPORT_ACKS) ?: response.optJSONArray("report_acks")
                ReportEventOutbox.acknowledge(app, reportAcks)
                val cursor = response.optString(SeneWireProtocol.FIELD_NEXT_CURSOR, response.optString("next_cursor"))
                if (cursor.isNotBlank()) prefs.edit().putString(CURSOR, cursor).commit()
            } catch (_: Exception) {
                // La solicitud permanece pendiente y se reintentará en la siguiente sincronización.
            } finally {
                running.set(false)
                if (retryAfterKey) sync(app, true)
            }
        }.start()
    }
}
