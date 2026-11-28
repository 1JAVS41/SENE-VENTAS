package com.sene.ventas

import android.content.Context
import android.os.SystemClock
import kotlin.math.abs

// Ahorro de solicitudes de hora de internet: se pide una sola vez y se guarda la base
// (hora de internet + reloj del teléfono en ese momento). Después, la hora de la pantalla
// se calcula con el reloj del teléfono, sin pedir nada por internet. Solo se vuelve a
// pedir si pasaron más de 12 horas o si el teléfono cambió su hora.
object InternetTimeCache {
    const val MAX_AGE_MILLIS = 12L * 60L * 60L * 1000L // 12 horas
    const val CLOCK_CHANGE_TOLERANCE_MILLIS = 60_000L // 1 minuto
    private const val PREFS = "sene_internet_time"
    private const val KEY_BASE_MILLIS = "internet_base_millis"
    private const val KEY_BASE_ELAPSED = "internet_base_elapsed"
    private const val KEY_BASE_WALL_MILLIS = "internet_base_wall_millis"

    // Decisión pura (sin teléfono ni red): hora de internet actual según la base guardada,
    // o null si hay que volver a pedirla (sin base, más de 12 horas, reinicio del teléfono
    // o cambio de la hora del teléfono).
    fun cachedNowMillis(
        baseMillis: Long,
        baseElapsedRealtime: Long,
        savedWallMillis: Long,
        nowElapsedRealtime: Long,
        nowWallMillis: Long
    ): Long? {
        if (baseMillis <= 0L || baseElapsedRealtime <= 0L || savedWallMillis <= 0L) return null
        val elapsedDelta = nowElapsedRealtime - baseElapsedRealtime
        if (elapsedDelta < 0L || elapsedDelta > MAX_AGE_MILLIS) return null
        val expectedWallMillis = savedWallMillis + elapsedDelta
        if (abs(nowWallMillis - expectedWallMillis) > CLOCK_CHANGE_TOLERANCE_MILLIS) return null
        return baseMillis + elapsedDelta
    }

    fun loadCachedNow(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return cachedNowMillis(
            prefs.getLong(KEY_BASE_MILLIS, 0L),
            prefs.getLong(KEY_BASE_ELAPSED, 0L),
            prefs.getLong(KEY_BASE_WALL_MILLIS, 0L),
            SystemClock.elapsedRealtime(),
            System.currentTimeMillis()
        )
    }

    fun save(context: Context, internetMillis: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_BASE_MILLIS, internetMillis)
            .putLong(KEY_BASE_ELAPSED, SystemClock.elapsedRealtime())
            .putLong(KEY_BASE_WALL_MILLIS, System.currentTimeMillis())
            .apply()
    }

    // Hora de internet ahorrando solicitudes: usa la base guardada si sigue vigente;
    // solo llama a fetch() (que pide por internet) cuando hace falta.
    fun currentTimeMillis(context: Context, fetch: () -> Long?): Long? {
        loadCachedNow(context)?.let { return it }
        val fresh = fetch() ?: return null
        save(context, fresh)
        return fresh
    }

    // Vuelve a pedir la hora por internet aunque la base guardada siga vigente.
    // Solo se usa cuando el usuario toca "ACTUALIZAR HORA DE INTERNET".
    fun refreshNow(context: Context, fetch: () -> Long?): Long? {
        val fresh = fetch() ?: return loadCachedNow(context)
        save(context, fresh)
        return fresh
    }
}
