package com.example.controlpuerta.security

import com.example.controlpuerta.model.Usuario
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class PasswordValidationResult(
    val isValid: Boolean,
    val hasMinLength: Boolean,
    val hasUpperAndLower: Boolean,
    val hasDigit: Boolean,
    val hasSpecialChar: Boolean,
    val notSameAsUsername: Boolean,
    val errorMessage: String
)

/** Credencial derivada lista para persistir. */
data class DerivedCredential(
    val hash: String,
    val salt: String,
    val iterations: Int
)

object SecurityUtils {

    const val PBKDF2_ITERATIONS: Int = 120_000
    const val MAX_PASSWORD_LENGTH: Int = 128

    private const val MIN_PASSWORD_LENGTH = 8
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"

    private val USERNAME_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{2,31}$")
    private val COMMON_PATTERNS = setOf("admin", "12345678", "password", "qwerty", "controlpuerta")
    private val secureRandom = SecureRandom()

    /**
     * Valida de forma estricta (allowlist) un identificador de usuario. Impide `/`, espacios,
     * `.`/`..` y longitudes anómalas que corromperían rutas de documentos de Firestore.
     */
    fun isValidUsername(username: String): Boolean = USERNAME_REGEX.matches(username)

    /** Hash heredado SHA-512 sin sal. Solo se conserva para verificar y migrar cuentas antiguas. */
    fun hashSha512(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-512").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.toHex()
    }

    fun generateSalt(): String {
        val salt = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(salt)
        return salt.toHex()
    }

    fun hashPassword(
        password: String,
        saltHex: String,
        iterations: Int = PBKDF2_ITERATIONS
    ): String {
        val spec = PBEKeySpec(password.toCharArray(), saltHex.hexToBytes(), iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded.toHex()
        } finally {
            spec.clearPassword()
        }
    }

    fun deriveCredential(password: String): DerivedCredential {
        val salt = generateSalt()
        return DerivedCredential(
            hash = hashPassword(password, salt, PBKDF2_ITERATIONS),
            salt = salt,
            iterations = PBKDF2_ITERATIONS
        )
    }

    /** Verifica la clave contra el usuario (PBKDF2 con sal o hash heredado) en tiempo constante. */
    fun verifyPassword(password: String, usuario: Usuario): Boolean {
        if (password.isEmpty() || password.length > MAX_PASSWORD_LENGTH) return false
        val computed = if (isLegacyHash(usuario)) {
            hashSha512(password)
        } else {
            hashPassword(password, usuario.passwordSalt, usuario.hashIterations)
        }
        return MessageDigest.isEqual(
            computed.toByteArray(Charsets.UTF_8),
            usuario.passwordHash.toByteArray(Charsets.UTF_8)
        )
    }

    fun isLegacyHash(usuario: Usuario): Boolean =
        usuario.passwordSalt.isEmpty() || usuario.hashIterations <= 0

    fun validatePasswordStrength(username: String, password: String): PasswordValidationResult {
        val cleanUser = username.trim().lowercase()
        val lowerPass = password.lowercase()
        val userTokens = cleanUser.split("_", ".", "-", " ").filter { it.length >= 3 }

        val hasMinLength = password.length >= MIN_PASSWORD_LENGTH
        val withinMaxLength = password.length <= MAX_PASSWORD_LENGTH
        val hasUpper = password.any { it.isUpperCase() }
        val hasLower = password.any { it.isLowerCase() }
        val hasUpperAndLower = hasUpper && hasLower
        val hasDigit = password.any { it.isDigit() }
        // Los espacios no cuentan como carácter especial: "Abcdefg1    " no es una clave robusta.
        val hasSpecialChar = password.any { !it.isLetterOrDigit() && !it.isWhitespace() }

        val containsUsername = (cleanUser.isNotEmpty() && lowerPass == cleanUser) ||
                (cleanUser.length >= 3 && lowerPass.contains(cleanUser)) ||
                userTokens.any { lowerPass.contains(it) }
        val containsCommonPattern = COMMON_PATTERNS.any { lowerPass.contains(it) }
        val notSameAsUsername = password.isNotEmpty() && !containsUsername && !containsCommonPattern

        val isValid = hasMinLength && withinMaxLength && hasUpperAndLower && hasDigit &&
                hasSpecialChar && notSameAsUsername

        val errorMessage = when {
            !hasMinLength -> "La contraseña debe tener al menos 8 caracteres."
            !withinMaxLength -> "La contraseña no puede superar $MAX_PASSWORD_LENGTH caracteres."
            !hasUpperAndLower -> "Debe incluir al menos una letra mayúscula y una minúscula."
            !hasDigit -> "Debe incluir al menos un número."
            !hasSpecialChar -> "Debe incluir al menos un carácter especial (@, #, $, !, etc.)."
            !notSameAsUsername -> "La contraseña no puede contener el nombre de usuario ni patrones comunes."
            else -> ""
        }

        return PasswordValidationResult(
            isValid = isValid,
            hasMinLength = hasMinLength,
            hasUpperAndLower = hasUpperAndLower,
            hasDigit = hasDigit,
            hasSpecialChar = hasSpecialChar,
            notSameAsUsername = notSameAsUsername,
            errorMessage = errorMessage
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Sal hexadecimal inválida." }
        return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
