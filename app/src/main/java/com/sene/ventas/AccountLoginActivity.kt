package com.sene.ventas

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*

class AccountLoginActivity : Activity() {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun field(hint: String, phone: Boolean = false) = EditText(this).apply {
        this.hint = hint; textSize = 16f; setSingleLine(true)
        setPadding(dp(15), dp(13), dp(15), dp(13)); setTextColor(0xFF17213D.toInt())
        background = GradientDrawable().apply { setColor(-1); cornerRadius = dp(13).toFloat(); setStroke(dp(1), 0xFFDCE3F0.toInt()) }
        if (phone) inputType = android.text.InputType.TYPE_CLASS_PHONE
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(28)); setBackgroundColor(0xFFF3F6FC.toInt())
        }
        root.addView(TextView(this).apply {
            text = "YA SOY CLIENTE SENE"; textSize = 24f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(0xFF173C7B.toInt()); setPadding(0, dp(8), 0, dp(12))
        })
        root.addView(TextView(this).apply {
            text = "Inicia sesión en tu negocio. Si cambiaste de celular, solicita la autorización para transferir tu cuenta. Tu suscripción y vencimiento se conservan."
            textSize = 14f; gravity = Gravity.CENTER; setTextColor(0xFF53627E.toInt()); setPadding(0, 0, 0, dp(16))
        })
        val business = field("Nombre exacto del negocio")
        val whatsapp = field("WhatsApp registrado", true)
        root.addView(business); root.addView(whatsapp)
        root.addView(action("INICIAR SESIÓN") {
            val name = business.text.toString().trim()
            val phone = whatsapp.text.toString().filter { it.isDigit() }
            if (name.length < 2 || phone.length !in 10..15) {
                AlertDialog.Builder(this).setMessage("Escribe el nombre del negocio y el WhatsApp registrado.")
                    .setPositiveButton("ENTENDIDO", null).show()
                return@action
            }
            setBusy(root, true)
            Thread {
                val result = try { ActivationApi.requestAccountRecovery(this, name, phone) }
                catch (_: Exception) { org.json.JSONObject().put("ok", false).put("message", "No se pudo conectar con SERVER-SENE. Revisa tu conexión.") }
                runOnUiThread {
                    setBusy(root, false)
                    if (result.optBoolean("ok")) {
                        val folio = result.optString("folio")
                        ActivationApi.savePendingRecovery(this, folio)
                        showCodeDialog(folio)
                    } else if (result.optString("error") == "DISPOSITIVO_YA_VINCULADO") {
                        checkCurrentSession()
                    } else {
                        AlertDialog.Builder(this).setTitle("No se pudo iniciar sesión")
                            .setMessage(result.optString("message", result.optString("error", "Verifica tus datos.")))
                            .setPositiveButton("ENTENDIDO", null).show()
                    }
                }
            }.start()
        })
        root.addView(action("CONTINUAR CON SOLICITUD PENDIENTE", false) {
            val folio = ActivationApi.pendingRecoveryFolio(this)
            if (folio.isBlank()) AlertDialog.Builder(this).setMessage("No hay una recuperación pendiente en este dispositivo.")
                .setPositiveButton("ENTENDIDO", null).show()
            else showCodeDialog(folio)
        })
        root.addView(action("VOLVER", false) { finish() })
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        checkCurrentSession()
    }
    private fun setBusy(root: LinearLayout, busy: Boolean) {
        for (i in 0 until root.childCount) root.getChildAt(i).isEnabled = !busy
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
                }
            }
        }.start()
    }
    private fun showCodeDialog(folio: String) {
        val code = EditText(this).apply {
            hint = "Código de autorización de 10 caracteres"; textSize = 17f; setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val dialog = AlertDialog.Builder(this).setTitle("AUTORIZAR ESTE CELULAR")
            .setMessage("Folio: " + folio + "\nEl responsable de SENE verificará tu identidad y te entregará el código. El celular anterior seguirá autorizado hasta completar este paso.")
            .setView(code).setPositiveButton("VALIDAR CÓDIGO", null).setNegativeButton("CANCELAR", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = code.text.toString().trim().uppercase()
                if (!Regex("^[A-HJ-NP-Z2-9]{10}$").matches(value)) { code.error = "El código debe tener 10 caracteres"; return@setOnClickListener }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                Thread {
                    val result = try { ActivationApi.completeAccountRecovery(this, folio, value) }
                    catch (_: Exception) { org.json.JSONObject().put("ok", false).put("message", "No se pudo conectar con SERVER-SENE.") }
                    val status = if (result.optBoolean("ok")) try { ActivationApi.subscriptionStatus(this) } catch (_: Exception) { null } else null
                    runOnUiThread {
                        if (result.optBoolean("ok") && status?.optBoolean("active") == true) {
                            ActivationApi.clearPendingRecovery(this); dialog.dismiss()
                            AlertDialog.Builder(this).setTitle("INICIO DE SESIÓN CORRECTO")
                                .setMessage("Dispositivo autorizado para " + status.optString("business_name", "tu negocio") + ". Tu suscripción y fecha de vencimiento se conservaron.")
                                .setPositiveButton("CONTINUAR") { _, _ -> finish() }.show()
                        } else {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            val message = result.optString("message", if (result.optBoolean("ok")) "El dispositivo se transfirió, pero no se pudo confirmar una suscripción vigente." else result.optString("error", "No se pudo validar el código."))
                            AlertDialog.Builder(this).setTitle("No se pudo completar").setMessage(message)
                                .setPositiveButton("ENTENDIDO", null).show()
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }
}
