package com.sene.ventas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Persistencia local de la configuración y movimientos de nómina.
 * Los importes se guardan como centavos para evitar errores de redondeo.
 */
object EmployeePayroll {
    private const val PREFS = "sene_employee_payroll_v1"
    private const val CONFIG = "employee_config"
    private const val ENTRIES = "payroll_entries"

    data class Config(
        val employee: String,
        val dailyHours: Int,
        val weeklySalaryCents: Long,
        val workDays: Int,
        val restDay: Int,
        val payDay: Int,
        val workDaysOfWeek: Set<Int> = emptySet()
    )

    data class Capacity(
        val adjustedSalaryCents: Long,
        val limitCents: Long,
        val committedCents: Long,
        val availableCents: Long
    )

    data class Entry(
        val id: String,
        val employee: String,
        val type: String,
        val amountCents: Long,
        val reason: String,
        val description: String,
        val timestamp: Long,
        val periodKey: String,
        val settled: Boolean
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun config(context: Context): Config? {
        val raw = prefs(context).getString(CONFIG, null) ?: return null
        return try {
            val x = JSONObject(raw)
            val legacyRest = x.optInt("restDay", Calendar.SUNDAY).coerceIn(Calendar.SUNDAY, Calendar.SATURDAY)
            val legacyCount = x.optInt("workDays", 6).coerceIn(1, 7)
            val savedDays = x.optJSONArray("workDaysOfWeek")
            val selectedDays = if (savedDays != null) {
                buildSet { for (i in 0 until savedDays.length()) savedDays.optInt(i).takeIf { it in Calendar.SUNDAY..Calendar.SATURDAY }?.let { add(it) } }
            } else {
                val ordered = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
                ordered.filter { it != legacyRest }.take(legacyCount).toSet()
            }
            Config(
                employee = x.optString("employee").trim(),
                dailyHours = x.optInt("dailyHours", 8).coerceIn(1, 24),
                weeklySalaryCents = x.optLong("weeklySalaryCents", 0L).coerceAtLeast(0L),
                workDays = selectedDays.size.coerceIn(1, 7),
                restDay = (Calendar.SUNDAY..Calendar.SATURDAY).firstOrNull { it !in selectedDays } ?: legacyRest,
                payDay = x.optInt("payDay", Calendar.SATURDAY).coerceIn(Calendar.SUNDAY, Calendar.SATURDAY),
                workDaysOfWeek = selectedDays
            ).takeIf { it.employee.isNotBlank() && it.weeklySalaryCents > 0L && selectedDays.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    fun saveConfig(context: Context, config: Config): Boolean {
        val selectedDays = config.workDaysOfWeek
        if (config.employee.isBlank() || config.dailyHours !in 1..24 ||
            config.weeklySalaryCents <= 0L || selectedDays.isEmpty() || selectedDays.any { it !in Calendar.SUNDAY..Calendar.SATURDAY } ||
            config.payDay !in Calendar.SUNDAY..Calendar.SATURDAY) return false
        val restDay = (Calendar.SUNDAY..Calendar.SATURDAY).firstOrNull { it !in selectedDays } ?: Calendar.SUNDAY
        val json = JSONObject()
            .put("employee", config.employee.trim())
            .put("dailyHours", config.dailyHours)
            .put("weeklySalaryCents", config.weeklySalaryCents)
            .put("workDays", selectedDays.size)
            .put("restDay", restDay)
            .put("payDay", config.payDay)
            .put("workDaysOfWeek", JSONArray().apply { selectedDays.sorted().forEach { put(it) } })
        return prefs(context).edit().putString(CONFIG, json.toString()).commit()
    }

    /**
     * Periodo semanal: desde el día posterior al último día de pago hasta el próximo día de pago.
     * Se usa la fecha local del dispositivo; el periodo queda identificado por su inicio.
     */
    fun periodKey(timestamp: Long, payDay: Int): String {
        val c = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = c.get(Calendar.DAY_OF_WEEK)
        val daysSincePayDay = (today - payDay + 7) % 7
        c.add(Calendar.DAY_OF_YEAR, -daysSincePayDay)
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return formatter.format(c.time)
    }

    fun entries(context: Context): List<Entry> {
        val raw = prefs(context).getString(ENTRIES, "[]") ?: "[]"
        return try {
            val a = JSONArray(raw)
            buildList {
                for (i in 0 until a.length()) {
                    val x = a.optJSONObject(i) ?: continue
                    add(Entry(
                        id = x.optString("id"),
                        employee = x.optString("employee"),
                        type = x.optString("type"),
                        amountCents = x.optLong("amountCents").coerceAtLeast(0L),
                        reason = x.optString("reason"),
                        description = x.optString("description"),
                        timestamp = x.optLong("timestamp"),
                        periodKey = x.optString("periodKey"),
                        settled = x.optBoolean("settled", false)
                    ))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveEntries(context: Context, entries: List<Entry>): Boolean {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(JSONObject()
                .put("id", e.id)
                .put("employee", e.employee)
                .put("type", e.type)
                .put("amountCents", e.amountCents)
                .put("reason", e.reason)
                .put("description", e.description)
                .put("timestamp", e.timestamp)
                .put("periodKey", e.periodKey)
                .put("settled", e.settled))
        }
        return prefs(context).edit().putString(ENTRIES, array.toString()).commit()
    }

    fun capacity(context: Context, absences: Int, timestamp: Long = System.currentTimeMillis()): Capacity {
        val cfg = config(context) ?: return Capacity(0L, 0L, 0L, 0L)
        val dayRate = cfg.weeklySalaryCents / cfg.workDays
        val absenceDeduction = (dayRate * absences.coerceAtLeast(0)).coerceAtMost(cfg.weeklySalaryCents)
        val adjusted = (cfg.weeklySalaryCents - absenceDeduction).coerceAtLeast(0L)
        val limit = adjusted / 2L
        val key = periodKey(timestamp, cfg.payDay)
        val committed = entries(context)
            .filter { it.employee.equals(cfg.employee, true) && it.periodKey == key && !it.settled &&
                (it.type == TYPE_LOAN || it.type == TYPE_CONSUMPTION) }
            .sumOf { it.amountCents }
        return Capacity(adjusted, limit, committed, (limit - committed).coerceAtLeast(0L))
    }

    /** Registra un consumo o préstamo solo si el total conjunto respeta el 50% disponible. */
    @Synchronized
    fun addRequest(
        context: Context,
        type: String,
        amountCents: Long,
        reason: String = "",
        description: String = "",
        absences: Int = 0,
        timestamp: Long = System.currentTimeMillis()
    ): Entry? {
        if (type != TYPE_LOAN && type != TYPE_CONSUMPTION) return null
        if (amountCents <= 0L) return null
        val cfg = config(context) ?: return null
        val cap = capacity(context, absences, timestamp)
        if (amountCents > cap.availableCents) return null
        val entry = Entry(
            id = java.util.UUID.randomUUID().toString(),
            employee = cfg.employee,
            type = type,
            amountCents = amountCents,
            reason = reason.trim(),
            description = description.trim(),
            timestamp = timestamp,
            periodKey = periodKey(timestamp, cfg.payDay),
            settled = false
        )
        if (!saveEntries(context, entries(context) + entry)) return null
        return entry
    }

    fun markSettled(context: Context, period: String): Boolean {
        val updated = entries(context).map {
            if (it.periodKey == period && !it.settled) it.copy(settled = true) else it
        }
        return saveEntries(context, updated)
    }

    fun entriesForCurrentPeriod(context: Context, timestamp: Long = System.currentTimeMillis()): List<Entry> {
        val cfg = config(context) ?: return emptyList()
        val key = periodKey(timestamp, cfg.payDay)
        return entries(context).filter {
            it.employee.equals(cfg.employee, true) && it.periodKey == key && !it.settled
        }
    }

    fun money(cents: Long): String = "$" + String.format(Locale.US, "%.2f", cents.coerceAtLeast(0L) / 100.0)

    const val TYPE_LOAN = "LOAN"
    const val TYPE_CONSUMPTION = "CONSUMPTION"
}
