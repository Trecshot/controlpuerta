package com.example.controlpuerta.security

import java.security.MessageDigest

data class PasswordValidationResult(
    val isValid: Boolean,
    val hasMinLength: Boolean,
    val hasUpperAndLower: Boolean,
    val hasDigit: Boolean,
    val hasSpecialChar: Boolean,
    val notSameAsUsername: Boolean,
    val errorMessage: String
)

object SecurityUtils {

    private val COMMON_PATTERNS = setOf("admin", "12345678", "password", "qwerty", "controlpuerta")

    fun hashSha512(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-512").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun validatePasswordStrength(username: String, password: String): PasswordValidationResult {
        val cleanUser = username.trim().lowercase()
        val lowerPass = password.lowercase()
        val userTokens = cleanUser.split("_", ".", "-", " ").filter { it.length >= 3 }

        val hasMinLength = password.length >= 8
        val hasUpper = password.any { it.isUpperCase() }
        val hasLower = password.any { it.isLowerCase() }
        val hasUpperAndLower = hasUpper && hasLower
        val hasDigit = password.any { it.isDigit() }
        val hasSpecialChar = password.any { !it.isLetterOrDigit() }

        val containsUsername = (cleanUser.isNotEmpty() && lowerPass == cleanUser) ||
                (cleanUser.length >= 3 && lowerPass.contains(cleanUser)) ||
                userTokens.any { lowerPass.contains(it) }
        val containsCommonPattern = COMMON_PATTERNS.any { lowerPass.contains(it) }
        val notSameAsUsername = password.isNotEmpty() && !containsUsername && !containsCommonPattern

        val isValid = hasMinLength && hasUpperAndLower && hasDigit && hasSpecialChar && notSameAsUsername

        val errorMessage = when {
            !hasMinLength -> "La contraseña debe tener al menos 8 caracteres."
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
}
