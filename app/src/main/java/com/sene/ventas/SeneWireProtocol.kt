package com.sene.ventas

import org.json.JSONObject

/**
 * Diccionario SENE Wire v1. Los códigos solo se usan en el transporte.
 * La interfaz siempre debe usar los textos completos de displayLabel().
 */
object SeneWireProtocol {
    const val VERSION = 1

    // Campos compactos compartidos por ambas APK y SERVER-SENE (Wire v1).
    const val FIELD_VERSION = "v"
    const val FIELD_TYPE = "t"
    const val FIELD_EVENT_ID = "e"
    const val FIELD_WORKER_ID = "w"
    const val FIELD_AMOUNT_CENTS = "a"
    const val FIELD_INSTALLATION_ID = "i"
    const val FIELD_CURSOR = "c"
    const val FIELD_REQUESTS = "r"
    const val FIELD_ATTENDANCE_EVENTS = "h"
    const val FIELD_INVENTORY_EVENTS = "g"
    const val FIELD_REPORT_EVENTS = "b"
    const val FIELD_DECISIONS = "d"
    const val FIELD_ADMIN_TOKEN = "a"
    const val FIELD_ATTENDANCE_ACKS = "ha"
    const val FIELD_INVENTORY_ACKS = "gi"
    const val FIELD_SALES_INVENTORY_ACKS = "ga"
    const val FIELD_REPORT_ACKS = "ba"
    const val FIELD_REQUEST_ACKS = "ra"
    const val FIELD_DECISION_ACKS = "da"
    const val FIELD_DECISION_ACK_CONFIRMATIONS = "daa"
    const val FIELD_ATTENDANCE_ACK_CONFIRMATIONS = "haa"
    const val FIELD_INVENTORY_ACK_CONFIRMATIONS = "gaa"
    const val FIELD_REPORT_ACK_CONFIRMATIONS = "baa"
    const val FIELD_ADMIN_DECISION_ACKS = "ad"
    const val FIELD_ADMIN_DECISION_ACK_CONFIRMATIONS = "ada"
    const val FIELD_ADMIN_DECISION_ACK_RECEIPTS = "adak"
    const val FIELD_NEXT_CURSOR = "n"
    const val FIELD_REPORT_PUBLIC_KEY = "rp"

    // Controles de transporte: nunca se presentan como texto literal en la interfaz.
    const val SYNC = "[SYNC]"
    const val ATTENDANCE_SYNC = "[AT_SYNC]"
    const val INVENTORY_SYNC = "[IN_SYNC]"
    const val APPROVED = "[OK]"
    const val REJECTED = "[NO]"

    const val LOAN = "[P$]"
    const val CONSUMPTION = "[C$]"
    const val NO_RECORD = "[SR]"
    const val ABSENCE = "[F]"
    const val IN_PROGRESS = "[EC]"
    const val FULL_SHIFT = "[CO]"
    const val EARLY_CLOSE = "[CA]"
    const val ADMIN_CLOSE = "[AD]"
    const val DAMAGED = "[PD]"
    const val EXPIRED = "[PC]"
    const val WASTE = "[ME]"
    const val RETURN = "[DV]"
    const val STOCK_IN = "[EA]"
    const val STOCK_OUT = "[SA]"
    const val INVENTORY_ADJUSTMENT = "[AJ]"
    const val OUT_OF_STOCK = "[AG]"

    private val labels = mapOf(
        LOAN to "Préstamo solicitado",
        CONSUMPTION to "Consumo solicitado",
        NO_RECORD to "Aún no tenía registro",
        ABSENCE to "Falta",
        IN_PROGRESS to "En curso",
        FULL_SHIFT to "Jornada completa",
        EARLY_CLOSE to "Cierre anticipado",
        ADMIN_CLOSE to "Cierre administrativo",
        DAMAGED to "Producto dañado",
        EXPIRED to "Producto caducado",
        WASTE to "Merma",
        RETURN to "Devolución",
        STOCK_IN to "Entrada de almacén",
        STOCK_OUT to "Salida de almacén",
        INVENTORY_ADJUSTMENT to "Ajuste de inventario",
        OUT_OF_STOCK to "Producto agotado",
        APPROVED to "Autorizada",
        REJECTED to "Rechazada",
        SYNC to "Sincronización",
        ATTENDANCE_SYNC to "Sincronización de asistencia",
        INVENTORY_SYNC to "Sincronización de inventario"
    )

    private val weekdays = mapOf(
        "L" to "Lunes", "M" to "Martes", "MI" to "Miércoles",
        "J" to "Jueves", "V" to "Viernes", "S" to "Sábado", "D" to "Domingo"
    )

    fun payDayCode(day: String): String? = when(day.trim().lowercase()) {
        "lunes" -> "[DP][L]"
        "martes" -> "[DP][M]"
        "miércoles", "miercoles" -> "[DP][MI]"
        "jueves" -> "[DP][J]"
        "viernes" -> "[DP][V]"
        "sábado", "sabado" -> "[DP][S]"
        "domingo" -> "[DP][D]"
        else -> null
    }

    fun displayLabel(code: String): String =
        labels[code] ?: if (code.startsWith("[DP][")) {
            val key = code.removePrefix("[DP][").removeSuffix("]")
            weekdays[key]?.let { "Día de pago: $it" } ?: "Día de pago"
        } else "Movimiento"

    /** Sobre compacto común: v=versión, t=tipo, e=evento, w=trabajador, a=importe en centavos. */
    fun request(type: String, eventId: String, workerId: String, amountCents: Long): JSONObject {
        require(type == LOAN || type == CONSUMPTION)
        require(eventId.isNotBlank() && workerId.isNotBlank() && amountCents >= 0)
        return JSONObject().put(FIELD_VERSION, VERSION).put(FIELD_TYPE, type).put(FIELD_EVENT_ID, eventId)
            .put(FIELD_WORKER_ID, workerId).put(FIELD_AMOUNT_CENTS, amountCents)
    }

    fun isKnown(code: String): Boolean = code in labels || code in setOf(SYNC, ATTENDANCE_SYNC, INVENTORY_SYNC) ||
        code in setOf("[DP][L]", "[DP][M]", "[DP][MI]", "[DP][J]", "[DP][V]", "[DP][S]", "[DP][D]")
}
