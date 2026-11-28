package com.sene.ventas

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MuseConnectActivity : Activity() {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun panel() = GradientDrawable().apply {
        setColor(0xFFFFFFFF.toInt())
        cornerRadius = dp(14).toFloat()
        setStroke(dp(1), 0xFFDCE3F0.toInt())
    }
    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        textSize = 16f
        setSingleLine(true)
        setPadding(dp(15), dp(13), dp(15), dp(13))
        background = panel()
        setTextColor(0xFF17213D.toInt())
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
    private fun section(titleText: String) = TextView(this).apply {
        text = titleText
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xFF24447C.toInt())
        setPadding(0, dp(12), 0, dp(4))
    }
    private fun body(textValue: String, bold: Boolean = false) = TextView(this).apply {
        text = textValue
        textSize = 15f
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xFF17213D.toInt())
        setPadding(dp(2), dp(2), dp(2), dp(2))
    }
    private fun note(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 14f
        setTextColor(0xFF53627E.toInt())
        setPadding(dp(2), dp(6), dp(2), dp(2))
    }

    private lateinit var content: LinearLayout
    private var editingToken = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(26))
            setBackgroundColor(0xFFF3F6FC.toInt())
        }
        content.addView(TextView(this).apply {
            text = "CONECTAR MI MUSE"
            textSize = 25f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(0xFF173C7B.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
        })
        content.addView(body("Consultando tu licencia…"))
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        setContentView(scroll)
        checkLicense()
    }

    private fun parseLicenseExpiry(raw: String): Long {
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
        for (pattern in patterns) try {
            val parser = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val parsed = parser.parse(raw)
            if (parsed != null) return parsed.time
        } catch (_: Exception) {
        }
        return 0L
    }

    private fun checkLicense() {
        Thread {
            val savedExpiry = ActivationApi.savedLicenseExpiry(this)
            val savedMillis = parseLicenseExpiry(savedExpiry)
            val now = System.currentTimeMillis()
            val twoDaysMillis = 2L * 24L * 60L * 60L * 1000L
            val needsServer = savedMillis <= 0L || savedMillis <= now || savedMillis - now <= twoDaysMillis
            var active: Boolean
            var expiryMillis = savedMillis
            var business = ActivationApi.savedLicenseBusiness(this)
            if (needsServer) {
                val status = try {
                    ActivationApi.subscriptionStatus(this)
                } catch (_: Exception) {
                    null
                }
                if (status != null) {
                    val serverExpiry = parseLicenseExpiry(status.optString("expires_at"))
                    if (serverExpiry > 0L) expiryMillis = serverExpiry
                    val serverBusiness = status.optString("business_name")
                    if (serverBusiness.isNotBlank()) business = serverBusiness
                    active = if (status.has("active")) status.optBoolean("active", false)
                    else expiryMillis > System.currentTimeMillis()
                } else {
                    active = expiryMillis > System.currentTimeMillis()
                }
            } else {
                active = expiryMillis > now
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) draw(active, expiryMillis, business)
            }
        }.start()
    }

    private fun draw(active: Boolean, expiryMillis: Long, business: String) {
        // Keep only the title (first child); rebuild the rest.
        while (content.childCount > 1) content.removeViewAt(1)
        if (!active) {
            content.addView(body("tu licencia esta vencida realiza el pago para seguir usando los servicios de SENE-VENTAS"))
            content.addView(action("PAGAR MENSUALIDAD") {
                startActivity(Intent(this, ActivationActivity::class.java).putExtra("force_renewal", true))
            })
            return
        }
        if (business.isNotBlank()) {
            content.addView(body(business, true))
        }
        if (expiryMillis > 0L) {
            val dateText = SimpleDateFormat("dd/MM/yyyy", Locale("es", "MX")).format(Date(expiryMillis))
            content.addView(body("Licencia activa hasta $dateText"))
        }
        content.addView(section("Tu dirección de Muse"))
        val endpoint = ActivationApi.museEndpoint(this)
        content.addView(body(endpoint))
        content.addView(action("COPIAR", false) { copyToClipboard("Dirección de Muse", endpoint) })
        content.addView(section("Tu clave de Muse"))
        val savedToken = ActivationApi.savedMuseToken(this)
        if (savedToken.isBlank() || editingToken) {
            val tokenField = field("Pega aquí tu clave de Muse")
            content.addView(tokenField)
            content.addView(action("GUARDAR") {
                val token = tokenField.text.toString().trim()
                if (token.isBlank()) {
                    Toast.makeText(this, "Pega tu clave de Muse para guardarla.", Toast.LENGTH_LONG).show()
                    return@action
                }
                ActivationApi.saveMuseToken(this, token)
                editingToken = false
                Toast.makeText(this, "Clave guardada.", Toast.LENGTH_SHORT).show()
                draw(true, expiryMillis, business)
            })
        } else {
            val masked = if (savedToken.length >= 8) {
                savedToken.take(4) + "…" + savedToken.takeLast(4)
            } else {
                "••••"
            }
            content.addView(body(masked, true))
            content.addView(action("COPIAR", false) { copyToClipboard("Clave de Muse", savedToken) })
            content.addView(action("CAMBIAR CLAVE", false) {
                editingToken = true
                draw(true, expiryMillis, business)
            })
        }
        content.addView(note("Esta clave es solo tuya, no la compartas."))
        content.addView(section("Cómo conectar tu Muse"))
        content.addView(body("1. Abre tu Muse."))
        content.addView(body("2. Agrega una conexión nueva."))
        content.addView(body("3. Pega la dirección de arriba."))
        content.addView(body("4. Pega tu clave como contraseña Bearer."))
        content.addView(body("5. Pregúntale algo de tu negocio, por ejemplo cuánto vendiste hoy."))
        content.addView(note("Si tu licencia vence, tu Muse solo te pedirá el pago y no responderá nada del negocio."))
        content.addView(action("VOLVER", false) { finish() })
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copiado.", Toast.LENGTH_SHORT).show()
    }
}
