package com.example.controlpuerta.security

import com.example.controlpuerta.model.Usuario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityUtilsTest {

    // ---------- Política de contraseñas ----------

    @Test
    fun rejectsPasswordContainingUsernameOrAdminPattern() {
        val resultSameUser = SecurityUtils.validatePasswordStrength("admin", "Admin123+")
        assertFalse(resultSameUser.isValid)
        assertFalse(resultSameUser.notSameAsUsername)

        val resultOtherUserWithAdmin = SecurityUtils.validatePasswordStrength("operador1", "Admin123+")
        assertFalse(resultOtherUserWithAdmin.isValid)
        assertFalse(resultOtherUserWithAdmin.notSameAsUsername)

        val resultContainingCustomUser = SecurityUtils.validatePasswordStrength("carlos", "Carlos#2026")
        assertFalse(resultContainingCustomUser.isValid)
        assertFalse(resultContainingCustomUser.notSameAsUsername)

        val resultContainingUserToken = SecurityUtils.validatePasswordStrength("juan.perez", "Perez@2026")
        assertFalse(resultContainingUserToken.isValid)
        assertFalse(resultContainingUserToken.notSameAsUsername)
    }

    @Test
    fun rejectsMissingComplexityRules() {
        assertFalse(SecurityUtils.validatePasswordStrength("operador1", "Ab1+").isValid)
        assertFalse(SecurityUtils.validatePasswordStrength("operador1", "segura123+").isValid)
        assertFalse(SecurityUtils.validatePasswordStrength("operador1", "SEGURA123+").isValid)
        assertFalse(SecurityUtils.validatePasswordStrength("operador1", "Segura+++").isValid)
        assertFalse(SecurityUtils.validatePasswordStrength("operador1", "Segura1234").isValid)
    }

    @Test
    fun acceptsStrongCompliantPassword() {
        val result = SecurityUtils.validatePasswordStrength("operador1", "PuertaSegura#9")
        assertTrue(result.isValid)
        assertTrue(result.hasMinLength)
        assertTrue(result.hasUpperAndLower)
        assertTrue(result.hasDigit)
        assertTrue(result.hasSpecialChar)
        assertTrue(result.notSameAsUsername)

        assertFalse(SecurityUtils.validatePasswordStrength("admin_maestro", "AdminFicticio#99").isValid)
        assertTrue(SecurityUtils.validatePasswordStrength("admin_maestro", "LlaveFicticia#99").isValid)
    }

    @Test
    fun passwordLengthBoundariesAroundMinimumAreEnforced() {
        // BVA 3 valores sobre el mínimo (8): 7 inválido, 8 y 9 válidos.
        assertFalse(SecurityUtils.validatePasswordStrength("operador1", "Abc12$4").isValid)
        assertTrue(SecurityUtils.validatePasswordStrength("operador1", "Abc12$45").isValid)
        assertTrue(SecurityUtils.validatePasswordStrength("operador1", "Abc12$456").isValid)
    }

    @Test
    fun passwordLengthBoundariesAroundMaximumAreEnforced() {
        val base = "Aa1$"
        val atMax = base + "x".repeat(SecurityUtils.MAX_PASSWORD_LENGTH - base.length)
        assertEquals(SecurityUtils.MAX_PASSWORD_LENGTH, atMax.length)
        assertTrue(SecurityUtils.validatePasswordStrength("operador1", atMax).isValid)
        assertFalse(SecurityUtils.validatePasswordStrength("operador1", atMax + "x").isValid)
    }

    @Test
    fun whitespaceIsNotCountedAsSpecialCharacter() {
        // Antes pasaba: los espacios contaban como "carácter especial".
        val result = SecurityUtils.validatePasswordStrength("operador1", "Abcdefg1    ")
        assertFalse(result.hasSpecialChar)
        assertFalse(result.isValid)
    }

    @Test
    fun shortUsernamesDoNotCrashValidation() {
        assertFalse(SecurityUtils.validatePasswordStrength("", "").isValid)
        assertTrue(SecurityUtils.validatePasswordStrength("ab", "ClaveSegura#7").isValid)
    }

    // ---------- Validación de usernames (anti-inyección de ruta Firestore) ----------

    @Test
    fun acceptsWellFormedUsernamesAtBoundaries() {
        assertTrue(SecurityUtils.isValidUsername("abc"))
        assertTrue(SecurityUtils.isValidUsername("admin_maestro"))
        assertTrue(SecurityUtils.isValidUsername("juan.perez-2"))
        assertTrue(SecurityUtils.isValidUsername("a" + "b".repeat(31))) // 32 caracteres
    }

    @Test
    fun rejectsMaliciousOrMalformedUsernames() {
        listOf(
            "", "ab", "a/b", "../etc", "..", ".", "usuarios/admin", " abc", "abc ", "ab c",
            "a" + "b".repeat(32), // 33 caracteres
            ".oculto", "-guion", "_guion", "añb", "a\u0000b", "a\u202Eb", "abc\n", "<script>"
        ).forEach { candidate ->
            assertFalse("Debió rechazar '$candidate'", SecurityUtils.isValidUsername(candidate))
        }
    }

    // ---------- Hashing con sal (PBKDF2) ----------

    @Test
    fun sameCredentialProducesDifferentSaltAndHash() {
        val first = SecurityUtils.deriveCredential("PuertaSegura#9")
        val second = SecurityUtils.deriveCredential("PuertaSegura#9")
        assertNotEquals(first.salt, second.salt)
        assertNotEquals(first.hash, second.hash)
        assertEquals(SecurityUtils.PBKDF2_ITERATIONS, first.iterations)
        assertEquals(64, first.hash.length) // 256 bits en hex
    }

    @Test
    fun verifiesCorrectPasswordAndRejectsWrongOnes() {
        val cred = SecurityUtils.deriveCredential("PuertaSegura#9")
        val usuario = Usuario(
            username = "operador1",
            passwordHash = cred.hash,
            passwordSalt = cred.salt,
            hashIterations = cred.iterations
        )
        assertTrue(SecurityUtils.verifyPassword("PuertaSegura#9", usuario))
        assertFalse(SecurityUtils.verifyPassword("puertasegura#9", usuario))
        assertFalse(SecurityUtils.verifyPassword("PuertaSegura#9 ", usuario))
        assertFalse(SecurityUtils.verifyPassword("", usuario))
        assertFalse(SecurityUtils.verifyPassword("x".repeat(SecurityUtils.MAX_PASSWORD_LENGTH + 1), usuario))
        assertFalse(SecurityUtils.isLegacyHash(usuario))
    }

    @Test
    fun verifiesLegacySha512AccountsSoTheyCanBeMigrated() {
        val legacy = Usuario(
            username = "operador1",
            passwordHash = SecurityUtils.hashSha512("ClaveVieja#1")
        )
        assertTrue(SecurityUtils.isLegacyHash(legacy))
        assertTrue(SecurityUtils.verifyPassword("ClaveVieja#1", legacy))
        assertFalse(SecurityUtils.verifyPassword("ClaveVieja#2", legacy))
    }

    @Test
    fun tamperedHashOrSaltNeverVerifies() {
        val cred = SecurityUtils.deriveCredential("PuertaSegura#9")
        val tamperedHash = Usuario(
            username = "u", passwordHash = cred.hash.reversed(),
            passwordSalt = cred.salt, hashIterations = cred.iterations
        )
        val otherSalt = Usuario(
            username = "u", passwordHash = cred.hash,
            passwordSalt = SecurityUtils.generateSalt(), hashIterations = cred.iterations
        )
        assertFalse(SecurityUtils.verifyPassword("PuertaSegura#9", tamperedHash))
        assertFalse(SecurityUtils.verifyPassword("PuertaSegura#9", otherSalt))
    }

    @Test
    fun hashSha512MatchesKnownVector() {
        assertEquals(
            "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce" +
                "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
            SecurityUtils.hashSha512("")
        )
    }
}
