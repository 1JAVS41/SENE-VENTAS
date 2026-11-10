package com.sene.ventas

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal fun Activity.installSeneAdvisor() {
    val prefs = getSharedPreferences("sene_consejero", Context.MODE_PRIVATE)
    val content = findViewById<FrameLayout>(android.R.id.content) ?: return
    if (content.findViewWithTag<View>("sene_consejero_bubble") != null) return

    val bubbleWidth = advisorDp(118)
    val bubbleHeight = advisorDp(82)
    val margin = advisorDp(12)
    val bubble = SeneRobotBubble(this).apply {
        tag = "sene_consejero_bubble"
        contentDescription = "IA Consejero SENE. Mover o abrir"
        isClickable = true
    }
    val params = FrameLayout.LayoutParams(bubbleWidth, bubbleHeight, Gravity.TOP or Gravity.LEFT)
    content.addView(bubble, params)
    bubble.post {
        val maxX = max(margin, content.width - bubbleWidth - margin)
        val maxY = max(margin, content.height - bubbleHeight - margin - advisorDp(76))
        params.leftMargin = prefs.getInt("bubble_x", maxX).coerceIn(margin, maxX)
        params.topMargin = prefs.getInt("bubble_y", maxY).coerceIn(margin, maxY)
        bubble.layoutParams = params
    }

    var downRawX = 0f
    var downRawY = 0f
    var startX = 0
    var startY = 0
    var moved = false
    val slop = (8 * resources.displayMetrics.density).toInt()
    bubble.setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = params.leftMargin
                startY = params.topMargin
                moved = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downRawX).toInt()
                val dy = (event.rawY - downRawY).toInt()
                if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) moved = true
                if (moved) {
                    val maxX = max(margin, content.width - view.width - margin)
                    val maxY = max(margin, content.height - view.height - margin - advisorDp(76))
                    params.leftMargin = (startX + dx).coerceIn(margin, maxX)
                    params.topMargin = (startY + dy).coerceIn(margin, maxY)
                    view.layoutParams = params
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (moved) {
                    prefs.edit().putInt("bubble_x", params.leftMargin).putInt("bubble_y", params.topMargin).apply()
                } else {
                    view.performClick()
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> true
            else -> true
        }
    }
    bubble.setOnClickListener {
        bubble.animate().cancel()
        bubble.animate().scaleX(1.22f).scaleY(1.22f).rotation(9f).setDuration(115)
            .withEndAction {
                bubble.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(180).start()
                showSeneAdvice(this)
            }.start()
    }
}

