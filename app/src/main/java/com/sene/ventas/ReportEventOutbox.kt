package com.sene.ventas

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cola durable de reportes cifrados para transporte al servidor.
 * SQLite evita guardar reportes grandes en un único valor de SharedPreferences.
 * Los eventos no se eliminan hasta recibir el ACK explícito del servidor.
 */
object ReportEventOutbox {
    private const val PREFS = "sene_report_wire_outbox"
    private const val LEGACY_KEY = "pending"
    private const val MIGRATED_KEY = "migrated_to_sqlite"
    private const val DB_NAME = "sene_report_wire_outbox.db"

    private class Db(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE report_events (event_id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX report_events_created_idx ON report_events(created_at)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Volatile private var helper: Db? = null
    @Synchronized private fun database(c: Context): Db {
        val app = c.applicationContext
        val db = helper ?: Db(app).also { helper = it }
        migrateLegacy(app, db.writableDatabase)
        return db
    }

    /** Migra la cola anterior sin borrar su copia hasta confirmar la transacción SQLite. */
    private fun migrateLegacy(c: Context, db: SQLiteDatabase) {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(MIGRATED_KEY, false)) return
        val raw = prefs.getString(LEGACY_KEY, null)
        if (raw.isNullOrBlank()) {
            prefs.edit().putBoolean(MIGRATED_KEY, true).commit()
            return
        }
        val legacy = try { JSONArray(raw) } catch (_: Exception) { return }
        db.beginTransaction()
        try {
            for (i in 0 until legacy.length()) {
                val event = legacy.optJSONObject(i) ?: continue
                val id = event.optString("e")
                if (id.isBlank() || event.optString("b").isBlank()) continue
                val values = ContentValues().apply {
                    put("event_id", id)
                    put("payload", event.toString())
                    put("created_at", event.optLong("i", 0L))
                }
                db.insertWithOnConflict("report_events", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } catch (_: Exception) {
            return
        } finally {
            db.endTransaction()
        }
        // La cola anterior se conserva como respaldo hasta confirmar el indicador.
        prefs.edit().putBoolean(MIGRATED_KEY, true).commit()
    }

    @Synchronized
    fun enqueue(
        context: Context,
        eventId: String,
        workerId: String,
        reportType: String,
        encryptedPayload: String,
        createdAt: Long = System.currentTimeMillis()
    ): Boolean {
        if (eventId.isBlank() || encryptedPayload.isBlank()) return false
        return try {
            val event = JSONObject()
                .put("v", SeneWireProtocol.VERSION)
                .put("t", SeneWireProtocol.SYNC)
                .put("e", eventId)
                .put("w", workerId)
                .put("k", reportType)
                .put("i", createdAt)
                .put("b", encryptedPayload)
            val db = database(context).writableDatabase
            val values = ContentValues().apply {
                put("event_id", eventId)
                put("payload", event.toString())
                put("created_at", createdAt)
            }
            db.insertWithOnConflict("report_events", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L ||
                exists(db, eventId)
        } catch (_: Exception) { false }
    }

    @Synchronized
    fun pending(context: Context): JSONArray {
        val result = JSONArray()
        return try {
            val db = database(context).readableDatabase
            db.rawQuery("SELECT payload FROM report_events ORDER BY created_at ASC, event_id ASC", null).use { cursor ->
                while (cursor.moveToNext()) {
                    try { result.put(JSONObject(cursor.getString(0))) } catch (_: Exception) { }
                }
            }
            result
        } catch (_: Exception) { JSONArray() }
    }

    @Synchronized
    fun acknowledge(context: Context, acknowledgements: JSONArray?) {
        if (acknowledgements == null || acknowledgements.length() == 0) return
        val accepted = linkedSetOf<String>()
        for (i in 0 until acknowledgements.length()) {
            val item = acknowledgements.opt(i)
            val id = when (item) {
                is JSONObject -> item.optString("e", item.optString("id"))
                else -> item?.toString().orEmpty()
            }
            if (id.isNotBlank()) accepted.add(id)
        }
        if (accepted.isEmpty()) return
        try {
            val db = database(context).writableDatabase
            db.beginTransaction()
            try {
                accepted.forEach { id -> db.delete("report_events", "event_id=?", arrayOf(id)) }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } catch (_: Exception) {
            // Un fallo al borrar conserva la cola; el servidor reconocerá el reintento por ID.
        }
    }

    private fun exists(db: SQLiteDatabase, id: String): Boolean =
        db.rawQuery("SELECT 1 FROM report_events WHERE event_id=? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }
}
