package com.example.controlpuerta.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginAttemptPolicyTest {

    @Test
    fun incrementsAttemptsAndReportsRemaining() {
        val first = LoginAttemptPolicy.nextOutcome(currentAttempts = 0, alreadyBlocked = false)
        assertEquals(1, first.attempts)
        assertFalse(first.blocked)
        assertEquals(2, first.remaining)

        val second = LoginAttemptPolicy.nextOutcome(currentAttempts = 1, alreadyBlocked = false)
        assertEquals(2, second.attempts)
        assertFalse(second.blocked)
        assertEquals(1, second.remaining)
    }

    @Test
    fun blocksExactlyOnThirdFailure() {
        val third = LoginAttemptPolicy.nextOutcome(currentAttempts = 2, alreadyBlocked = false)
        assertEquals(LoginAttemptPolicy.MAX_ATTEMPTS, third.attempts)
        assertTrue(third.blocked)
        assertEquals(0, third.remaining)
    }

    @Test
    fun alreadyBlockedAccountStaysBlockedAndNeverExceedsMax() {
        val outcome = LoginAttemptPolicy.nextOutcome(currentAttempts = 3, alreadyBlocked = true)
        assertTrue(outcome.blocked)
        assertEquals(LoginAttemptPolicy.MAX_ATTEMPTS, outcome.attempts)
        assertEquals(0, outcome.remaining)
    }

    @Test
    fun corruptedCountersAreClampedSafely() {
        val negative = LoginAttemptPolicy.nextOutcome(currentAttempts = -5, alreadyBlocked = false)
        assertEquals(1, negative.attempts)
        val huge = LoginAttemptPolicy.nextOutcome(currentAttempts = 999, alreadyBlocked = false)
        assertEquals(LoginAttemptPolicy.MAX_ATTEMPTS, huge.attempts)
        assertTrue(huge.blocked)
    }
}
