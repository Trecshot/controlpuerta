package com.example.controlpuerta.security

/**
 * Datos mínimos de una sesión persistida (nunca almacena contraseñas ni hashes).
 */
data class StoredSession(
    val username: String,
    val rol: String,
    val expiresAtMillis: Long
)

/**
 * Reglas puras de expiración y validación de sesiones persistentes (TTL por defecto: 12 horas).
 */
object SessionPolicy {
    const val SESSION_TTL_MS: Long = 12L * 60L * 60L * 1000L

    fun calculateExpiresAt(
        nowMillis: Long,
        ttlMillis: Long = SESSION_TTL_MS
    ): Long {
        val safeTtl = if (ttlMillis > 0L) ttlMillis else 0L
        return nowMillis + safeTtl
    }

    fun isSessionActive(
        expiresAtMillis: Long,
        nowMillis: Long
    ): Boolean = expiresAtMillis > 0L && nowMillis <= expiresAtMillis

    fun validateStoredSession(
        username: String?,
        rol: String?,
        expiresAtMillis: Long,
        nowMillis: Long
    ): StoredSession? {
        val cleanUser = username?.trim().orEmpty()
        val cleanRol = rol?.trim().orEmpty()
        if (cleanUser.isEmpty() || cleanRol.isEmpty()) return null
        if (!isSessionActive(expiresAtMillis, nowMillis)) return null
        return StoredSession(
            username = cleanUser,
            rol = cleanRol,
            expiresAtMillis = expiresAtMillis
        )
    }
}
