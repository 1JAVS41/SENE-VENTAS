package com.sene.ventas

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

private class HistoryDb(context: Context) : SQLiteOpenHelper(context, "sene_history.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sales (id INTEGER PRIMARY KEY AUTOINCREMENT, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE moves (id INTEGER PRIMARY KEY AUTOINCREMENT, data TEXT NOT NULL)")
        createProductStorage(db)
        createCorrectionStorage(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createProductStorage(db)
        if (oldVersion < 3) createCorrectionStorage(db)
    }

    private fun createProductStorage(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS products (id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS store_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    private fun createCorrectionStorage(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS correction_ops (op_id TEXT PRIMARY KEY, sale_id TEXT NOT NULL, state TEXT NOT NULL, payload TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_correction_ops_state ON correction_ops(state, updated_at)")
    }

    /** Saves a complete local correction intent before any remote grant is consumed. */
    fun saveCorrectionOperation(operation: JSONObject): Boolean {
        val opId = operation.optString("opId").trim()
        val saleId = operation.optString("saleId").trim()
        if (opId.isBlank() || saleId.isBlank()) return false
        val payload = operation.toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery("SELECT sale_id, payload FROM correction_ops WHERE op_id=?", arrayOf(opId)).use { c ->
                if (c.moveToFirst()) Pair(c.getString(0), c.getString(1)) else null
            }
            if (existing != null) return existing.first == saleId && existing.second == payload
            val values = ContentValues().apply {
                put("op_id", opId); put("sale_id", saleId)
                put("state", operation.optString("state", "prepared"))
                put("payload", payload); put("updated_at", System.currentTimeMillis())
            }
            if (db.insert("correction_ops", null, values) == -1L) return false
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    /** State changes are durable and scoped to the immutable operation identifier. */
    fun updateCorrectionOperation(opId: String, state: String, operation: JSONObject): Boolean {
        if (opId.isBlank() || operation.optString("opId") != opId || operation.optString("saleId").isBlank()) return false
        val values = ContentValues().apply { put("state", state); put("payload", operation.toString()); put("updated_at", System.currentTimeMillis()) }
        return writableDatabase.update("correction_ops", values, "op_id=?", arrayOf(opId)) == 1
    }

    fun pendingCorrectionOperations(): JSONArray {
        val result = JSONArray()
        readableDatabase.rawQuery("SELECT payload FROM correction_ops WHERE state NOT IN ('completed', 'cancelled') ORDER BY updated_at ASC", null).use { c ->
            while (c.moveToNext()) try { result.put(JSONObject(c.getString(0))) } catch (_: Exception) { }
        }
        return result
    }

    fun migrate(context: Context) {
        val prefs = context.getSharedPreferences("sene_ventas_local", 0)
        if (prefs.getBoolean("history_migrated_v1", false)) return

        val db = writableDatabase
        db.rawQuery("SELECT value FROM store_meta WHERE key='history_migrated_v1' LIMIT 1", null).use {
            if (it.moveToFirst() && it.getString(0) == "1") {
                val saved = prefs.getString("datos", null)
                val legacy = try {
                    if (saved.isNullOrBlank()) JSONObject() else JSONObject(saved)
                } catch (_: Exception) {
                    return
                }
                legacy.remove("sales")
                legacy.remove("moves")
                check(prefs.edit().putString("datos", legacy.toString()).putBoolean("history_migrated_v1", true).commit()) {
                    "No se pudo finalizar la limpieza del respaldo local."
                }
                return
            }
        }

        val raw = prefs.getString("datos", null)
        val legacy = try {
            if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
        } catch (_: Exception) {
            // Do not mark a malformed legacy file as migrated: preserve it for recovery.
            return
        }

        db.beginTransaction()
        try {
            val sales = legacy.optJSONArray("sales") ?: JSONArray()
            for (i in 0 until sales.length()) {
                val item = sales.optJSONObject(i) ?: continue
                db.insert("sales", null, ContentValues().apply { put("data", item.toString()) })
            }
            val moves = legacy.optJSONArray("moves") ?: JSONArray()
            for (i in 0 until moves.length()) {
                val item = moves.optJSONObject(i) ?: continue
                db.insert("moves", null, ContentValues().apply { put("data", item.toString()) })
            }
            db.insertWithOnConflict(
                "store_meta",
                null,
                ContentValues().apply {
                    put("key", "history_migrated_v1")
                    put("value", "1")
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        legacy.remove("sales")
        legacy.remove("moves")
        check(prefs.edit().putString("datos", legacy.toString()).putBoolean("history_migrated_v1", true).commit()) {
            "Los registros ya están protegidos en SQLite, pero no se pudo actualizar el respaldo de preferencias."
        }
    }

    /**
     * Copies the legacy preferences inventory exactly once. The marker and rows
     * are committed together, so a process death cannot leave a half-migration.
     * Returns false without changing anything if the legacy JSON is malformed.
     */
    fun migrateProducts(context: Context): Boolean {
        val db = writableDatabase
        db.rawQuery("SELECT value FROM store_meta WHERE key='products_migrated_v1' LIMIT 1", null).use {
            if (it.moveToFirst() && it.getString(0) == "1") return true
        }

        val prefs = context.getSharedPreferences("sene_ventas_local", 0)
        val raw = prefs.getString("datos", null)
        val legacy = try {
            if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
        } catch (_: Exception) {
            return false
        }
        val rawProducts = legacy.opt("products")
        if (legacy.has("products") && rawProducts != null && rawProducts !is JSONArray) return false
        val products = (rawProducts as? JSONArray) ?: JSONArray()
        // Never silently discard malformed product rows during first migration.
        for (i in 0 until products.length()) if (products.optJSONObject(i) == null) return false

        db.beginTransaction()
        try {
            // Do not overwrite an existing table if migration is retried.
            db.delete("products", null, null)
            for (i in 0 until products.length()) {
                val item = products.getJSONObject(i)
                check(db.insert("products", null, ContentValues().apply {
                    put("code", item.optString("code", ""))
                    put("data", item.toString())
                }) != -1L) { "No se pudo copiar un producto al inventario SQLite." }
            }
            val count = db.rawQuery("SELECT COUNT(*) FROM products", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else -1
            }
            check(count == products.length()) { "La verificación de inventario no coincide; se canceló la migración." }
            db.insertWithOnConflict(
                "store_meta",
                null,
                ContentValues().apply {
                    put("key", "products_migrated_v1")
                    put("value", "1")
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    fun productsReady(): Boolean {
        readableDatabase.rawQuery("SELECT value FROM store_meta WHERE key='products_migrated_v1' LIMIT 1", null).use {
            return it.moveToFirst() && it.getString(0) == "1"
        }
    }

    fun allProducts(): JSONArray {
        val result = JSONArray()
        readableDatabase.rawQuery("SELECT data FROM products ORDER BY id ASC", null).use { cursor ->
            while (cursor.moveToNext()) {
                try {
                    result.put(JSONObject(cursor.getString(0)))
                } catch (_: Exception) {
                    // Keep other valid products readable if one legacy row is damaged.
                }
            }
        }
        return result
    }

    private fun validateProductArray(array: JSONArray, allowEmpty: Boolean = true): Boolean {
        if (!allowEmpty && array.length() == 0) return false
        val codes = HashSet<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: return false
            val code = item.optString("code").trim()
            val stock = item.optInt("stock", -1)
            if (code.isBlank() || !codes.add(code) || stock < 0) return false
        }
        return true
    }

    fun replaceProducts(array: JSONArray) {
        // Validate the complete replacement before deleting any persisted stock.
        require(validateProductArray(array)) { "El inventario contiene productos inválidos o códigos duplicados; no se guardaron cambios." }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("products", null, null)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                check(db.insert("products", null, ContentValues().apply {
                    put("code", item.optString("code").trim())
                    put("data", item.toString())
                }) != -1L) { "No se pudo guardar un producto; se revirtió el inventario completo." }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Persists the stock decrement and all sale lines in one SQLite transaction.
     * This prevents a process death from leaving a completed sale without its
     * matching inventory change, or vice versa.
     */
    fun recordSale(array: JSONArray, items: List<JSONObject>): Boolean {
        if (items.isEmpty() || !validateProductArray(array, allowEmpty = false)) return false
        val saleId = items.first().optString("saleId").trim()
        if (saleId.isBlank() || items.any {
                it.optString("saleId").trim() != saleId ||
                    it.optString("code").isBlank() ||
                    it.optInt("qty", 0) <= 0 ||
                    !it.optDouble("subtotal", Double.NaN).isFinite() ||
                    it.optDouble("subtotal", Double.NaN) < 0.0
            }) return false

        // Reconcile the proposed stock snapshot against the currently persisted
        // inventory. A caller cannot silently increase stock or sell more than
        // available by supplying a forged/stale JSON snapshot.
        val previousProducts = allProducts()
        val previousByCode = HashMap<String, JSONObject>()
        for (i in 0 until previousProducts.length()) {
            val product = previousProducts.optJSONObject(i) ?: return false
            val code = product.optString("code").trim()
            if (code.isBlank() || previousByCode.put(code, product) != null) return false
        }
        val nextByCode = HashMap<String, JSONObject>()
        for (i in 0 until array.length()) {
            val product = array.optJSONObject(i) ?: return false
            val code = product.optString("code").trim()
            if (code.isBlank() || nextByCode.put(code, product) != null) return false
        }
        if (previousByCode.keys != nextByCode.keys) return false
        val quantities = HashMap<String, Int>()
        for (item in items) {
            val code = item.optString("code").trim()
            val qty = item.optInt("qty", 0)
            val oldProduct = previousByCode[code] ?: return false
            val price = oldProduct.optDouble("price", Double.NaN)
            val itemPrice = item.optDouble("price", Double.NaN)
            val subtotal = item.optDouble("subtotal", Double.NaN)
            if (!price.isFinite() || price < 0.0 || !itemPrice.isFinite() ||
                kotlin.math.abs(price - itemPrice) > 0.000001 ||
                !subtotal.isFinite() ||
                kotlin.math.abs(subtotal - price * qty) > 0.01) return false
            quantities[code] = (quantities[code] ?: 0) + qty
        }
        for ((code, oldProduct) in previousByCode) {
            val oldStock = oldProduct.optInt("stock", -1)
            val newStock = nextByCode[code]?.optInt("stock", -1) ?: return false
            val soldQty = quantities[code] ?: 0
            if (oldStock < 0 || soldQty > oldStock || newStock != oldStock - soldQty) return false
        }

        val db = writableDatabase
        db.beginTransaction()
        var inserted = false
        try {
            // A ticket may contain several rows sharing one UUID. Refuse a
            // retry if any row from that ticket was already committed.
            db.rawQuery("SELECT data FROM sales", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val existing = try { JSONObject(cursor.getString(0)) } catch (_: Exception) { continue }
                    if (existing.optString("saleId") == saleId) return false
                }
            }
            db.delete("products", null, null)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                check(db.insert("products", null, ContentValues().apply {
                    put("code", item.optString("code", ""))
                    put("data", item.toString())
                }) != -1L) { "No se pudo guardar el inventario de la venta." }
            }
            for (item in items) {
                check(db.insert("sales", null, ContentValues().apply { put("data", item.toString()) }) != -1L) {
                    "No se pudo guardar el detalle de la venta."
                }
            }
            db.setTransactionSuccessful()
            inserted = true
        } finally {
            db.endTransaction()
        }
        return inserted
    }


    /**
     * Applies one already-authorized correction in a single SQLite transaction.
     *
     * The remote grant must have been consumed idempotently and the durable
     * journal moved to "grant_consumed" before this method is called. This
     * method deliberately performs no network calls and never treats the
     * audit row as a second cash movement.
     *
     * Operation payload:
     * { opId, saleId, state:"grant_consumed", grantId, cashConfirmed,
     *   reason, changes:[{fromCode:"", toCode:"SKU", newQty:2}] }
     * An empty fromCode adds an omitted line; newQty=0 removes a line.
     */
    fun applySaleCorrection(operation: JSONObject): Boolean {
        val opId = operation.optString("opId").trim()
        val saleId = operation.optString("saleId").trim()
        val grantId = operation.optString("grantId").trim()
        if (opId.isBlank() || saleId.isBlank() || grantId.isBlank() ||
            operation.optString("state") != "grant_consumed") return false

        val db = writableDatabase
        db.beginTransaction()
        try {
            val journal = db.rawQuery(
                "SELECT sale_id, state, payload FROM correction_ops WHERE op_id=?",
                arrayOf(opId)
            ).use { c -> if (c.moveToFirst()) Triple(c.getString(0), c.getString(1), c.getString(2)) else null }
                ?: return false
            if (journal.first != saleId) return false
            val savedOperation = try { JSONObject(journal.third) } catch (_: Exception) { return false }
            if (savedOperation.optString("opId") != opId ||
                savedOperation.optString("grantId") != grantId ||
                savedOperation.optString("saleId") != saleId) return false
            // Idempotent recovery: a retry after commit must recognize the durable
            // completed journal before requiring the pre-commit payload state.
            if (journal.second == "completed" && savedOperation.optString("state") == "completed") {
                db.setTransactionSuccessful()
                return true
            }
            if (journal.second != "grant_consumed" || savedOperation.optString("state") != "grant_consumed") return false
            // The durable journal is authoritative: never apply caller-supplied changes
            // that differ from the operation persisted before the remote grant was used.
            val changes = savedOperation.optJSONArray("changes") ?: return false
            if (changes.length() == 0) return false

            // A sale can only be corrected once, even if a second local op was queued.
            val priorCorrection = db.rawQuery(
                "SELECT payload FROM correction_ops WHERE sale_id=? AND state='completed' AND op_id<>? LIMIT 1",
                arrayOf(saleId, opId)
            ).use { it.moveToFirst() }
            if (priorCorrection) return false

            val saleRows = ArrayList<Pair<Long, JSONObject>>()
            db.rawQuery("SELECT id, data FROM sales ORDER BY id ASC", null).use { c ->
                while (c.moveToNext()) {
                    val row = try { JSONObject(c.getString(1)) } catch (_: Exception) { continue }
                    if (row.optString("saleId") == saleId) saleRows.add(c.getLong(0) to row)
                }
            }
            if (saleRows.isEmpty()) return false
            val oldTotal = saleRows.sumOf { it.second.optDouble("subtotal", Double.NaN) }
            // Refuse to repair corrupt financial history implicitly: every stored
            // line must have a positive quantity and a finite, non-negative subtotal.
            if (!oldTotal.isFinite() || oldTotal < 0.0 ||
                saleRows.any { it.second.optInt("qty", -1) <= 0 ||
                    !it.second.optDouble("subtotal", Double.NaN).isFinite() ||
                    it.second.optDouble("subtotal", Double.NaN) < 0.0 }) return false
            val originalCodes = saleRows.map { it.second.optString("code").trim() }
            if (originalCodes.any { it.isBlank() } || originalCodes.size != originalCodes.toSet().size) return false
            val updatedRows = saleRows.map { JSONObject(it.second.toString()) }.toMutableList()

            val productRows = ArrayList<JSONObject>()
            db.rawQuery("SELECT data FROM products ORDER BY id ASC", null).use { c ->
                while (c.moveToNext()) productRows.add(JSONObject(c.getString(0)))
            }
            if (productRows.isEmpty()) return false
            fun productIndex(code: String): Int {
                val matches = productRows.indices.filter { productRows[it].optString("code") == code }
                return if (matches.size == 1) matches[0] else -1
            }

            // Proyectar el inventario en dos fases: primero devolver todas las líneas
            // originales y después descontar todos los destinos. Así los cambios
            // múltiples no dependen del orden (por ejemplo, intercambiar dos productos).
            for (i in 0 until changes.length()) {
                val change = changes.optJSONObject(i) ?: return false
                val fromCode = change.optString("fromCode").trim()
                if (fromCode.isEmpty()) continue
                val source = updatedRows.firstOrNull { it.optString("code") == fromCode } ?: return false
                val oldQty = source.optInt("qty", -1)
                if (oldQty <= 0) return false
                val sourceIndex = productIndex(fromCode)
                if (sourceIndex < 0) return false
                val stock = productRows[sourceIndex].optInt("stock", -1)
                if (stock < 0 || stock > Int.MAX_VALUE - oldQty) return false
                productRows[sourceIndex].put("stock", stock + oldQty)
            }

            val changesBySource = LinkedHashMap<String, JSONObject>()
            val additions = ArrayList<JSONObject>()
            for (i in 0 until changes.length()) {
                val change = changes.optJSONObject(i) ?: return false
                val fromCode = change.optString("fromCode").trim()
                val toCode = change.optString("toCode").trim()
                val newQty = change.optInt("newQty", -1)
                if (newQty !in 0..100000) return false
                if (fromCode.isEmpty()) {
                    if (newQty == 0 || toCode.isBlank()) return false
                    additions.add(change)
                } else {
                    if (changesBySource.containsKey(fromCode)) return false
                    if (saleRows.count { it.second.optString("code") == fromCode } != 1) return false
                    if (newQty > 0 && toCode.isBlank()) return false
                    changesBySource[fromCode] = change
                }
            }

            // Rebuild from the immutable original ticket, not from moving list indexes.
            // This supports swaps and several simultaneous replacements without making
            // one newly-created row look like the source row of a later change.
            val finalRows = ArrayList<JSONObject>()
            fun appendTargetLine(oldRow: JSONObject, change: JSONObject): Boolean {
                val toCode = change.optString("toCode").trim()
                val newQty = change.optInt("newQty", -1)
                if (toCode.isBlank() || newQty !in 1..100000) return false
                val targetIndex = productIndex(toCode)
                if (targetIndex < 0) return false
                val target = productRows[targetIndex]
                val available = target.optInt("stock", -1)
                if (available < newQty) return false
                val unitPrice = target.optDouble("price", Double.NaN)
                if (!unitPrice.isFinite() || unitPrice < 0.0) return false
                target.put("stock", available - newQty)
                finalRows.add(JSONObject(oldRow.toString()).put("code", toCode)
                    .put("name", target.optString("name", "Producto"))
                    .put("qty", newQty).put("price", unitPrice)
                    .put("subtotal", unitPrice * newQty))
                return true
            }

            for ((_, originalRow) in saleRows) {
                val fromCode = originalRow.optString("code").trim()
                val change = changesBySource[fromCode]
                if (change == null) {
                    finalRows.add(JSONObject(originalRow.toString()))
                } else if (change.optInt("newQty", -1) > 0) {
                    if (!appendTargetLine(originalRow, change)) return false
                } // newQty == 0 intentionally removes this original ticket line.
            }
            val templateRow = saleRows.first().second
            for (change in additions) {
                if (!appendTargetLine(templateRow, change)) return false
            }
            updatedRows.clear()
            updatedRows.addAll(finalRows)

            if (updatedRows.isEmpty()) return false
            // A ticket may not end up with duplicate product codes: that would make
            // later edits ambiguous and can hide an incorrect stock calculation.
            val finalCodes = updatedRows.map { it.optString("code").trim() }
            if (finalCodes.any { it.isBlank() } || finalCodes.size != finalCodes.toSet().size) return false
            val newTotal = updatedRows.sumOf { it.optDouble("subtotal", Double.NaN) }
            if (!newTotal.isFinite() || newTotal < 0.0) return false
            val rawDeltaCents = (newTotal - oldTotal) * 100.0
            if (!rawDeltaCents.isFinite() || kotlin.math.abs(rawDeltaCents) > Long.MAX_VALUE.toDouble()) return false
            val deltaCents = kotlin.math.round(rawDeltaCents).toLong()
            // A non-zero difference must carry durable evidence of the real-world
            // collection/refund. A bare checkbox is not sufficient for an audit.
            val cashEvidence = savedOperation.optJSONObject("cashEvidence")
            if (deltaCents != 0L) {
                if (!savedOperation.optBoolean("cashConfirmed", false) || cashEvidence == null) return false
                val method = cashEvidence.optString("method").trim().lowercase()
                val reference = cashEvidence.optString("reference").trim()
                val evidenceAmount = cashEvidence.optLong("amountCents", Long.MIN_VALUE)
                val confirmedAt = cashEvidence.optLong("confirmedAt", 0L)
                if (method !in setOf("cash", "card", "transfer", "other") ||
                    reference.length !in 3..120 ||
                    evidenceAmount != kotlin.math.abs(deltaCents) ||
                    confirmedAt <= 0L) return false
            }
            val now = System.currentTimeMillis()
            val totalCount = updatedRows.size
            for (row in updatedRows) {
                row.put("saleTotal", newTotal)
                    .put("saleItemCount", totalCount)
                    .put("correctionOpId", opId)
            }

            // Replace the sale rows, inventory, audit evidence and journal state atomically.
            for ((rowId, _) in saleRows) db.delete("sales", "id=?", arrayOf(rowId.toString()))
            for (row in updatedRows) {
                check(db.insert("sales", null, ContentValues().apply { put("data", row.toString()) }) != -1L) {
                    "No se pudo actualizar el ticket corregido."
                }
            }
            db.delete("products", null, null)
            for (product in productRows) {
                check(db.insert("products", null, ContentValues().apply {
                    put("code", product.optString("code", ""))
                    put("data", product.toString())
                }) != -1L) { "No se pudo actualizar el inventario corregido." }
            }

            val direction = when {
                deltaCents > 0L -> "COBRO"
                deltaCents < 0L -> "DEVOLUCION"
                else -> "SIN_MOVIMIENTO"
            }
            val audit = JSONObject()
                .put("type", "CORRECTION_AUDIT")
                .put("ts", now)
                .put("saleId", saleId)
                .put("correctionOpId", opId)
                .put("grantId", grantId)
                .put("reason", savedOperation.optString("reason").take(500))
                .put("cashReason", savedOperation.optString("cashReason").take(120))
                .put("detail", savedOperation.optString("detail").take(1000))
                .put("previousTotal", oldTotal)
                .put("newTotal", newTotal)
                .put("amount", kotlin.math.abs(deltaCents) / 100.0)
                .put("direction", direction)
                .put("cashConfirmed", savedOperation.optBoolean("cashConfirmed", false))
                .put("cashEvidence", cashEvidence ?: JSONObject())
                .put("countedInCash", false)
            check(db.insert("moves", null, ContentValues().apply { put("data", audit.toString()) }) != -1L) {
                "No se pudo guardar la evidencia de auditoría."
            }

            val completed = JSONObject(savedOperation.toString()).put("state", "completed").put("completedAt", now)
            val values = ContentValues().apply {
                put("state", "completed")
                put("payload", completed.toString())
                put("updated_at", now)
            }
            check(db.update("correction_ops", values, "op_id=?", arrayOf(opId)) == 1) {
                "No se pudo cerrar el registro de corrección."
            }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun all(table: String): JSONArray {
        require(table == "sales" || table == "moves")
        val result = JSONArray()
        readableDatabase.rawQuery("SELECT data FROM $table ORDER BY id ASC", null).use { cursor ->
            while (cursor.moveToNext()) {
                try {
                    result.put(JSONObject(cursor.getString(0)))
                } catch (_: Exception) {
                }
            }
        }
        return result
    }

    fun replace(table: String, array: JSONArray) {
        require(table == "sales" || table == "moves")
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(table, null, null)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i)
                    ?: throw IllegalArgumentException("Registro inválido en $table")
                db.insertOrThrow(table, null, ContentValues().apply { put("data", item.toString()) })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun append(table: String, item: JSONObject) {
        require(table == "sales" || table == "moves")
        writableDatabase.insertOrThrow(table, null, ContentValues().apply { put("data", item.toString()) })
    }

    fun appendMany(table: String, items: List<JSONObject>) {
        require(table == "sales" || table == "moves")
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (item in items) {
                db.insertOrThrow(table, null, ContentValues().apply { put("data", item.toString()) })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

object Store {
    private const val PREF = "sene_ventas_local"
    private const val KEY = "datos"

    @Volatile private var history: HistoryDb? = null

    @Synchronized
    private fun db(context: Context): HistoryDb {
        val helper = history ?: HistoryDb(context.applicationContext).also { history = it }
        helper.migrate(context.applicationContext)
        helper.migrateProducts(context.applicationContext)
        return helper
    }

    private fun root(context: Context): JSONObject {
        val raw = context.getSharedPreferences(PREF, 0).getString(KEY, null)
        return try {
            if (raw == null) JSONObject() else JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun put(context: Context, value: JSONObject) {
        context.getSharedPreferences(PREF, 0).edit().putString(KEY, value.toString()).apply()
        try { ReportFile.update(context, value) } catch (_: Exception) { }
    }

    fun products(context: Context): JSONArray {
        val storage = db(context)
        return if (storage.productsReady()) storage.allProducts()
        else root(context).optJSONArray("products") ?: JSONArray()
    }

    fun saveCorrectionOperation(context: Context, operation: JSONObject): Boolean = db(context).saveCorrectionOperation(operation)
    fun updateCorrectionOperation(context: Context, opId: String, state: String, operation: JSONObject): Boolean = db(context).updateCorrectionOperation(opId, state, operation)
    fun pendingCorrectionOperations(context: Context): JSONArray = db(context).pendingCorrectionOperations()
    fun applySaleCorrection(context: Context, operation: JSONObject): Boolean {
        val storage = db(context)
        if (!storage.applySaleCorrection(operation)) return false
        val value = root(context)
        value.put("products", storage.allProducts()).put("stockUpdatedAt", System.currentTimeMillis())
        check(context.getSharedPreferences(PREF, 0).edit().putString(KEY, value.toString()).commit()) {
            "La corrección quedó guardada en SQLite, pero no se pudo actualizar el respaldo local; vuelve a abrir el historial para sincronizarlo."
        }
        try { ReportFile.update(context, value) } catch (_: Exception) { }
        return true
    }

    fun sales(context: Context) = db(context).all("sales")
    fun moves(context: Context) = db(context).all("moves")
    fun shift(context: Context) = root(context).optJSONObject("shift")
    fun phone(context: Context) = root(context).optString("phone", "523311337592")

    fun setPhone(context: Context, phone: String) {
        val value = root(context)
        value.put("phone", phone)
        put(context, value)
    }

    fun saveProducts(context: Context, array: JSONArray) {
        val storage = db(context)
        check(storage.productsReady()) { "No se pudo verificar la migración del inventario; no se guardaron cambios." }
        storage.replaceProducts(array)
        val value = root(context)
        value.put("products", array).put("stockUpdatedAt", System.currentTimeMillis())
        context.getSharedPreferences(PREF, 0).edit().putString(KEY, value.toString()).commit()
        try { ReportFile.update(context, value) } catch (_: Exception) { }
    }

    fun recordSale(context: Context, products: JSONArray, items: List<JSONObject>): Boolean {
        require(items.isNotEmpty()) { "La venta no contiene productos." }
        val storage = db(context)
        check(storage.productsReady()) { "No se pudo verificar la migración del inventario; no se registró la venta." }
        if (!storage.recordSale(products, items)) return false
        val value = root(context)
        value.put("products", products).put("stockUpdatedAt", System.currentTimeMillis())
        // SQLite is authoritative. Preferences and TXT are secondary snapshots;
        // their failure must not make a committed sale look unsuccessful.
        context.getSharedPreferences(PREF, 0).edit().putString(KEY, value.toString()).commit()
        try { ReportFile.update(context, value) } catch (_: Exception) { }
        return true
    }

    fun stockUpdatedAtText(context: Context): String {
        val timestamp = root(context).optLong("stockUpdatedAt", 0L)
        return if (timestamp > 0L) java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale("es", "MX")).format(java.util.Date(timestamp))
        else "Sin registro previo de actualización"
    }

    fun saveSales(context: Context, array: JSONArray) {
        db(context).replace("sales", array)
        try { ReportFile.update(context) } catch (_: Exception) { }
    }

    fun saveShift(context: Context, shift: JSONObject?) {
        val value = root(context)
        if (shift == null) value.remove("shift") else value.put("shift", shift)
        put(context, value)
    }

    fun addSale(context: Context, item: JSONObject) = addSales(context, listOf(item))

    fun addSales(context: Context, items: List<JSONObject>) {
        if (items.isEmpty()) return
        db(context).appendMany("sales", items)
        try { ReportFile.update(context) } catch (_: Exception) { }
    }

    fun addMove(context: Context, item: JSONObject) {
        db(context).append("moves", item)
        try { ReportFile.update(context) } catch (_: Exception) { }
    }

    fun providerExpenses(context: Context, since: Long = 0L): Double {
        val all = moves(context)
        var total = 0.0
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i) ?: continue
            if (item.optString("type") == "PROVIDER_PAYMENT" && item.optLong("ts", 0L) >= since) {
                total += item.optDouble("amount", 0.0)
            }
        }
        return total
    }

    fun cashBalance(context: Context): Double {
        val shift = shift(context) ?: return 0.0
        val start = shift.optLong("startTs", 0L)
        val all = sales(context)
        var sold = 0.0
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i) ?: continue
            if (item.optLong("ts", 0L) >= start) sold += item.optDouble("subtotal", 0.0)
        }
        return shift.optDouble("cash", 0.0) + sold - providerExpenses(context, start)
    }

    fun money(value: Double) = "$" + String.format(java.util.Locale.US, "%.2f", value)
}
