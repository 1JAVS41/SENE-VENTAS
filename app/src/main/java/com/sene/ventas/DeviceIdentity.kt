package com.sene.ventas

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.provider.Settings
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

/**
 * Identidad criptográfica local de una instalación de SENE VENTAS.
 *
 * La clave privada nunca sale de Android Keystore. Esta clase NO registra,
 * activa ni vincula el dispositivo: eso requerirá autorización del backend
 * y de SENE PATRÓN en una etapa posterior.
 */
data class DeviceIdentitySnapshot(
    val installationId: String,
    val continuityId: String,
    val publicKeyBase64: String,
    val fingerprint: String
)

object DeviceIdentity {
    private const val KEY_ALIAS = "SENE_VENTAS_DEVICE_IDENTITY_V1"
    private const val PREFS = "sene_device_identity"
    private const val ID_KEY = "installation_id"

    @Synchronized
    fun get(context: Context): DeviceIdentitySnapshot {
        val appContext = context.applicationContext
        val keyPair = loadOrCreateKeyPair(appContext)

        // El ID lógico se deriva del paquete y ANDROID_ID, no de preferencias
        // que Android puede borrar al reinstalar. Así conserva el mismo ID en
        // el mismo perfil/teléfono; la clave P-256 sigue protegida en Keystore.
        val androidId = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        check(androidId.isNotBlank() && !androidId.equals("9774d56d682e549c", true)) {
            "Android no proporcionó un identificador estable para este dispositivo"
        }
        val continuityInput = appContext.packageName + "|" + androidId
        val installationId = deriveInstallationId(appContext.packageName, androidId)
        val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        check(preferences.edit().putString(ID_KEY, installationId).commit()) {
            "No se pudo guardar el identificador lógico del dispositivo"
        }

        // continuityId ayuda a localizar la vinculación anterior, pero no es
        // secreto ni prueba criptográfica: el Worker exige datos registrados y
        // una firma nueva de la clave P-256 que permanece dentro de Keystore.
        val continuityId = MessageDigest.getInstance("SHA-256")
            .digest(continuityInput.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }

        val publicBytes = keyPair.public.encoded
        val fingerprintBytes = MessageDigest.getInstance("SHA-256").digest(publicBytes)
        val fingerprint = fingerprintBytes.joinToString("") { byte ->
            "%02X".format(byte.toInt() and 0xFF)
        }

        return DeviceIdentitySnapshot(
            installationId = installationId,
            continuityId = continuityId,
            publicKeyBase64 = Base64.encodeToString(publicBytes, Base64.NO_WRAP),
            fingerprint = fingerprint
        )
    }

    /**
     * Firma un reto aleatorio entregado por el futuro servicio de registro.
     * El reto debe tener al menos 16 bytes para evitar pruebas triviales.
     * La clave privada se usa dentro de Android Keystore y nunca se exporta.
     */
    @Synchronized
    fun signChallenge(context: Context, challenge: ByteArray): String {
        require(challenge.size in 16..4096) {
            "El reto de seguridad debe contener entre 16 y 4096 bytes"
        }

        // Garantiza que la identidad y su par de claves existan.
        get(context.applicationContext)

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as? java.security.PrivateKey
            ?: throw IllegalStateException("No se encontró la clave privada protegida del equipo")

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(challenge)
        return Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
    }

    /**
     * Verificación local de la firma con la clave pública registrada.
     * El servidor realizará su propia verificación antes de aceptar el equipo.
     */
    fun verifyChallenge(
        snapshot: DeviceIdentitySnapshot,
        challenge: ByteArray,
        signatureBase64: String
    ): Boolean {
        if (snapshot.installationId.isBlank() ||
            challenge.size !in 16..4096 ||
            signatureBase64.isBlank()
        ) return false
        return try {
            val publicBytes = Base64.decode(snapshot.publicKeyBase64, Base64.NO_WRAP)
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(publicBytes)
                .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
            // Evita aceptar una identidad cuyo dato de huella no corresponda
            // con la clave pública que se está usando para verificar.
            if (!fingerprint.equals(snapshot.fingerprint, ignoreCase = true)) return false

            val publicKey = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(publicBytes))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(challenge)
            verifier.verify(Base64.decode(signatureBase64, Base64.NO_WRAP))
        } catch (_: Exception) {
            false
        }
    }

    private fun loadOrCreateKeyPair(context: Context): KeyPair {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val publicKey = keyStore.getCertificate(KEY_ALIAS)?.publicKey
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as? java.security.PrivateKey

        if (publicKey != null && privateKey != null) {
            return KeyPair(publicKey, privateKey)
        }

        // Recupera el caso excepcional de una entrada incompleta en Keystore:
        // si quedó solo el certificado o solo la clave privada, el alias viejo
        // impediría generar el nuevo par. Como no existe un par utilizable, se
        // elimina esa entrada dañada y se renueva también el ID de instalación.
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }

        // Si falta la clave, se renueva el par criptográfico. El ID lógico
        // estable se vuelve a derivar de ANDROID_ID al regresar a get().
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(ID_KEY).commit()) {
            "No se pudo renovar el identificador de instalación"
        }

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            "AndroidKeyStore"
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKeyPair()
    }
}
