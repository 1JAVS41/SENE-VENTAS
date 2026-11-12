package com.sene.ventas

/**
 * Convierte importes escritos con separadores habituales en México.
 * Acepta 1,234.56 y 1.234,56 sin confundir el separador de miles.
 */
internal fun parseAmount(raw: String): Double? {
    val value = raw.trim()
        .replace("$", "")
        .replace(" ", "")
        .replace("\u00A0", "")
    if (value.isEmpty()) return null

    val comma = value.lastIndexOf(',')
    val dot = value.lastIndexOf('.')
    val normalized = when {
        comma >= 0 && dot >= 0 && comma > dot ->
            value.replace(".", "").replace(',', '.')
        comma >= 0 && dot >= 0 ->
            value.replace(",", "")
        comma >= 0 -> {
            val decimals = value.length - comma - 1
            if (decimals in 1..2) value.replace(',', '.') else value.replace(",", "")
        }
        else -> value
    }
    return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
}

/** Importe de apertura válido: finito y nunca negativo. */
internal fun parseOpeningCash(raw: String): Double? =
    parseAmount(raw)?.takeIf { it >= 0.0 }
