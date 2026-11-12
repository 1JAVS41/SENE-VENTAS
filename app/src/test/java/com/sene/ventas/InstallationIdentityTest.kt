package com.sene.ventas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class InstallationIdentityTest {
    @Test fun samePhoneAndPackageKeepSameLogicalId() {
        val first = deriveInstallationId("com.sene.ventas", "android-id-sample")
        val afterReinstall = deriveInstallationId("com.sene.ventas", "android-id-sample")
        assertEquals(first, afterReinstall)
    }

    @Test fun differentPhonesDoNotShareLogicalId() {
        val first = deriveInstallationId("com.sene.ventas", "phone-one")
        val second = deriveInstallationId("com.sene.ventas", "phone-two")
        assertNotEquals(first, second)
    }

    @Test fun differentAppsDoNotShareLogicalId() {
        val ventas = deriveInstallationId("com.sene.ventas", "same-phone")
        val patron = deriveInstallationId("com.sene.patron", "same-phone")
        assertNotEquals(ventas, patron)
    }
}
