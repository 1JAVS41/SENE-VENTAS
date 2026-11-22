package com.sene.ventas

import android.app.Activity
import android.content.Intent
import android.net.Uri

/**
 * WhatsApp es únicamente un canal de mensaje libre al patrón.
 * Los datos de préstamos y consumos viajan por SERVER-SENE mediante la cola
 * durable de PayrollRequestSync; nunca se incluyen importes, motivos ni códigos
 * internos en el texto de WhatsApp.
 */
object SeneOrderSender {
    fun send(activity: Activity, workerId: String, workerName: String, type: String,
             description: String, amountCents: Long, orderId: String): Boolean {
        return try {
            var target = Store.phone(activity).filter { it.isDigit() }
            if (target.isBlank()) return false
            if (target.length == 10) target = "52$target"
            if (target.startsWith("521") && target.length == 13) target = "52" + target.substring(3)
            if (target.length < 12) return false

            val safeName = workerName.trim().ifBlank { "un trabajador" }
            val message = "Hola, soy $safeName. Necesito comunicarme contigo. ¿Puedes contactarme cuando tengas oportunidad?"
            val encoded = java.net.URLEncoder.encode(message, "UTF-8")
            val links = listOf(
                Uri.parse("https://api.whatsapp.com/send?phone=$target&text=$encoded"),
                Uri.parse("https://wa.me/$target?text=$encoded")
            )
            for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) for (uri in links) {
                try { activity.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)); return true }
                catch (_: Exception) { }
            }
            activity.startActivity(Intent(Intent.ACTION_VIEW, links.last()))
            true
        } catch (_: Exception) { false }
    }
}
