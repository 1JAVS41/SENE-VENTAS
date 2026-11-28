package com.sene.ventas

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import android.view.Gravity
import android.widget.*

class ActivationActivity : Activity() {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun panel() = GradientDrawable().apply {
        setColor(0xFFFFFFFF.toInt())
        cornerRadius = dp(14).toFloat()
        setStroke(dp(1), 0xFFDCE3F0.toInt())
    }
    private fun field(hintText: String, phone: Boolean = false) = EditText(this).apply {
        hint = hintText
        textSize = 16f
        setSingleLine(true)
        setPadding(dp(15), dp(13), dp(15), dp(13))
        background = panel()
        setTextColor(0xFF17213D.toInt())
        if (phone) inputType = android.text.InputType.TYPE_CLASS_PHONE
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(7) }
    }
    private fun action(label: String, primary: Boolean = true, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        minHeight = dp(54)
        setTextColor(if (primary) -1 else 0xFF24447C.toInt())
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            if (primary) intArrayOf(0xFF315BA5.toInt(), 0xFF173C7B.toInt())
            else intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())
        ).apply { cornerRadius = dp(13).toFloat(); if (!primary) setStroke(dp(1), 0xFFD5DEEF.toInt()) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7); bottomMargin = dp(7) }
        setOnClickListener { click() }
    }

    private lateinit var holder: EditText
    private lateinit var phone: EditText
    private lateinit var business: EditText
    private lateinit var type: RadioGroup
    private lateinit var submit: Button
    private lateinit var pending: Button
    private lateinit var countdownLabel: TextView
    private val countdownHandler = Handler(Looper.getMainLooper())
    private val countdownTick = object : Runnable {
        override fun run() {
            if (::countdownLabel.isInitialized) updateCountdown()
            countdownHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(26))
            setBackgroundColor(0xFFF3F6FC.toInt())
        }
        root.addView(TextView(this).apply {
            text = "VINCULAR SENE VENTAS"
            textSize = 25f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(0xFF173C7B.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
        })
        root.addView(TextView(this).apply {
            text = "Registra tu negocio o solicita la mensualidad. La suscripción comienza cuando el código de activación sea validado."
            textSize = 14f
            setTextColor(0xFF53627E.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(5), 0, dp(5), dp(12))
        })
        root.addView(TextView(this).apply {
            text = "TIPO DE SOLICITUD"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF24447C.toInt())
        })
        type = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(RadioButton(this@ActivationActivity).apply { id = 1; text = "ACTIVAR NEGOCIO NUEVO"; isChecked = true })
            addView(RadioButton(this@ActivationActivity).apply { id = 2; text = "PAGAR MENSUALIDAD" })
        }
        if (intent.getBooleanExtra("force_renewal", false)) {
            type.check(2)
            for (i in 0 until type.childCount) type.getChildAt(i).isEnabled = false
        }
        root.addView(type)
        holder = field("Nombre del titular")
        phone = field("Teléfono con WhatsApp", true)
        business = field("Nombre del negocio")
        root.addView(holder); root.addView(phone); root.addView(business)
        submit = action("ENVIAR SOLICITUD") { sendRequest() }
        root.addView(submit)
        pending = action("YA REALICÉ EL PAGO", false) { openPayment() }
        root.addView(pending)
        countdownLabel = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFF24447C.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = panel()
            visibility = android.view.View.GONE
        }
        root.addView(countdownLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(8) })
        root.addView(action("VOLVER", false) { finish() })
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
        pending.visibility = android.view.View.VISIBLE
        updateRequestFormLock()
    }

    override fun onResume() {
        super.onResume()
        if (::pending.isInitialized) {
            val hasPending = ActivationApi.pendingFolio(this).isNotBlank()
            updateRequestFormLock()
            pending.visibility = android.view.View.VISIBLE
            countdownLabel.visibility = if (hasPending) android.view.View.VISIBLE else android.view.View.GONE
            countdownHandler.removeCallbacks(countdownTick)
            if (hasPending) countdownHandler.post(countdownTick)
        }
    }

    override fun onPause() {
        countdownHandler.removeCallbacks(countdownTick)
        super.onPause()
    }

    override fun onDestroy() {
        countdownHandler.removeCallbacks(countdownTick)
        super.onDestroy()
    }

    private fun pendingExpiryMillis(): Long {
        val raw = ActivationApi.pendingExpiresAt(this)
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(raw)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    private fun updateRequestFormLock() {
        val hasPending = ActivationApi.pendingFolio(this).isNotBlank()
        val expiry = pendingExpiryMillis()
        // If a folio exists but its expiry cannot be read, keep the form locked
        // rather than risk creating a duplicate request.
        val locked = hasPending && (expiry <= 0L || expiry > System.currentTimeMillis())
        holder.isEnabled = !locked
        phone.isEnabled = !locked
        business.isEnabled = !locked
        val forceRenewal = intent.getBooleanExtra("force_renewal", false)
        type.isEnabled = !locked && !forceRenewal
        for (i in 0 until type.childCount) type.getChildAt(i).isEnabled = !locked && !forceRenewal
        submit.isEnabled = !locked
        submit.text = if (locked) "SOLICITUD EN CURSO" else if (intent.getBooleanExtra("force_renewal", false)) "PAGAR MENSUALIDAD · LICENCIA VENCIDA" else "ENVIAR SOLICITUD"
    }

    private fun updateCountdown() {
        val raw = ActivationApi.pendingExpiresAt(this)
        val expiry = pendingExpiryMillis()
        if (expiry <= 0L) {
            countdownLabel.text = "Solicitud registrada. Consulta tu folio para continuar."
            return
        }
        val remaining = expiry - System.currentTimeMillis()
        if (remaining <= 0L) {
            countdownLabel.text = "SOLICITUD VENCIDA. Puedes generar una nueva solicitud."
            updateRequestFormLock()
            return
        }
        val totalSeconds = remaining / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        countdownLabel.text = String.format(Locale.US, "TIEMPO RESTANTE: %02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun sendRequest() {
        updateRequestFormLock()
        if (submit.isEnabled.not()) {
            AlertDialog.Builder(this).setTitle("SOLICITUD EN CURSO")
                .setMessage("Ya tienes una solicitud activa. No puedes registrar otra ni cambiar los datos hasta que termine el contador de 48 horas. Usa YA REALICÉ EL PAGO para ingresar el código cuando lo recibas.")
                .setPositiveButton("ENTENDIDO", null).show()
            return
        }
        val holderText = holder.text.toString().trim()
        val phoneText = phone.text.toString().filter { it.isDigit() }
        val businessText = business.text.toString().trim()
        if (holderText.length < 2 || phoneText.length !in 10..15 || businessText.length < 2) {
            AlertDialog.Builder(this).setMessage("Completa el nombre del titular, un WhatsApp válido y el nombre del negocio.")
                .setPositiveButton("ENTENDIDO", null).show()
            return
        }
        submit.isEnabled = false
        submit.text = "ENVIANDO SOLICITUD…"
        val requestType = if (type.checkedRadioButtonId == 2) "renewal" else "activation"
        Thread {
            val result = try {
                ActivationApi.createRequest(this, requestType, holderText, phoneText, businessText)
            } catch (_: Exception) {
                org.json.JSONObject().put("ok", false).put("message", "No se pudo conectar con SERVER-SENE. Revisa tu conexión e inténtalo de nuevo.")
            }
            runOnUiThread {
                submit.isEnabled = true
                submit.text = if (intent.getBooleanExtra("force_renewal", false)) "PAGAR MENSUALIDAD · LICENCIA VENCIDA" else "ENVIAR SOLICITUD"
                if (result.optBoolean("ok")) {
                    val folio = result.optString("folio")
                    val amount = result.optInt("amount_cents", 10000)
                    val expires = result.optString("request_expires_at")
                    ActivationApi.savePending(this, folio, amount, expires)
                    val amountText = String.format(java.util.Locale.US, "%.2f", amount / 100.0)
                    AlertDialog.Builder(this)
                        .setTitle("SOLICITUD REGISTRADA")
                        .setMessage("Folio: $folio\nImporte: " + "$" + amountText + " MXN\n\nTienes 48 horas para realizar el pago y enviar el comprobante. La administración SENE te hará llegar tus datos de acceso por WhatsApp una vez que envíes el comprobante de pago.")
                        .setPositiveButton("CONTINUAR AL PAGO") { _, _ -> openPayment() }
                        .setNegativeButton("MÁS TARDE", null)
                        .show()
                    pending.visibility = android.view.View.VISIBLE
                    countdownLabel.visibility = android.view.View.VISIBLE
                    updateRequestFormLock()
                    updateCountdown()
                    countdownHandler.removeCallbacks(countdownTick)
                    countdownHandler.post(countdownTick)
                } else if (result.optString("error") == "SOLICITUD_ACTIVA" && result.optString("folio").isNotBlank()) {
                    val folio = result.optString("folio")
                    ActivationApi.savePending(this, folio, result.optInt("amount_cents", 10000), result.optString("expires_at"))
                    pending.visibility = android.view.View.VISIBLE
                    countdownLabel.visibility = android.view.View.VISIBLE
                    updateRequestFormLock()
                    updateCountdown()
                    countdownHandler.removeCallbacks(countdownTick)
                    countdownHandler.post(countdownTick)
                    AlertDialog.Builder(this).setTitle("YA EXISTE UNA SOLICITUD")
                        .setMessage("Folio: $folio\nTu solicitud sigue dentro de las 48 horas. No necesitas crear otra.")
                        .setPositiveButton("VER DATOS DE PAGO") { _, _ -> openPayment() }
                        .setNegativeButton("MÁS TARDE", null).show()
                } else {
                    val message = result.optString("message", result.optString("error", "No se pudo registrar la solicitud."))
                    AlertDialog.Builder(this).setTitle("No se pudo completar")
                        .setMessage(message).setPositiveButton("ENTENDIDO", null).show()
                }
            }
        }.start()
    }

    private fun openPayment() {
        val folio = ActivationApi.pendingFolio(this)
        startActivity(Intent(this, PaymentInfoActivity::class.java)
            .putExtra("folio", folio)
            .putExtra("amount_cents", ActivationApi.pendingAmount(this)))
    }
}
