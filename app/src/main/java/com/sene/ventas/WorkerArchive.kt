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

/**
 * Retención de archivos: se ejecuta como máximo una vez cada 30 días.
 * Conserva los 10 inventarios y 10 reportes más recientes por perfil/categoría.
 */
object ArchiveRetention {
    private const val PREFS = "sene_ventas_local"
    private const val LAST_RUN = "archive_cleanup_last_run"
    private const val PERIOD = 30L * 24L * 60L * 60L * 1000L
    private const val KEEP = 10

    @Synchronized fun cleanupIfDue(context: Context, tree: Uri?, ownerKey: String) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(LAST_RUN, 0L) < PERIOD) return
        try {
            cleanLocal(context, ownerKey)
            if (tree != null) cleanTree(context, tree, ownerKey)
            prefs.edit().putLong(LAST_RUN, now).apply()
        } catch (_: Exception) {
            // Si falla el acceso al almacenamiento compartido, se reintentará en el siguiente inicio.
        }
    }

    private data class Entry(val file: File, val modified: Long)
    private data class DocEntry(val uri: Uri, val modified: Long)

    private fun cleanLocal(context: Context, ownerKey: String) {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val groups = linkedMapOf<String, MutableList<Entry>>()
        fun scan(dir: File, path: String) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) {
                    val name = child.name
                    if (name.equals("INVENTARIOS", true) || name.equals("REPORTES", true)) {
                        val key = File(path, name).absolutePath
                        val files = mutableListOf<Entry>()
                        collectLocal(child, files)
                        if (files.isNotEmpty()) groups.getOrPut(key) { mutableListOf() }.addAll(files)
                    } else if (name.equals("INVENTARIO-SENE", true) && !path.contains("TRABAJADORES")) {
                        val files = children.filter { it.isFile && it.name.matches(Regex("(?i)^inventario-\\d+\\.txt$")) }
                            .map { Entry(it, it.lastModified()) }
                        if (files.isNotEmpty()) groups.getOrPut(child.absolutePath) { mutableListOf() }.addAll(files)
                        scan(child, child.absolutePath)
                    } else scan(child, child.absolutePath)
                }
            }
        }
        scan(base, base.absolutePath)
        groups.forEach { (group, entries) ->
            val expected = if (group.contains("REPORTES", true)) "REPORTES" else "INVENTARIO_SENE"
            val valid = entries.all { entry ->
                try { validBackup(entry.file.readBytes(), ownerKey, expected) } catch (_: Exception) { false }
            }
            if (valid) entries.sortedByDescending { it.modified }.drop(KEEP).forEach { it.file.delete() }
            // Si la clave no coincide o hay TXT heredados, se conserva todo el grupo.
        }
    }

    private fun validBackup(raw: ByteArray, ownerKey: String, expected: String): Boolean {
        if (!SeneSharedFiles.isSharedFile(raw)) return false
        val doc = SeneSharedFiles.decode(raw, ownerKey)
        return if (expected == "REPORTES") doc.category == "REPORTES"
            else doc.category == "INVENTARIO_SENE" || doc.category == "INVENTARIO_MAESTRO"
    }

    private fun collectLocal(dir: File, out: MutableList<Entry>) {
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) collectLocal(child, out)
            else if (child.isFile && child.name.endsWith(".txt", true)) out.add(Entry(child, child.lastModified()))
        }
    }

    private fun cleanTree(context: Context, tree: Uri, ownerKey: String) {
        val resolver = context.contentResolver
        val groups = linkedMapOf<String, MutableList<DocEntry>>()
        val visited = mutableSetOf<String>()
        fun walk(parent: Uri, path: String, depth: Int) {
            if (depth > 10) return
            val parentId = DocumentsContract.getDocumentId(parent)
            if (!visited.add(parentId)) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            resolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ), null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modifiedCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    if (idCol < 0 || nameCol < 0 || mimeCol < 0) continue
                    val name = cursor.getString(nameCol) ?: continue
                    val child = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idCol))
                    val childPath = if (path.isBlank()) name else "$path/$name"
                    if (cursor.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(child, childPath, depth + 1)
                    } else {
                        val isInventory = name.matches(Regex("(?i)^inventario-\\d+\\.txt$"))
                        val inventoryGroup = when {
                            childPath.contains("/TRABAJADORES/", true) && childPath.contains("/INVENTARIOS/", true) ->
                                childPath.substringBefore("/INVENTARIOS/", childPath) + "/INVENTARIOS"
                            childPath.endsWith("/INVENTARIO-SENE/$name", true) && !childPath.contains("/TRABAJADORES/", true) ->
                                childPath.substringBeforeLast("/")
                            else -> null
                        }
                        val reportIndex = childPath.indexOf("/REPORTES/", ignoreCase = true)
                        val reportGroup = if (reportIndex >= 0) childPath.substring(0, reportIndex + "/REPORTES".length) else null
                        val isReport = reportGroup != null && name.endsWith(".txt", true)
                        val group = when {
                            isInventory -> inventoryGroup
                            isReport -> reportGroup
                            else -> null
                        }
                        if (group != null) {
                            val modified = if (modifiedCol >= 0) cursor.getLong(modifiedCol) else 0L
                            groups.getOrPut(group) { mutableListOf() }.add(DocEntry(child, modified))
                        }
                    }
                }
            }
        }
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        walk(root, "", 0)
        groups.forEach { (group, entries) ->
            val expected = if (group.contains("REPORTES", true)) "REPORTES" else "INVENTARIO_SENE"
            val valid = entries.all { entry ->
                try {
                    val raw = resolver.openInputStream(entry.uri)?.use { it.readBytes() } ?: return@all false
                    validBackup(raw, ownerKey, expected)
                } catch (_: Exception) { false }
            }
            if (valid) entries.sortedByDescending { it.modified }.drop(KEEP).forEach {
                try { DocumentsContract.deleteDocument(resolver, it.uri) } catch (_: Exception) {}
            }
        }
    }
}
