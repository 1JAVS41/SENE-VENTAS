package com.sene.ventas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Pruebas de la lógica pura de ahorro de solicitudes (sin teléfono ni red).
// No hay pruebas unitarias previas en el repo: este es el primer archivo de pruebas.
class LicenseAndInternetTimeCacheTest {

    private val day = 24L * 60L * 60L * 1000L
    private val hour = 60L * 60L * 1000L

    @Test
    fun license_sinFechaGuardada_preguntaAlServidor() {
        assertTrue(ActivationApi.shouldRefreshLicenseStatus(0L, 0L, 1_000L))
    }

    @Test
    fun license_fechaLejanaYConsultaReciente_noPregunta() {
        val now = 1_700_000_000_000L
        val expiry = now + 30 * day
        assertFalse(ActivationApi.shouldRefreshLicenseStatus(expiry, now - hour, now))
    }

    @Test
    fun license_consultaDeHaceMasDe24Horas_pregunta() {
        val now = 1_700_000_000_000L
        val expiry = now + 30 * day
        assertTrue(ActivationApi.shouldRefreshLicenseStatus(expiry, now - 25 * hour, now))
    }

    @Test
    fun license_venceEnMenosDe2Dias_preguntaAunqueLaConsultaSeaReciente() {
        val now = 1_700_000_000_000L
        val expiry = now + day
        assertTrue(ActivationApi.shouldRefreshLicenseStatus(expiry, now - hour, now))
    }

    @Test
    fun license_yaVencida_pregunta() {
        val now = 1_700_000_000_000L
        assertTrue(ActivationApi.shouldRefreshLicenseStatus(now - hour, now - hour, now))
    }

    @Test
    fun license_relojDelTelefonoRetrocedio_pregunta() {
        val now = 1_700_000_000_000L
        val expiry = now + 30 * day
        assertTrue(ActivationApi.shouldRefreshLicenseStatus(expiry, now + hour, now))
    }

    @Test
    fun license_fechaDelServidorSeEntiende() {
        val millis = ActivationApi.parseLicenseExpiryMillis("2026-12-16T00:00:00.000Z")
        assertTrue(millis > 0L)
        assertEquals(0L, ActivationApi.parseLicenseExpiryMillis(""))
        assertEquals(0L, ActivationApi.parseLicenseExpiryMillis("no-es-una-fecha"))
    }

    @Test
    fun internetTime_baseReciente_calculaConRelojDelTelefono() {
        val base = 1_700_000_000_000L
        val cached = InternetTimeCache.cachedNowMillis(base, 10_000L, base, 70_000L, base + 60_000L)
        assertEquals(base + 60_000L, cached)
    }

    @Test
    fun internetTime_masDe12Horas_vuelveAPedir() {
        val base = 1_700_000_000_000L
        assertNull(InternetTimeCache.cachedNowMillis(base, 10_000L, base, 10_000L + 13 * hour, base + 13 * hour))
    }

    @Test
    fun internetTime_cambioLaHoraDelTelefono_vuelveAPedir() {
        val base = 1_700_000_000_000L
        // El teléfono avanzó 10 minutos de golpe: no fiarse de la base guardada.
        assertNull(InternetTimeCache.cachedNowMillis(base, 10_000L, base, 70_000L, base + 10 * 60_000L))
    }

    @Test
    fun internetTime_reinicioDelTelefono_vuelveAPedir() {
        val base = 1_700_000_000_000L
        assertNull(InternetTimeCache.cachedNowMillis(base, 9_000_000L, base, 5_000L, base + 60_000L))
    }
}