private fun showSeneAdvice(activity: Activity) {
    val products = Store.products(activity)
    val sales = Store.sales(activity)
    val advice = mutableListOf<String>()
    val low = mutableListOf<String>()
    val today = SimpleDateFormat("dd/MM/yyyy", Locale("es", "MX")).format(Date())
    val soldByCode = mutableMapOf<String, Int>()
    var soldToday = 0
    var revenueToday = 0.0
    for (i in 0 until sales.length()) {
        val sale = sales.optJSONObject(i) ?: continue
        if (sale.optString("time").startsWith(today)) {
            soldToday += sale.optInt("qty", 1)
            revenueToday += sale.optDouble("subtotal")
            val code = sale.optString("code")
            if (code.isNotBlank()) soldByCode[code] = (soldByCode[code] ?: 0) + sale.optInt("qty", 1)
        }
    }
    for (i in 0 until products.length()) {
        val product = products.optJSONObject(i) ?: continue
        val stock = product.optInt("stock")
        if (stock <= 3) low.add(product.optString("name", "Producto") + ": quedan " + stock)
    }
    if (low.isNotEmpty()) advice.add("INVENTARIO POR REVISAR\n" + low.take(5).joinToString("\n"))
    val best = soldByCode.maxByOrNull { it.value }
    if (best != null) {
        val name = (0 until products.length()).mapNotNull { products.optJSONObject(it) }
            .firstOrNull { it.optString("code") == best.key }?.optString("name") ?: best.key
        advice.add("MAYOR MOVIMIENTO\n" + name + ": " + best.value + " piezas vendidas hoy.")
    }
    if (soldToday > 0) advice.add("RESUMEN DE HOY\n" + soldToday + " piezas vendidas · " + Store.money(revenueToday) + ".")
    if (advice.isEmpty()) advice.add("Estoy listo para revisar tus ventas y existencias. Conforme registres movimientos te señalaré productos con poco stock y tendencias de venta.")
    val body = advice.joinToString("\n\n") + "\n\nAnalizo únicamente los datos guardados en SENE-VENTAS."
    val thought = when {
        low.isNotEmpty() -> "¡Atención! Detecté productos que están por agotarse. ¡Vamos a revisarlos!"
        best != null -> "¡Buen trabajo! Ya detecté qué producto lleva el mayor movimiento de hoy."
        soldToday > 0 -> "¡Así vamos hoy! Tengo un resumen de tus ventas listo para ti."
        else -> "¡Estoy listo! Vigilo tus ventas y existencias para darte consejos útiles."
    }

    val density = activity.resources.displayMetrics.density
    fun d(n: Int) = (n * density).toInt()
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(d(16), d(14), d(16), d(12))
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF17264A.toInt(), 0xFF263D70.toInt())
        ).apply { cornerRadius = d(22).toFloat() }
    }
    root.addView(TextView(activity).apply {
        text = "CONSEJERO SENE"
        textSize = 22f
        letterSpacing = .09f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
    }, LinearLayout.LayoutParams(-1, d(32)))

    // Composición vertical: el robot queda completo arriba y el globo debajo,
    // sin superponerse ni tapar ninguna parte del personaje.
    val stage = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(Color.TRANSPARENT)
    }
    val robot = SeneRobotPortrait(activity)
    stage.addView(robot, LinearLayout.LayoutParams(d(190), d(238)).apply {
        gravity = Gravity.CENTER_HORIZONTAL
    })
    val comic = ComicThoughtView(activity, thought)
    stage.addView(comic, LinearLayout.LayoutParams(-1, d(186)).apply {
        topMargin = d(2)
    })
    root.addView(stage, LinearLayout.LayoutParams(-1, d(428)))

    val scroll = ScrollView(activity).apply {
        isFillViewport = false
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = true
    }
    scroll.addView(TextView(activity).apply {
        text = body
        textSize = 17.5f
        setTextColor(0xFFEAF1FF.toInt())
        setLineSpacing(d(6).toFloat(), 1.08f)
        setPadding(d(8), d(10), d(8), d(8))
    })
    root.addView(scroll, LinearLayout.LayoutParams(-1, d(174)))

    lateinit var dialog: AlertDialog
    val chatButton = android.widget.Button(activity).apply {
        text = "CHATEAR CON SENE IA"
        textSize = 15f
        isAllCaps = false
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFF4267B8.toInt(), 0xFF243D78.toInt())
        ).apply { cornerRadius = d(14).toFloat() }
        elevation = d(4).toFloat()
        setPadding(d(16), d(12), d(16), d(12))
        setOnClickListener {
            dialog.dismiss()
            activity.showSeneChat()
        }
    }
    root.addView(chatButton, LinearLayout.LayoutParams(-1, d(52)).apply { topMargin = d(8) })

    dialog = AlertDialog.Builder(activity)
        .setView(root)
        .setPositiveButton("ENTENDIDO", null)
        .create()
    dialog.setOnShowListener {
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(activity.resources.displayMetrics.widthPixels - d(28), -2)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            text = "ENTENDIDO"
            textSize = 17f
            letterSpacing = .08f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(d(28), d(13), d(28), d(13))
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0xFF102346.toInt(), 0xFF102346.toInt())
                ).apply { cornerRadius = d(17).toFloat() },
                android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0xFFFFD66B.toInt(), 0xFFFFA52F.toInt(), 0xFFCB5D18.toInt())
                ).apply { cornerRadius = d(15).toFloat(); setStroke(d(1), 0xFFFFE6A6.toInt()) }
            )).apply { setLayerInset(1, d(2), d(1), d(2), d(5)) }
            elevation = d(9).toFloat()
            setShadowLayer(d(2).toFloat(), 0f, d(2).toFloat(), 0xFF07152E.toInt())
        }
    }
    dialog.show()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.window?.setLayout(activity.resources.displayMetrics.widthPixels - d(28), -2)
}

