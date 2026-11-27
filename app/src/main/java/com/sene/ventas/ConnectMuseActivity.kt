package com.sene.ventas

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ConnectMuseActivity : Activity() {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private lateinit var status: TextView
    private lateinit var urlValue: TextView
    private lateinit var tokenValue: TextView
    private lateinit var ingestValue: TextView

    private fun label(value: String, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = 15f
        setTextColor(0xFF17213D.toInt())
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(4), dp(8), dp(4), dp(5))
    }

    private fun action(value: String, click: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 15f
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        }
    }

    private fun copy(value: String, title: String) {
        if (value.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(title, value))
        status.text = "$title copiado."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(22))
            setBackgroundColor(Color.WHITE)
        }
        root.addView(label("CONECTA TU MUSE", true).apply { textSize = 22f })
        root.addView(label("Conecta el MUSE de este negocio. La conexión solo funciona mientras la licencia de SENE VENTAS esté activa."))
        status = label("Consulta tu licencia para obtener los datos de conexión.")
        root.addView(status)
        root.addView(label("URL DEL CONECTOR", true))
        urlValue = label("Aún no disponible")
        root.addView(urlValue)
        root.addView(action("COPIAR URL") { copy(ActivationApi.savedMuseUrl(this), "URL") })
        root.addView(label("TOKEN MUSE · CONFIDENCIAL", true))
        tokenValue = label("Aún no disponible")
        root.addView(tokenValue)
        root.addView(action("COPIAR TOKEN MUSE") { copy(ActivationApi.savedMuseToken(this), "Token MUSE") })
        root.addView(label("TOKEN PARA SENEADMIN · CONFIGURACIÓN DE GUARDIÁN", true))
        ingestValue = label("Aún no disponible")
        root.addView(ingestValue)
        root.addView(action("COPIAR TOKEN PARA SENEADMIN") { copy(ActivationApi.savedGuardianIngestToken(this), "Token de SeneAdmin") })
        root.addView(label(
            "PASOS PARA CONECTAR\n1. Abre la configuración de conectores de MUSE.\n2. Crea un conector personalizado.\n3. Pega la URL y el TOKEN MUSE mostrados aquí.\n4. Guarda y prueba la conexión.\n\nPARA QUE MUSE RECIBA LOS DATOS DE SENEADMIN\nEn SeneAdmin abre SENE GUARDIÁN y coloca el mismo identificador de instalación de SENE VENTAS y el TOKEN PARA SENEADMIN. Autoriza el envío y pulsa ENVIAR CAPTURA AHORA.\n\nNo intercambies los dos tokens: cada uno tiene una función distinta."
        ))
        root.addView(action("COMPROBAR LICENCIA Y CONECTAR") { connect() })
        root.addView(action("VOLVER") { finish() })
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
        showSavedConnection()
    }

    private fun showSavedConnection() {
        val url = ActivationApi.savedMuseUrl(this)
        val token = ActivationApi.savedMuseToken(this)
        if (url.isNotBlank()) urlValue.text = url
        if (token.isNotBlank()) tokenValue.text = token
        val ingest = ActivationApi.savedGuardianIngestToken(this)
        if (ingest.isNotBlank()) ingestValue.text = ingest
    }

    private fun connect() {
        status.text = "Comprobando licencia…"
        Thread {
            val result = try {
                ActivationApi.connectMuse(this)
            } catch (_: Exception) {
                org.json.JSONObject().put("ok", false).put("message", "No se pudo conectar con SERVER-SENE. Revisa internet e inténtalo de nuevo.")
            }
            runOnUiThread {
                showSavedConnection()
                if (result.optBoolean("ok")) {
                    status.text = result.optString("message", "Conexión comprobada.")
                    if (result.optBoolean("already_connected") && ActivationApi.savedMuseToken(this).isBlank()) {
                        tokenValue.text = "El token se entregó anteriormente y no está guardado en este dispositivo. No se puede volver a mostrar."
                    }
                } else {
                    status.text = result.optString("message", result.optString("error", "No se pudo conectar MUSE."))
                }
            }
        }.start()
    }
}
