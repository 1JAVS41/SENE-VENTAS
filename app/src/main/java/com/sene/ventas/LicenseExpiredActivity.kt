package com.sene.ventas

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class LicenseExpiredActivity : Activity() {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun action(label: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        minHeight = dp(60)
        setTextColor(if (primary) Color.WHITE else 0xFF24447C.toInt())
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            if (primary) intArrayOf(0xFF168B45.toInt(), 0xFF087332.toInt()) else intArrayOf(Color.WHITE, Color.WHITE)
        ).apply { cornerRadius = dp(14).toFloat(); if (!primary) setStroke(dp(1), 0xFFD5DEEF.toInt()) }
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9); bottomMargin = dp(9) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val business = intent.getStringExtra("business_name").orEmpty()
        val expiry = intent.getStringExtra("expires_at").orEmpty()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(35), dp(22), dp(30))
            setBackgroundColor(0xFFF3F6FC.toInt())
        }
        root.addView(TextView(this).apply {
            text = "LICENCIA VENCIDA"
            textSize = 27f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(0xFFB3261E.toInt())
            setPadding(0, dp(18), 0, dp(18))
        })
        root.addView(TextView(this).apply {
            text = (if (business.isNotBlank()) "Negocio: $business\\n" else "") +
                (if (expiry.isNotBlank()) "Vencimiento: $expiry\\n\\n" else "\\n") +
                "La mensualidad de SENE VENTAS terminó. La aplicación está bloqueada hasta que renueves tu licencia."
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(0xFF263B65.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(24))
        })
        root.addView(action("PAGAR MENSUALIDAD · LICENCIA VENCIDA", true) {
            startActivity(Intent(this, ActivationActivity::class.java).putExtra("force_renewal", true))
            finish()
        })
        root.addView(action("CERRAR APLICACIÓN", false) { finishAffinity() })
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    override fun onBackPressed() {
        finishAffinity()
    }
}
