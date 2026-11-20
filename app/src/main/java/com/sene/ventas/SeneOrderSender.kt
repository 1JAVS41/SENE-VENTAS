package com.sene.ventas

import android.app.Activity
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Envía órdenes cifradas al WhatsApp del patrón con el mismo sobre SENE-ADMIN-REPORT|1. */
object SeneOrderSender {
    fun send(activity: Activity, workerId: String, workerName: String, type: String,
             description: String, amountCents: Long, orderId: String): Boolean {
        return try {
            val now = Date()
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(now)
            val body = listOf("SENE-ORDER|1", "ORDER_ID=$orderId", "ORDER_TYPE=$type",
                "WORKER_ID=$workerId", "WORKER_NAME=$workerName", "DATE=$date", "TIME=$time",
                "AMOUNT_CENTS=$amountCents", "DESCRIPTION=$description").joinToString("\n")
            val message = ReportCrypto.prepare(activity, workerId, "ORDEN_$type", body, "ORDER:$orderId")
            var target = Store.phone(activity).filter { it.isDigit() }
            if (target.isBlank()) return false
            if (target.length == 10) target = "52$target"
            if (target.startsWith("521") && target.length == 13) target = "52" + target.substring(3)
            if (target.length < 12) return false
            val encoded = URLEncoder.encode(message, "UTF-8")
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