/** Robot metálico con volumen, reflejos, visor de cristal y detalles de circuito. */
private object SeneRobotPainter {
    fun draw(canvas: Canvas, width: Float, height: Float, phase: Float = 0f) {
        val save = canvas.save()
        canvas.scale(width / 200f, height / 240f)
        val bob = sin(phase * Math.PI * 2.0).toFloat()
        val breathing = sin(phase * Math.PI * 4.0).toFloat()
        canvas.translate(0f, bob * 2.8f)
        canvas.rotate(sin(phase * Math.PI * 2.0).toFloat() * 1.65f + sin(phase * Math.PI * 4.0).toFloat() * .35f, 100f, 120f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun solid(c: Int) { p.shader = null; p.clearShadowLayer(); p.style = Paint.Style.FILL; p.color = c }
        fun grad(r: RectF, colors: IntArray, horizontal: Boolean = false) {
            p.style = Paint.Style.FILL
            p.shader = if (horizontal) LinearGradient(r.left, r.top, r.right, r.top, colors, null, Shader.TileMode.CLAMP)
            else LinearGradient(r.left, r.top, r.left, r.bottom, colors, null, Shader.TileMode.CLAMP)
            p.clearShadowLayer()
        }
        fun rr(r: RectF, radius: Float) = canvas.drawRoundRect(r, radius, radius, p)

        // Sombra y hombros: cuerpo compacto, con piezas independientes.
        solid(0x55030A1C); canvas.drawOval(25f, 211f, 178f, 237f, p)
        p.setShadowLayer(10f, 0f, 6f, 0x99000000.toInt())
        grad(RectF(37f, 150f, 163f, 232f), intArrayOf(0xFFB9D9F4.toInt(), 0xFF527EB2.toInt(), 0xFF172F58.toInt()))
        canvas.drawRoundRect(37f, 150f, 163f, 232f, 36f, 36f, p); p.clearShadowLayer()
        canvas.save(); canvas.rotate(sin(phase * Math.PI * 2.0).toFloat() * 7f, 41f, 165f)
        grad(RectF(20f, 162f, 62f, 206f), intArrayOf(0xFFE9F7FF.toInt(), 0xFF6D9BC9.toInt(), 0xFF203E6C.toInt()))
        canvas.drawRoundRect(20f, 162f, 62f, 206f, 17f, 17f, p); canvas.restore()
        canvas.save(); canvas.rotate(-sin(phase * Math.PI * 2.0).toFloat() * 7f, 159f, 165f)
        grad(RectF(138f, 162f, 180f, 206f), intArrayOf(0xFFE9F7FF.toInt(), 0xFF6D9BC9.toInt(), 0xFF203E6C.toInt()))
        canvas.drawRoundRect(138f, 162f, 180f, 206f, 17f, 17f, p); canvas.restore()

        // Cabeza y laterales de aleación.
        p.setShadowLayer(12f, 0f, 8f, 0xCC000000.toInt())
        grad(RectF(25f, 35f, 175f, 176f), intArrayOf(0xFFF4FBFF.toInt(), 0xFF9EBEDC.toInt(), 0xFF456B9D.toInt(), 0xFF152D54.toInt()))
        canvas.drawRoundRect(25f, 35f, 175f, 176f, 47f, 47f, p); p.clearShadowLayer()
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f
        p.shader = LinearGradient(29f, 36f, 169f, 174f, intArrayOf(Color.WHITE, 0xFF7EA8D6.toInt(), 0xFF10264A.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(27f, 37f, 173f, 174f, 44f, 44f, p)
        p.shader = null; p.style = Paint.Style.FILL

        // Orejas mecánicas y tornillos.
        grad(RectF(9f, 84f, 35f, 133f), intArrayOf(0xFFFFD98B.toInt(), 0xFFB96E28.toInt(), 0xFF6A3D1B.toInt()))
        canvas.drawRoundRect(9f, 84f, 35f, 133f, 11f, 11f, p)
        grad(RectF(165f, 84f, 191f, 133f), intArrayOf(0xFFFFD98B.toInt(), 0xFFB96E28.toInt(), 0xFF6A3D1B.toInt()))
        canvas.drawRoundRect(165f, 84f, 191f, 133f, 11f, 11f, p)
        solid(0xFFFFE6B0.toInt()); canvas.drawCircle(22f, 108f, 4f, p); canvas.drawCircle(178f, 108f, 4f, p)

        // Antena e indicador.
        p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 6f
        solid(0xFFB77B37.toInt()); canvas.drawLine(100f, 16f, 100f, 37f, p)
        p.setShadowLayer(8f, 0f, 0f, 0xFFFFB94E.toInt())
        val antennaPulse = 10.5f + (breathing + 1f) * 2.2f
        grad(RectF(100f-antennaPulse, 16f-antennaPulse, 100f+antennaPulse, 16f+antennaPulse), intArrayOf(0xFFFFF0B5.toInt(), 0xFFFFB33D.toInt(), 0xFF9B4E16.toInt()))
        canvas.drawCircle(100f, 16f, antennaPulse, p); p.clearShadowLayer()
        solid(Color.WHITE); canvas.drawCircle(96f, 11f, 3f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = 0x99FFD77A.toInt()
        canvas.drawCircle(100f, 16f, antennaPulse + 4f + (breathing + 1f) * 1.5f, p); p.style = Paint.Style.FILL

        // Visor negro curvo con borde cromado.
        p.setShadowLayer(8f, 0f, 4f, 0xCC000000.toInt())
        grad(RectF(39f, 75f, 161f, 137f), intArrayOf(0xFF203B60.toInt(), 0xFF071629.toInt(), 0xFF020914.toInt()))
        canvas.drawRoundRect(39f, 75f, 161f, 137f, 24f, 24f, p); p.clearShadowLayer()
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0xFFB7D6F1.toInt()
        canvas.drawRoundRect(40f, 76f, 160f, 136f, 23f, 23f, p); p.style = Paint.Style.FILL

        // Reflejos curvos del cristal del visor y microdetalles ópticos.
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.strokeCap = Paint.Cap.ROUND
        p.color = 0x667FEAFF.toInt()
        canvas.drawArc(47f, 79f, 153f, 112f, 198f, 142f, false, p)
        p.color = 0x66FFFFFF.toInt()
        canvas.drawLine(52f, 83f, 67f, 79f, p)
        canvas.drawLine(133f, 79f, 148f, 83f, p)
        p.style = Paint.Style.FILL
        // Ojos con brillo interno y reflejo de cristal.
        val blinking = phase in .18f.. .225f || phase in .70f.. .745f
        val eyeScale = if (blinking) .12f else 1f
        val eyeTop = 105f - 14f * eyeScale
        val eyeBottom = 105f + 14f * eyeScale
        val gaze = sin(phase * Math.PI * 2.0 * 1.5).toFloat() * 3.2f
        for (x in listOf(70f, 130f)) {
            p.setShadowLayer(14f + (breathing + 1f) * 2f, 0f, 0f, 0xFF36D8FF.toInt())
            grad(RectF(x - 13f, eyeTop, x + 13f, eyeBottom), intArrayOf(0xFFE7FFFF.toInt(), 0xFF48DFFF.toInt(), 0xFF0874C8.toInt()))
            canvas.drawRoundRect(x - 13f, eyeTop, x + 13f, eyeBottom, 7f, 7f, p); p.clearShadowLayer()
            if (!blinking) {
                solid(0xFFFFFFFF.toInt()); canvas.drawCircle(x - 4f + gaze, 97f, 4f, p)
                solid(0xFF073D68.toInt()); canvas.drawCircle(x + gaze, 105f, 3.2f, p)
            }
        }
        p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 3f; p.color = 0xFF75E8FF.toInt()
        canvas.drawArc(84f, 111f, 116f, 128f, 12f, 156f, false, p)

        // Microplacas laterales, biseles y tornillos mecanizados de la carcasa.
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.8f
        p.color = 0xFFDAEEFF.toInt()
        canvas.drawArc(30f, 47f, 72f, 91f, 190f, 105f, false, p)
        canvas.drawArc(128f, 47f, 170f, 91f, 245f, 105f, false, p)
        p.color = 0xFF29496F.toInt(); p.strokeWidth = 2.2f
        canvas.drawLine(31f, 139f, 43f, 151f, p)
        canvas.drawLine(169f, 139f, 157f, 151f, p)
        p.style = Paint.Style.FILL
        for ((x, y) in listOf(35f to 61f, 165f to 61f, 34f to 148f, 166f to 148f)) {
            p.setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
            grad(RectF(x - 3.5f, y - 3.5f, x + 3.5f, y + 3.5f), intArrayOf(0xFFFFFFFF.toInt(), 0xFF7898B8.toInt(), 0xFF233C5E.toInt()))
            canvas.drawCircle(x, y, 3.5f, p); p.clearShadowLayer()
            solid(0xFF304A68.toInt()); canvas.drawLine(x - 1.8f, y, x + 1.8f, y, p)
        }

        // Circuito IC en la frente.
        p.setShadowLayer(5f, 0f, 2f, 0xAAFFB33E.toInt())
        grad(RectF(85f, 43f, 115f, 68f), intArrayOf(0xFFFFF0B8.toInt(), 0xFFFFC65C.toInt(), 0xFF9C5A20.toInt()))
        canvas.drawRoundRect(85f, 43f, 115f, 68f, 5f, 5f, p); p.clearShadowLayer()
        solid(0xFF75471F.toInt())
        for (x in listOf(91f, 100f, 109f)) { canvas.drawLine(x, 37f, x, 43f, p); canvas.drawLine(x, 68f, x, 74f, p) }
        canvas.drawLine(91f, 50f, 109f, 50f, p); canvas.drawLine(91f, 56f, 109f, 56f, p); canvas.drawLine(91f, 62f, 109f, 62f, p)
        solid(0xFFFFF2C8.toInt()); canvas.drawCircle(100f, 56f, 2.5f, p)

        // Uniones mecánicas, ranuras de ventilación y tornillería del torso.
        solid(0xFF142B4B.toInt())
        for (x in listOf(77f, 87f, 113f, 123f)) {
            canvas.drawRoundRect(x, 157f, x + 4f, 169f, 2f, 2f, p)
        }
        solid(0xFFB8D8F2.toInt())
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f
        canvas.drawArc(58f, 154f, 142f, 198f, 195f, 150f, false, p)
        p.style = Paint.Style.FILL
        // Cuello articulado con pistones y anillos de acero.
        grad(RectF(77f, 143f, 123f, 165f), intArrayOf(0xFFE8F5FF.toInt(), 0xFF52769C.toInt(), 0xFF172D4C.toInt()))
        canvas.drawRoundRect(77f, 143f, 123f, 165f, 8f, 8f, p)
        solid(0xFF172A45.toInt())
        canvas.drawRoundRect(84f, 147f, 89f, 162f, 2f, 2f, p)
        canvas.drawRoundRect(111f, 147f, 116f, 162f, 2f, 2f, p)
        solid(0xFFFFC56A.toInt()); canvas.drawCircle(86.5f, 145f, 2.5f, p); canvas.drawCircle(113.5f, 145f, 2.5f, p)

        // Placa del pecho con el nombre y remaches.
        p.setShadowLayer(7f, 0f, 4f, 0xAA000000.toInt())
        grad(RectF(48f, 177f, 152f, 218f), intArrayOf(0xFFE7F5FF.toInt(), 0xFF6D9BCF.toInt(), 0xFF1A3C70.toInt()))
        canvas.drawRoundRect(48f, 177f, 152f, 218f, 12f, 12f, p); p.clearShadowLayer()
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = 0xFFE4F4FF.toInt()
        canvas.drawRoundRect(50f, 179f, 150f, 216f, 10f, 10f, p); p.style = Paint.Style.FILL
        solid(0xFFFFFFFF.toInt()); p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); p.textSize = 15f
        p.setShadowLayer(3f, 0f, 2f, 0xFF07152E.toInt()); canvas.drawText("SENE VENTAS", 100f, 203f, p); p.clearShadowLayer()
        solid(0xFFFFC45A.toInt()); canvas.drawCircle(57f, 188f, 2f, p); canvas.drawCircle(143f, 188f, 2f, p)

        // Refuerzos de carcasa, rejillas de ventilación y luces de estado para
        // dar profundidad de metal mecanizado al torso sin cambiar la silueta.
        grad(RectF(58f, 207f, 142f, 226f), intArrayOf(0xFF24476F.toInt(), 0xFF0C1F3D.toInt(), 0xFF426B94.toInt()))
        canvas.drawRoundRect(58f, 207f, 142f, 226f, 6f, 6f, p)
        solid(0xFF9EC4E7.toInt())
        for (x in listOf(70f, 79f, 88f, 112f, 121f, 130f)) {
            canvas.drawRoundRect(x, 212f, x + 4f, 221f, 2f, 2f, p)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = 0xFFE8F7FF.toInt()
        canvas.drawLine(43f, 174f, 53f, 181f, p)
        canvas.drawLine(157f, 174f, 147f, 181f, p)
        canvas.drawArc(42f, 185f, 158f, 232f, 12f, 156f, false, p)
        p.style = Paint.Style.FILL
        // Luces de estado laterales con halo y pequeñas lentes.
        for ((index, pair) in listOf(39f to 194f, 161f to 194f).withIndex()) {
            val (x, y) = pair
            val ledOn = ((phase * 6f).toInt() + index) % 3 != 0
            p.setShadowLayer(if(ledOn) 7f else 2f, 0f, 0f, 0xFF43D9FF.toInt())
            solid(if(ledOn)0xFF56E7FF.toInt() else 0xFF285A79.toInt()); canvas.drawCircle(x, y, if(ledOn)3.8f else 2.8f, p)
            p.clearShadowLayer()
            if(ledOn){solid(Color.WHITE);canvas.drawCircle(x - 1f, y - 1f, 1.1f, p)}
        }
        // Remaches inferiores y bordes de las placas de los hombros.
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f
        p.color = 0xFFD9EEFF.toInt()
        canvas.drawArc(23f, 164f, 61f, 207f, 190f, 90f, false, p)
        canvas.drawArc(139f, 164f, 177f, 207f, 260f, 90f, false, p)
        p.style = Paint.Style.FILL
        solid(0xFF263F60.toInt())
        for (x in listOf(45f, 155f)) canvas.drawCircle(x, 224f, 2.4f, p)
        solid(0xFFD7E9F8.toInt())
        for (x in listOf(45f, 155f)) canvas.drawCircle(x - 0.7f, 223.3f, 0.8f, p)

        canvas.restoreToCount(save)
    }
}

private class SeneRobotBubble(context: Context) : View(context) {
    private var phase = 0f
    private var animator: ValueAnimator? = null
    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2650L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    override fun onDetachedFromWindow() {
        animator?.cancel(); animator = null
        super.onDetachedFromWindow()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val save = canvas.save()
        canvas.scale(width / 280f, height / 200f)
        val bob = sin(phase * Math.PI * 2.0).toFloat()
        canvas.translate(0f, bob * 3.2f)
        canvas.rotate(sin(phase * Math.PI * 2.0).toFloat() * 2.1f + sin(phase * Math.PI * 4.0).toFloat() * .7f, 84f, 100f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.setShadowLayer(13f, 0f, 6f, 0x99000000.toInt())
        p.color = 0xFF14284D.toInt()
        canvas.drawCircle(84f, 100f, 77f, p)
        p.clearShadowLayer()
        p.style = Paint.Style.STROKE; p.strokeWidth = 4f
        p.shader = LinearGradient(15f, 18f, 155f, 178f, intArrayOf(Color.WHITE, 0xFF62A5E8.toInt(), 0xFF10244A.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(84f, 100f, 74f, p)
        p.shader = null; p.style = Paint.Style.FILL
        canvas.save()
        canvas.translate(9f, 12f)
        SeneRobotPainter.draw(canvas, 150f, 180f, phase)
        canvas.restore()
        p.color = 0xAAFFFFFF.toInt()
        canvas.drawOval(36f, 29f, 59f, 37f, p)
        // Placa IA integrada al mismo lienzo: acompaña a la burbuja al arrastrarla.
        val iaPulse = (sin(phase * Math.PI * 2.0 * 2.0).toFloat() + 1f) * .5f
        p.setShadowLayer(7f + iaPulse * 5f, 0f, 3f, 0xAA000000.toInt())
        p.color = 0xFF102346.toInt()
        val tag = RectF(164f, 112f, 272f, 164f)
        canvas.drawRoundRect(tag, 20f, 20f, p)
        p.clearShadowLayer()
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f
        p.shader = LinearGradient(tag.left, tag.top, tag.right, tag.bottom, intArrayOf(0xFFFFE7A6.toInt(), 0xFFFFA52F.toInt(), 0xFF9B4E16.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(tag, 20f, 20f, p)
        p.shader = null; p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        p.textSize = 31f
        p.setShadowLayer(2f, 0f, 2f, 0xFF07152E.toInt())
        p.color = Color.WHITE
        canvas.drawText("IA", tag.centerX(), 147f, p)
        p.clearShadowLayer()
        canvas.restoreToCount(save)
    }
}

private class SeneRobotPortrait(context: Context) : View(context) {
    private var phase = 0f
    private var animator: ValueAnimator? = null
    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3100L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    override fun onDetachedFromWindow() {
        animator?.cancel(); animator = null
        super.onDetachedFromWindow()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        SeneRobotPainter.draw(canvas, width.toFloat(), height.toFloat(), phase)
    }
}

/** Globo de pensamiento estilo cómic con contorno entintado y tipografía de historieta. */
private class ComicThoughtView(context: Context, private val message: String) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.setShadowLayer(dp(5f), 0f, dp(3f), 0x88000000.toInt())
        p.color = 0xFFFFFFFF.toInt()
        val bubble = RectF(dp(5f), dp(5f), w - dp(5f), h - dp(27f))
        canvas.drawRoundRect(bubble, dp(25f), dp(25f), p)
        p.clearShadowLayer()
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(3.2f); p.color = 0xFF17213A.toInt()
        canvas.drawRoundRect(bubble, dp(25f), dp(25f), p)
        p.style = Paint.Style.FILL
        canvas.drawCircle(w * .29f, h - dp(18f), dp(8f), p)
        canvas.drawCircle(w * .24f, h - dp(5f), dp(4f), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(2.2f)
        canvas.drawCircle(w * .29f, h - dp(18f), dp(8f), p)
        canvas.drawCircle(w * .24f, h - dp(5f), dp(4f), p)
        p.style = Paint.Style.FILL
        p.color = 0xFFFFB844.toInt()
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        p.textSize = dp(21f)
        p.setShadowLayer(dp(1.5f), 0f, dp(1f), 0x884A2B00.toInt())
        canvas.drawText("¡OYE, PATRÓN!", w / 2f, dp(37f), p)
        p.clearShadowLayer()
        p.color = 0xFF17213A.toInt()
        p.typeface = Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
        p.textSize = dp(25f)
        val maxWidth = w - dp(42f)
        val lines = mutableListOf<String>()
        var remaining = message
        while (remaining.isNotEmpty() && lines.size < 4) {
            val count = p.breakText(remaining, true, maxWidth, null)
            if (count <= 0) break
            var end = count
            if (end < remaining.length) {
                val space = remaining.lastIndexOf(' ', end - 1)
                if (space > 0) end = space
            }
            lines.add(remaining.substring(0, end).trim())
            remaining = remaining.substring(end).trim()
        }
        if (remaining.isNotEmpty() && lines.isNotEmpty()) {
            val last = lines.last()
            val suffix = "…"
            var clipped = last
            while (clipped.isNotEmpty() && p.measureText(clipped + suffix) > maxWidth) clipped = clipped.dropLast(1)
            lines[lines.lastIndex] = clipped.trimEnd() + suffix
        }
        val lineHeight = dp(30f)
        val total = lines.size * lineHeight
        var y = dp(66f) + max(0f, (h - dp(92f) - total) / 2f)
        for (line in lines) {
            p.setShadowLayer(dp(1.5f), dp(1f), dp(1f), 0x33FFFFFF)
            canvas.drawText(line, w / 2f, y, p)
            p.clearShadowLayer()
            y += lineHeight
        }
    }
}

private fun Activity.advisorDp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
