package com.sene.ventas

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Protocolo compartido SENE VENTAS / SENE ADMIN.
 * Los dos proyectos deben conservar exactamente este encabezado, KDF, AAD y orden.
 * Archivos portables entre teléfonos; la clave del propietario no se guarda dentro del archivo.
 */
object SeneSharedFiles {
    private const val MAGIC = "SENE-SHARED-FILE|2"
    private const val ITERATIONS = 210000
    private const val IV_SIZE = 12

    data class Document(val category: String, val updated: String, val payload: ByteArray)

    private fun b64(value: ByteArray) =
        Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun unb64(value: String) =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun derive(ownerKey: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(ownerKey.trim().toCharArray(), salt, ITERATIONS, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }

    fun encode(category: String, ownerKey: String, payload: ByteArray, updated: String): ByteArray {
        require(category == "INVENTARIO_SENE" || category == "INVENTARIO_MAESTRO" || category == "REPORTES")
        require(ownerKey.trim().length >= 8)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val header = listOf(MAGIC, "CATEGORY=$category", "UPDATED=$updated", "KDF=PBKDF2-HMAC-SHA256-210000")
            .joinToString("\n")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(ownerKey, salt))
        cipher.updateAAD(header.toByteArray(Charsets.UTF_8))
        val body = cipher.doFinal(payload)
        val lines = header + "\nSALT=" + b64(salt) + "\nIV=" + b64(cipher.iv) + "\nDATA=" + b64(body)
        return lines.toByteArray(Charsets.UTF_8)
    }

    fun decode(raw: ByteArray, ownerKey: String): Document {
        val lines = raw.toString(Charsets.UTF_8).replace("\r\n", "\n").lines()
        require(lines.size == 7 && lines[0] == MAGIC) { "Formato de archivo SENE no reconocido" }
        fun field(name: String) = lines.firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("=") ?: throw IllegalArgumentException("Falta $name")
        val category = field("CATEGORY")
        require(category == "INVENTARIO_SENE" || category == "INVENTARIO_MAESTRO")
        require(field("KDF") == "PBKDF2-HMAC-SHA256-210000")
        val header = lines.take(4).joinToString("\n")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(ownerKey, unb64(field("SALT"))),
            GCMParameterSpec(128, unb64(field("IV"))))
        cipher.updateAAD(header.toByteArray(Charsets.UTF_8))
        val payload = cipher.doFinal(unb64(field("DATA")))
        return Document(category, field("UPDATED"), payload)
    }

    fun isSharedFile(raw: ByteArray): Boolean =
        raw.size >= MAGIC.length && raw.copyOfRange(0, MAGIC.length).toString(Charsets.US_ASCII) == MAGIC
}
