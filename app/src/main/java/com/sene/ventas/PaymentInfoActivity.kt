package com.sene.ventas

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class PaymentInfoActivity : Activity() {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int, stroke: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun requestCodeDialog() {
        val folio = intent.getStringExtra("folio") ?: ActivationApi.pendingFolio(this)
        if (folio.isBlank()) {
            AlertDialog.Builder(this).setTitle("Falta el folio")
                .setMessage("Primero registra una solicitud desde VINCULAR SENE VENTAS.")
                .setPositiveButton("ENTENDIDO", null).show()
            return
        }
        val codeField = EditText(this).apply {
            hint = "Código de activación de 10 caracteres"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setPadding(dp(16), dp(13), dp(16), dp(13))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Validar activación")
            .setMessage("Folio: $folio\nEscribe el código que recibiste después de que el responsable verificó tu pago.")
            .setView(codeField)
            .setPositiveButton("VALIDAR CÓDIGO", null)
            .setNegativeButton("CANCELAR", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = codeField.text.toString().trim().uppercase()
                if (code.length != 10) {
                    codeField.error = "El código debe tener 10 caracteres"
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                Thread {
                    val result = try { ActivationApi.redeem(this, folio, code) }
                    catch (_: Exception) { org.json.JSONObject().put("ok", false).put("message", "No se pudo conectar con SERVER-SENE.") }
                    runOnUiThread {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        if (result.optBoolean("ok")) {
                            ActivationApi.clearPending(this)
                            dialog.dismiss()
                            AlertDialog.Builder(this)
                                .setTitle("SENE VENTAS VINCULADO")
                                .setMessage("La suscripción quedó activada por 30 días.\nVence: " + result.optString("expires_at"))
                                .setPositiveButton("CONTINUAR") { _, _ -> finish() }
                                .show()
                        } else {
                            val error = result.optString("error")
                            val message = result.optString("message").ifBlank {
                                when (error) {
                                    "CODIGO_INCORRECTO" -> "El código no coincide. Revisa los 10 caracteres e inténtalo otra vez."
                                    "CODIGO_BLOQUEADO" -> "Se agotaron los intentos permitidos. Contacta al responsable."
                                    "CODIGO_YA_UTILIZADO" -> "Este código ya fue utilizado."
                                    "CODIGO_INVALIDADO" -> "Este código fue invalidado al generarse una solicitud nueva."
                                    "DISPOSITIVO_NO_AUTORIZADO" -> "Este código pertenece a otro dispositivo."
                                    "FOLIO_NO_ENCONTRADO" -> "No encontramos ese folio. Verifica tu solicitud."
                                    else -> "No se pudo activar. Revisa el código y el folio."
                                }
                            }
                            AlertDialog.Builder(this).setTitle("No se pudo validar")
                                .setMessage(message)
                                .setPositiveButton("ENTENDIDO", null).show()
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(24))
            setBackgroundColor(0xFFF3F6FC.toInt())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(18), dp(16), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF073B83.toInt(), 0xFF0756B8.toInt())
            ).apply { cornerRadius = dp(18).toFloat() }
        }
        header.addView(TextView(this).apply {
            text = "SENE VENTAS"
            textSize = 27f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        header.addView(TextView(this).apply {
            text = "Instrucciones de pago"
            textSize = 19f
            setTextColor(Color.WHITE)
            setPadding(0, dp(5), 0, 0)
        })
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "Realiza tu pago mediante transferencia bancaria o depósito con los siguientes datos:"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFF17264A.toInt())
            setPadding(dp(8), dp(18), dp(8), dp(14))
        })

        fun copyRow(label: String, value: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(10), dp(10))
                background = rounded(Color.WHITE, 14, 0xFFD3E0F4.toInt())
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = label
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF0750A5.toInt())
            })
            texts.addView(TextView(this).apply {
                text = value
                textSize = 16f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(0xFF101828.toInt())
            })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                text = "COPIAR"
                isAllCaps = false
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = rounded(0xFF0756C8.toInt(), 12)
                setPadding(dp(9), dp(5), dp(9), dp(5))
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
                    Toast.makeText(this@PaymentInfoActivity, "$label copiado", Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(dp(104), dp(48)))
            val params = LinearLayout.LayoutParams(-1, -2)
            params.bottomMargin = dp(10)
            root.addView(row, params)
        }

        val folio = intent.getStringExtra("folio") ?: ActivationApi.pendingFolio(this)
        val amountCents = intent.getIntExtra("amount_cents", ActivationApi.pendingAmount(this))
        if (folio.isNotBlank()) {
            root.addView(TextView(this).apply {
                text = "FOLIO: $folio\\nIMPORTE: $" + String.format(java.util.Locale.US, "%.2f", amountCents / 100.0) + " MXN"
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(0xFF073B83.toInt())
                setPadding(dp(12), dp(13), dp(12), dp(13))
                background = rounded(Color.WHITE, 14, 0xFFD3E0F4.toInt())
            })
        }

        copyRow("Titular", "JUAN ANTONIO VAZQUEZ")
        copyRow("Banco", "BBVA")
        copyRow("CLABE", "012180015267857481")
        copyRow("WhatsApp para comprobante", "3325142147")

        root.addView(TextView(this).apply {
            text = "Envía tu comprobante de pago por WhatsApp al número indicado."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFF263B65.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(12))
        })
        val sendReceipt = Button(this).apply {
            text = "ENVIAR COMPROBANTE POR WHATSAPP"
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(0xFF168B45.toInt(), 14)
            setOnClickListener {
                val message = if (folio.isBlank()) {
                    "Hola, envío mi comprobante de pago de SENE VENTAS."
                } else {
                    "Hola, envío mi comprobante de pago de SENE VENTAS. Folio: " + folio +
                        ". Importe: $" + String.format(java.util.Locale.US, "%.2f", amountCents / 100.0) + " MXN."
                }
                val url = "https://wa.me/523325142147?text=" + Uri.encode(message)
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                catch (_: Exception) { Toast.makeText(this@PaymentInfoActivity, "No se pudo abrir WhatsApp", Toast.LENGTH_LONG).show() }
            }
        }
        root.addView(sendReceipt, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(8) })

        val confirm = Button(this).apply {
            text = "YA REALICÉ EL PAGO Y ENVIÉ EL COMPROBANTE"
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(0xFF149B35.toInt(), 16)
            setOnClickListener { requestCodeDialog() }
        }
        root.addView(confirm, LinearLayout.LayoutParams(-1, dp(66)))
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
    }
}
