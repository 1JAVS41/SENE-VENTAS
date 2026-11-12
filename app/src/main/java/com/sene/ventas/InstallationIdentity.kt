package com.sene.ventas

import java.util.UUID

/**
 * Identificador lógico estable del mismo perfil Android tras reinstalar.
 * No sustituye la clave P-256 ni demuestra por sí solo la posesión del equipo.
 */
internal fun deriveInstallationId(packageName: String, androidId: String): String {
    require(packageName.isNotBlank() && androidId.isNotBlank())
    val seed = "$packageName|$androidId".toByteArray(Charsets.UTF_8)
    return UUID.nameUUIDFromBytes(seed).toString()
}
