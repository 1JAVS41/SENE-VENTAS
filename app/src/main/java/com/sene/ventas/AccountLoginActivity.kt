package com.sene.ventas

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*

class AccountLoginActivity : Activity() {
    companion object {
        fun ensureReportWhatsApp(activity: Activity, onComplete: () -> Unit) {
            val prefs = activity.getSharedPreferences("sene_report_whatsapp", Activity.MODE_PRIVATE)
            if (prefs.getBoolean("registered", false)) {
                onComplete()
                return
            }
            val phone = EditText(activity).apply {
                hint = "Número de WhatsApp con lada y código de país"
                textSize = 16f
                inputType = android.text.InputType.TYPE_CLASS_PHONE
                setSingleLine(true)
                setPadding((15 * resources.displayMetrics.density).toInt(), (13 * resources.displayMetrics.density).toInt(), (15 * resources.displayMetrics.density).toInt(), (13 * resources.displayMetrics.density).toInt())
            }
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((20 * resources.displayMetrics.density).toInt(), (8 * resources.displayMetrics.density).toInt(), (20 * resources.displayMetrics.density).toInt(), (4 * resources.displayMetrics.density).toInt())
                addView(TextView(activity).apply {
                    text = "Registra el número de WhatsApp donde recibirás los reportes de tus trabajadores."
                    textSize = 15f
                    setTextColor(0xFF263653.toInt())
                })
                addView(TextView(activity).apply {
                    text = "Recomendación: no utilices tu WhatsApp personal. Es preferible usar un número exclusivo para el negocio."
                    textSize = 13f
                    setTextColor(0xFF66708A.toInt())
                    setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
                })
                addView(phone)
            }
            val dialog = AlertDialog.Builder(activity)
                .setTitle("WHATSAPP PARA REPORTES")
                .setView(content)
                .setCancelable(false)
                .setPositiveButton("GUARDAR Y CONTINUAR", null)
                .create()
            dialog.setOnShowListener {
                dialog.setCanceledOnTouchOutside(false)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val digits = phone.text.toString().filter { it.isDigit() }
                    if (digits.length !in 10..15) {
                        phone.error = "Introduce un número válido con lada y código de país"
                        return@setOnClickListener
                    }
                    Store.setPhone(activity, digits)
                    prefs.edit().putBoolean("registered", true).apply()
                    dialog.dismiss()
                    onComplete()
                }
            }
            dialog.show()
        }
    }


    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun field(hint: String, code: Boolean = false) = EditText(this).apply {
        this.hint = hint; textSize = 16f; setSingleLine(true)
        setPadding(dp(15), dp(13), dp(15), dp(13)); setTextColor(0xFF17213D.toInt())
        background = GradientDrawable().apply { setColor(-1); cornerRadius = dp(13).toFloat(); setStroke(dp(1), 0xFFDCE3F0.toInt()) }
        if (code) {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(android.text.InputFilter.AllCaps())
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7); bottomMargin = dp(7) }
    }
    private fun action(label: String, primary: Boolean = true, click: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f
        typeface = Typeface.create("sans-serif", Typeface.BOLD); minHeight = dp(54)
        setTextColor(if (primary) -1 else 0xFF24447C.toInt())
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            if (primary) intArrayOf(0xFF315BA5.toInt(), 0xFF173C7B.toInt()) else intArrayOf(-1, -1)
        ).apply { cornerRadius = dp(13).toFloat(); if (!primary) setStroke(dp(1), 0xFFD5DEEF.toInt()) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7); bottomMargin = dp(7) }
        setOnClickListener { click() }
    }
    private lateinit var root: LinearLayout
    private lateinit var folio: EditText
    private lateinit var activationCode: EditText
    private lateinit var loginButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(28)); setBackgroundColor(0xFFF3F6FC.toInt())
        }
        root.addView(TextView(this).apply {
            text = "YA SOY CLIENTE SENE"; textSize = 24f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(0xFF173C7B.toInt()); setPadding(0, dp(8), 0, dp(12))
        })
        root.addView(TextView(this).apply {
            text = "Inicia sesión con el folio y el código de activación de tu licencia."
            textSize = 14f; gravity = Gravity.CENTER; setTextColor(0xFF53627E.toInt()); setPadding(0, 0, 0, dp(16))
        })
        folio = field("Folio de instalación / licencia")
        activationCode = field("Código de activación", true)
        root.addView(folio); root.addView(activationCode)
        loginButton = action("INICIAR SESIÓN Y TRANSFERIR DISPOSITIVO") { confirmTransfer() }
        root.addView(loginButton)
        root.addView(action("YA REALICÉ EL PAGO", false) {
            startActivity(android.content.Intent(this, PaymentInfoActivity::class.java)
                .putExtra("folio", ActivationApi.pendingFolio(this))
                .putExtra("amount_cents", ActivationApi.pendingAmount(this)))
        })
        root.addView(action("VOLVER", false) { finish() })
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        checkCurrentSession()
    }

    private fun checkCurrentSession() {
        Thread {
            val status = try { ActivationApi.subscriptionStatus(this) } catch (_: Exception) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (status?.optBoolean("active") == true) {
                    AlertDialog.Builder(this).setTitle("SESIÓN ACTIVA")
                        .setMessage("Este dispositivo ya está autorizado para " + status.optString("business_name", "tu negocio") + ". La suscripción está vigente.")
                        .setPositiveButton("CONTINUAR") { _, _ -> finish() }.show()
                } else {
                    val linked = getSharedPreferences("sene_account_transfer", MODE_PRIVATE)
                        .getBoolean("linked_successfully", false)
                    if (!linked) showTransferWarning()
                }
            }
        }.start()
    }

    private fun showTransferWarning() {
        AlertDialog.Builder(this).setTitle("⚠ AVISO IMPORTANTE")
            .setMessage(
                "Esta licencia permite vincular SENE VENTAS en un solo dispositivo a la vez.\n\n" +
                "Si introduces tu código en otro dispositivo, el nuevo acceso sustituirá al anterior y el dispositivo previamente vinculado perderá el acceso a SENE VENTAS.\n\n" +
                "PROTEGE TUS DATOS DE ACCESO\n" +
                "Cuida tus datos de acceso y mantenlos en un lugar seguro. Cualquier persona que conozca tu folio y código de activación podría vincular otro dispositivo y desvincular el tuyo.\n\n" +
                "¿Deseas continuar y transferir tu licencia a este dispositivo?"
            )
            .setPositiveButton("CONTINUAR", null)
            .setNegativeButton("CANCELAR") { _, _ -> finish() }
            .show()
    }

    private fun confirmTransfer() {
        val folioValue = folio.text.toString().trim()
        val codeValue = activationCode.text.toString().trim().uppercase()
        if (folioValue.length < 4 || folioValue.length > 80 || codeValue.isBlank() || codeValue.length > 64) {
            AlertDialog.Builder(this).setTitle("DATOS INCOMPLETOS")
                .setMessage("Introduce el folio y el código de activación de tu licencia.")
                .setPositiveButton("ENTENDIDO", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("CONFIRMAR TRANSFERENCIA")
            .setMessage("Al continuar, este dispositivo sustituirá al que actualmente está vinculado a la licencia. El dispositivo anterior perderá el acceso. ¿Deseas continuar?")
            .setNegativeButton("CANCELAR", null)
            .setPositiveButton("SÍ, TRANSFERIR") { _, _ -> transfer(folioValue, codeValue) }
            .show()
    }

    private fun transfer(folioValue: String, codeValue: String) {
        setBusy(true)
        Thread {
            val result = try { ActivationApi.transferAccountDevice(this, folioValue, codeValue) }
            catch (_: Exception) { org.json.JSONObject().put("ok", false).put("message", "No se pudo conectar con SERVER-SENE. Revisa tu conexión.") }
            val status = if (result.optBoolean("ok")) try { ActivationApi.subscriptionStatus(this) } catch (_: Exception) { null } else null
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false)
                if (result.optBoolean("ok") && status?.optBoolean("active") == true) {
                    getSharedPreferences("sene_account_transfer", MODE_PRIVATE).edit()
                        .putBoolean("linked_successfully", true).apply()
                    AlertDialog.Builder(this).setTitle("INICIO DE SESIÓN CORRECTO")
                        .setMessage("Este dispositivo quedó vinculado a " + status.optString("business_name", "tu negocio") +
                            ". El dispositivo anterior perdió el acceso. Tu suscripción y fecha de vencimiento se conservaron.")
                        .setCancelable(false)
                        .setPositiveButton("CONTINUAR") { _, _ -> finish() }.show()
                } else {
                    val message = result.optString("message",
                        if (result.optBoolean("ok")) "La transferencia fue recibida, pero aún no se pudo confirmar el acceso. Cuando SERVER-SENE esté actualizado, podrás completar el inicio de sesión."
                        else result.optString("error", "No se pudo transferir la licencia. Verifica el folio y el código."))
                    AlertDialog.Builder(this).setTitle("NO SE PUDO INICIAR SESIÓN")
                        .setMessage(message).setPositiveButton("ENTENDIDO", null).show()
                }
            }
        }.start()
    }

    private fun setBusy(busy: Boolean) {
        folio.isEnabled = !busy
        activationCode.isEnabled = !busy
        loginButton.isEnabled = !busy
        loginButton.text = if (busy) "VALIDANDO LICENCIA…" else "INICIAR SESIÓN Y TRANSFERIR DISPOSITIVO"
    }
}
