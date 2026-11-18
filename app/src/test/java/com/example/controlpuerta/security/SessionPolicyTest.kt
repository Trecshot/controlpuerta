package com.example.controlpuerta.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPolicyTest {

    @Test
    fun defaultTtlIsTwelveHours() {
        assertEquals(12L * 60L * 60L * 1000L, SessionPolicy.SESSION_TTL_MS)
        val base = 1_000_000L
        assertEquals(base + 43_200_000L, SessionPolicy.calculateExpiresAt(base))
    }

    @Test
    fun isSessionActiveRespectsExpirationBoundary() {
        val expiresAt = 10_000L
        assertTrue(SessionPolicy.isSessionActive(expiresAtMillis = expiresAt, nowMillis = 9_999L))
        assertTrue(SessionPolicy.isSessionActive(expiresAtMillis = expiresAt, nowMillis = 10_000L))
        assertFalse(SessionPolicy.isSessionActive(expiresAtMillis = expiresAt, nowMillis = 10_001L))
        assertFalse(SessionPolicy.isSessionActive(expiresAtMillis = 0L, nowMillis = 100L))
    }

    @Test
    fun validateStoredSessionRejectsBlankExpiredAndAcceptsValidSession() {
        val now = 50_000L
        val valid = SessionPolicy.validateStoredSession(
            username = " operador1 ",
            rol = " USUARIO ",
            expiresAtMillis = now + 60_000L,
            nowMillis = now
        )
        assertNotNull(valid)
        assertEquals("operador1", valid?.username)
        assertEquals("USUARIO", valid?.rol)

        assertNull(
            SessionPolicy.validateStoredSession(
                username = "",
                rol = "USUARIO",
                expiresAtMillis = now + 60_000L,
                nowMillis = now
            )
        )
        assertNull(
            SessionPolicy.validateStoredSession(
                username = "operador1",
                rol = "",
                expiresAtMillis = now + 60_000L,
                nowMillis = now
            )
        )
        assertNull(
            SessionPolicy.validateStoredSession(
                username = "operador1",
                rol = "USUARIO",
                expiresAtMillis = now - 1L,
                nowMillis = now
            )
        )
    }
}
