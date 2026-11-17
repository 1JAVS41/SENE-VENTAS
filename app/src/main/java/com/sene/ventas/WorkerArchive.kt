package com.sene.ventas

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.util.Locale

/**
 * Estructura aislada por perfil dentro de INVENTARIO SENE.
 * INVENTARIO-SENE/TRABAJADORES/<nombre-id>/INVENTARIOS y REPORTES.
 */
object WorkerArchive {
    private const val ROOT = "INVENTARIO-SENE"
    private const val WORKERS = "TRABAJADORES"

    fun profileKey(id: String, name: String): String {
        val clean = name.trim().lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9áéíóúñ_-]+"), "-")
            .trim('-').take(32).ifBlank { "trabajador" }
        return clean + "-" + id.replace("-", "").take(8)
    }

    fun localProfileFolder(c: Context, id: String, name: String): File =
        File(File(File(c.getExternalFilesDir(null) ?: c.filesDir, ROOT), WORKERS), profileKey(id, name))

    fun localInventoryFolder(c: Context, id: String, name: String): File =
        File(localProfileFolder(c, id, name), "INVENTARIOS")

    fun localReportsFolder(c: Context, id: String, name: String): File =
        File(localProfileFolder(c, id, name), "REPORTES")

    @Synchronized fun ensureProfileFolders(c: Context, id: String, name: String) {
        val inventory = localInventoryFolder(c, id, name)
        val reports = localReportsFolder(c, id, name)
        if (!inventory.exists() && !inventory.mkdirs()) throw java.io.IOException("No se pudo crear la carpeta local de inventarios")
        if (!reports.exists() && !reports.mkdirs()) throw java.io.IOException("No se pudo crear la carpeta local de reportes")
        val saved = c.getSharedPreferences("sene_ventas_local", 0).getString("report_tree_uri", null)
        if (!saved.isNullOrBlank()) {
            val tree = Uri.parse(saved)
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val resolver = c.contentResolver
            val sene = childOrCreate(c, tree, root, ROOT)
            val workers = childOrCreate(c, tree, sene, WORKERS)
            val profile = childOrCreate(c, tree, workers, profileKey(id, name))
            childOrCreate(c, tree, profile, "INVENTARIOS")
            childOrCreate(c, tree, profile, "REPORTES")
        }
    }

    fun profileSection(c: Context, tree: Uri, id: String, name: String, section: String): Uri {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val sene = childOrCreate(c, tree, root, ROOT)
        val workers = childOrCreate(c, tree, sene, WORKERS)
        val profile = childOrCreate(c, tree, workers, profileKey(id, name))
        return childOrCreate(c, tree, profile, section)
    }

    private fun childOrCreate(c: Context, tree: Uri, parent: Uri, name: String): Uri {
        val resolver = c.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cur ->
            val idCol = cur.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cur.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cur.moveToNext()) {
                if (idCol >= 0 && nameCol >= 0 && cur.getString(nameCol) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(idCol))
                }
            }
        }
        return DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw java.io.IOException("No se pudo crear la carpeta $name")
    }
}
