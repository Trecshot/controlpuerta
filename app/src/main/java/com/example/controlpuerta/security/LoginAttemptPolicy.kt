package com.example.controlpuerta.security

/**
 * Reglas puras del bloqueo por intentos fallidos (se evalúan dentro de una transacción de Firestore).
 */
object LoginAttemptPolicy {
    const val MAX_ATTEMPTS: Int = 3

    data class Outcome(val attempts: Int, val blocked: Boolean, val remaining: Int)

    fun nextOutcome(currentAttempts: Int, alreadyBlocked: Boolean): Outcome {
        if (alreadyBlocked) return Outcome(MAX_ATTEMPTS, blocked = true, remaining = 0)
        val next = (currentAttempts.coerceAtLeast(0) + 1).coerceAtMost(MAX_ATTEMPTS)
        val blocked = next >= MAX_ATTEMPTS
        return Outcome(next, blocked, (MAX_ATTEMPTS - next).coerceAtLeast(0))
    }
}
