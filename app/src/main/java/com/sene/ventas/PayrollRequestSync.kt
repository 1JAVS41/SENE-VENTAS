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
    private const val ACKED_REQUESTS = "acked_request_ids"
    private const val PENDING_DECISION_ACKS = "pending_decision_acks"
    private val running = AtomicBoolean(false)

    private fun decisionAcks(prefs: android.content.SharedPreferences): JSONArray =
        try { JSONArray(prefs.getString(PENDING_DECISION_ACKS, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }

    private fun mergeDecisionAcks(prefs: android.content.SharedPreferences, ids: Set<String>) {
        if (ids.isEmpty()) return
        val merged = linkedSetOf<String>()
        val old = decisionAcks(prefs)
        for (i in 0 until old.length()) old.optString(i).takeIf { it.isNotBlank() }?.let { merged.add(it) }
        merged.addAll(ids.filter { it.isNotBlank() })
        prefs.edit().putString(PENDING_DECISION_ACKS, JSONArray(merged.toList()).toString()).commit()
    }

    private fun clearDecisionAcks(prefs: android.content.SharedPreferences, confirmations: JSONArray?) {
        if (confirmations == null || confirmations.length() == 0) return
        val confirmed = mutableSetOf<String>()
        for (i in 0 until confirmations.length()) {
            val item = confirmations.opt(i)
            val id = if (item is JSONObject) item.optString("e", item.optString("id")) else item?.toString().orEmpty()
            if (id.isNotBlank()) confirmed.add(id)
        }
        if (confirmed.isEmpty()) return
        val remaining = decisionAcks(prefs)
        val keep = JSONArray()
        for (i in 0 until remaining.length()) {
            val id = remaining.optString(i)
            if (id.isNotBlank() && id !in confirmed) keep.put(id)
        }
        prefs.edit().putString(PENDING_DECISION_ACKS, keep.toString()).commit()
    }

    private fun acknowledgedRequests(prefs: android.content.SharedPreferences): Set<String> {
        return try {
            val values = JSONArray(prefs.getString(ACKED_REQUESTS, "[]") ?: "[]")
            val result = mutableSetOf<String>()
            for (i in 0 until values.length()) {
                val id = values.optString(i)
                if (id.isNotBlank()) result.add(id)
            }
            result
        } catch (_: Exception) { emptySet() }
    }

    private fun saveRequestAcknowledgements(prefs: android.content.SharedPreferences, ids: JSONArray?) {
        if (ids == null || ids.length() == 0) return
        val accepted = acknowledgedRequests(prefs).toMutableSet()
        for (i in 0 until ids.length()) {
            val item = ids.opt(i)
            val id = if (item is JSONObject) item.optString("e", item.optString("id")) else item?.toString().orEmpty()
            if (id.isNotBlank()) accepted.add(id)
        }
        prefs.edit().putString(ACKED_REQUESTS, JSONArray(accepted.toList()).toString()).commit()
    }

    /**
     * Respalda cualquier reporte localmente cuando aún no está disponible la clave pública
     * de SeneAdmin. El formato es el mismo que migra migrateLocalPendingReports().
     */
    fun saveLocalPendingReport(
        context: Context, eventId: String, workerId: String, reportType: String, body: String,
        contentType: String = reportType
    ): Boolean {
        if (eventId.isBlank() || workerId.isBlank() || reportType.isBlank() || contentType.isBlank() || body.isBlank()) return false
        return try {
            val folder = java.io.File(context.applicationContext.filesDir, "REPORTES-PENDIENTES-SERVER")
            if (!folder.exists() && !folder.mkdirs()) return false
            val safeEvent = eventId.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = java.io.File(folder, "$safeEvent.sene")
            if (target.isFile && target.length() > 0L) {
                val existing = try {
                    JSONObject(InventoryCrypto.decrypt(target.readBytes()).toString(Charsets.UTF_8))
                } catch (_: Exception) { null }
                if (existing?.optString("e") == eventId) return true
                return false
            }
            val envelope = JSONObject().put("e", eventId).put("w", workerId)
                .put("k", reportType).put("r", contentType).put("b", body)
            val temp = java.io.File(folder, "$safeEvent.sene.tmp")
            temp.writeBytes(InventoryCrypto.encrypt(envelope.toString().toByteArray(Charsets.UTF_8)))
            val verified = JSONObject(InventoryCrypto.decrypt(temp.readBytes()).toString(Charsets.UTF_8))
            if (verified.optString("e") != eventId || verified.optString("b").isBlank()) {
                temp.delete()
                return false
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                return false
            }
            target.isFile && target.length() > 0L
        } catch (_: Exception) { false }
    }

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
                val contentType = stored?.optString("r").orEmpty().ifBlank {
                    when(reportType) {
                        SeneWireProtocol.IN_PROGRESS -> "INICIO_TURNO"
                        SeneWireProtocol.FULL_SHIFT, SeneWireProtocol.EARLY_CLOSE, SeneWireProtocol.ADMIN_CLOSE -> "CIERRE_TURNO"
                        else -> reportType
                    }
                }
                val body = stored?.optString("b").orEmpty().ifBlank {
                    // Compatibilidad con respaldos creados por APK anteriores.
                    if (stored == null) plain else ""
                }
                if (eventId.isBlank() || workerId.isBlank() || body.isBlank()) continue
                val encrypted = ReportCrypto.prepare(app, workerId, contentType, body, eventId)
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
                val acknowledged = acknowledgedRequests(prefs)
                val pending = EmployeePayroll.entries(app).filter {
                    it.status == "PENDING" && !it.settled && it.id !in acknowledged &&
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
                    .put(SeneWireProtocol.FIELD_DECISION_ACKS, decisionAcks(prefs))
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
                val requestAcks = response.optJSONArray(SeneWireProtocol.FIELD_REQUEST_ACKS)
                    ?: response.optJSONArray("request_acks") ?: JSONArray()
                saveRequestAcknowledgements(prefs, requestAcks)
                val decisions = response.optJSONArray("d") ?: response.optJSONArray("decisions") ?: JSONArray()
                val processedDecisionAcks = mutableSetOf<String>()
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
                    val applied = EmployeePayroll.applyDecision(app, id, state == "APPROVED",
                        decision.optString("by", decision.optString("decided_by")), decidedAt,
                        decision.optString("rr", decision.optString("rejection_reason")))
                    if (applied) processedDecisionAcks.add(id)
                }
                mergeDecisionAcks(prefs, processedDecisionAcks)
                val decisionAckConfirmations = response.optJSONArray(SeneWireProtocol.FIELD_DECISION_ACK_CONFIRMATIONS)
                    ?: response.optJSONArray("decision_ack_acks")
                clearDecisionAcks(prefs, decisionAckConfirmations)
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
