package com.example.controlpuerta.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityUtilsTest {

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
}
