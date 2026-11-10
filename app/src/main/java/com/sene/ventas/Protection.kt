package com.sene.ventas

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.provider.Settings
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Activación inicial y blindaje local vinculado al Android Keystore del dispositivo.
 * El texto de la clave maestra nunca se persiste.
 */
object Protection {
    private const val PREFS = "sene_install_protection"
    private const val ALIAS = "sene_device_binding_v1"
    private const val ROUNDS = 310000
    private const val SALT_B64 = "HdH3dF+B/pxZDymDkL208g=="
    private const val HASH_B64 = "Ru33WCl+r5Bq8IJKJLVD7vUDxqN9Kmf1KWFfl8MK2MA="
    private const val CHALLENGE = "SENE-VENTAS-DEVICE-BINDING-v1"

    private fun deviceId(c: Context): String =
        Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()

    fun requireActivation(activity: Activity, onAuthorized: () -> Unit) {
        if (isAuthorized(activity)) {
            onAuthorized()
            return
        }
        val field = EditText(activity).apply {
            hint = "Clave maestra"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setPadding(activity.dp(18), activity.dp(14), activity.dp(18), activity.dp(14))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("SENE-VENTAS · Autorización")
            .setMessage("Introduce la clave maestra para autorizar este dispositivo.")
            .setView(field)
            .setCancelable(false)
            .setPositiveButton("AUTORIZAR", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entered = field.text?.toString().orEmpty()
                if (verifyMasterKey(entered) && deviceId(activity).isNotBlank()) {
                    dialog.dismiss()
                    requestInitialReportNumber(activity) { number ->
                        Store.setPhone(activity, number)
                        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putBoolean("authorized", true)
                            .putString("device_id", deviceId(activity))
                            .apply()
                        onAuthorized()
                    }
                } else {
                    field.text?.clear()
                    field.error = "Clave incorrecta"
                }
            }
        }
        dialog.show()
    }

    
    private fun requestInitialReportNumber(activity: Activity, onSaved: (String) -> Unit) {
        val field = EditText(activity).apply {
            hint = "Ej. 52 + número de celular"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
            setPadding(activity.dp(18), activity.dp(14), activity.dp(18), activity.dp(14))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("WhatsApp para reportes")
            .setMessage("Ingresa el número del patrón con código de país. Se guardará automáticamente en Soy Patrón y se usará para enviar los reportes.")
            .setView(field)
            .setCancelable(false)
            .setPositiveButton("GUARDAR Y CONTINUAR", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val number = field.text?.toString().orEmpty().filter { it.isDigit() }
                if (number.length < 10 || number.length > 15) {
                    field.error = "Ingresa un número válido con código de país"
                } else {
                    dialog.dismiss()
                    onSaved(number)
                }
            }
        }
        dialog.show()
    }

    private fun verifyMasterKey(value: String): Boolean {
        if (value.isEmpty()) return false
        return try {
            val spec = PBEKeySpec(value.toCharArray(), Base64.decode(SALT_B64, Base64.DEFAULT), ROUNDS, 256)
            val actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            spec.clearPassword()
            MessageDigest.isEqual(actual, Base64.decode(HASH_B64, Base64.DEFAULT))
        } catch (_: Exception) {
            false
        }
    }

    fun isAuthorized(c: Context): Boolean {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean("authorized", false)) return false
        if (p.getString("device_id", null) != deviceId(c) || deviceId(c).isBlank()) return false
        return if (p.getBoolean("blinded", false)) verifyDeviceKey() else true
    }

    fun isBlinded(c: Context): Boolean {
        return isAuthorized(c) && c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("blinded", false)
    }

    private fun verifyDeviceKey(): Boolean {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val privateKey = ks.getKey(ALIAS, null) as? PrivateKey ?: return false
            val publicKey = ks.getCertificate(ALIAS)?.publicKey ?: return false
            val signer = Signature.getInstance("SHA256withRSA")
            signer.initSign(privateKey)
            signer.update(CHALLENGE.toByteArray(Charsets.UTF_8))
            val signed = signer.sign()
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(publicKey)
            verifier.update(CHALLENGE.toByteArray(Charsets.UTF_8))
            verifier.verify(signed)
        } catch (_: Exception) {
            false
        }
    }

    private fun createDeviceKey(): Boolean {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias(ALIAS)) {
                val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
                generator.initialize(
                    KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                    ).setDigests(KeyProperties.DIGEST_SHA256).setKeySize(2048).build()
                )
                generator.generateKeyPair()
            }
            verifyDeviceKey()
        } catch (_: Exception) {
            false
        }
    }

    fun addBlindButton(activity: Activity, container: LinearLayout) {
        if (!isAuthorized(activity) || isBlinded(activity)) return
        val button = Button(activity).apply {
            text = "BLINDAR APK"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFC62828.toInt())
                cornerRadius = 999f
            }
            minHeight = activity.dp(38)
            setPadding(activity.dp(20), activity.dp(4), activity.dp(20), activity.dp(4))
            setOnClickListener {
                AlertDialog.Builder(activity)
                    .setTitle("Blindar este dispositivo")
                    .setMessage("Se creará una clave criptográfica que no se puede exportar desde Android Keystore. El botón desaparecerá al terminar. Guarda el APK original fuera del celular del cliente.")
                    .setPositiveButton("BLINDAR AHORA") { _, _ ->
                        if (createDeviceKey()) {
                            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                .putBoolean("blinded", true).apply()
                            Toast.makeText(activity, "Dispositivo blindado correctamente", Toast.LENGTH_LONG).show()
                            val row = parent as? LinearLayout
                            (row?.parent as? android.view.ViewGroup)?.removeView(row)
                        } else {
                            Toast.makeText(activity, "No se pudo crear la protección del dispositivo", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, activity.dp(8), 0, activity.dp(8))
            addView(button, LinearLayout.LayoutParams(-2, -2))
        }
        container.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun Activity.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
