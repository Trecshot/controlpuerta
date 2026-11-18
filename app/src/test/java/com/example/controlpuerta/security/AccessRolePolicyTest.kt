package com.example.controlpuerta.security

import com.example.controlpuerta.model.RegistroAcceso
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessRolePolicyTest {

    @Test
    fun userOnlyClientAllowsOnlyExactUsuarioRole() {
        assertTrue(AccessRolePolicy.isRoleAllowedForClient("USUARIO", allowAdmin = false))

        assertFalse(AccessRolePolicy.isRoleAllowedForClient("ADMIN", allowAdmin = false))
        assertFalse(AccessRolePolicy.isRoleAllowedForClient("usuario", allowAdmin = false))
        assertFalse(AccessRolePolicy.isRoleAllowedForClient(" USUARIO ", allowAdmin = false))
        assertFalse(AccessRolePolicy.isRoleAllowedForClient("SUPERVISOR", allowAdmin = false))
        assertFalse(AccessRolePolicy.isRoleAllowedForClient("", allowAdmin = false))
    }

    @Test
    fun adminClientAllowsAdminAndUsuarioButRejectsUnknownRoles() {
        assertTrue(AccessRolePolicy.isRoleAllowedForClient("ADMIN", allowAdmin = true))
        assertTrue(AccessRolePolicy.isRoleAllowedForClient("USUARIO", allowAdmin = true))

        assertFalse(AccessRolePolicy.isRoleAllowedForClient("GUEST", allowAdmin = true))
        assertFalse(AccessRolePolicy.isRoleAllowedForClient("", allowAdmin = true))
    }

    @Test
    fun filterOwnAccessRecordsExcludesOtherUsersAndAdminActionsAndSortsDesc() {
        val sampleLogs = listOf(
            RegistroAcceso(
                id = "1",
                username = "operador1",
                rol = "USUARIO",
                accion = "APERTURA_PUERTA",
                sensorReed = "ABIERTO",
                timestamp = 1000L
            ),
            RegistroAcceso(
                id = "2",
                username = "operador2",
                rol = "USUARIO",
                accion = "APERTURA_PUERTA",
                sensorReed = "ABIERTO",
                timestamp = 5000L
            ),
            RegistroAcceso(
                id = "3",
                username = "operador1",
                rol = "ADMIN:admin_maestro",
                accion = "DESBLOQUEO_ADMIN",
                sensorReed = "CERRADO",
                timestamp = 4000L
            ),
            RegistroAcceso(
                id = "4",
                username = "operador1",
                rol = "USUARIO",
                accion = "CIERRE_PUERTA",
                sensorReed = "CERRADO",
                timestamp = 3000L
            ),
            RegistroAcceso(
                id = "5",
                username = "admin_maestro",
                rol = "ADMIN",
                accion = "APERTURA_PUERTA",
                sensorReed = "ABIERTO",
                timestamp = 6000L
            )
        )

        val result = AccessRolePolicy.filterOwnAccessRecords(sampleLogs, username = "operador1")

        assertEquals(listOf("4", "1"), result.map { it.id })
    }

    @Test
    fun filterOwnAccessRecordsRespectsBlankUsernameAndMaxItems() {
        val sampleLogs = (1..5).map { idx ->
            RegistroAcceso(
                id = idx.toString(),
                username = "operador1",
                rol = "USUARIO",
                accion = "APERTURA_PUERTA",
                sensorReed = "ABIERTO",
                timestamp = idx * 1000L
            )
        }

        assertTrue(AccessRolePolicy.filterOwnAccessRecords(sampleLogs, username = "").isEmpty())
        val capped = AccessRolePolicy.filterOwnAccessRecords(sampleLogs, username = "operador1", maxItems = 2)
        assertEquals(listOf("5", "4"), capped.map { it.id })
    }

    @Test
    fun formatsTechnicalCodesToNaturalUserLanguage() {
        assertEquals("Puerta abierta", AccessRolePolicy.formatUserFriendlyAction("APERTURA_PUERTA"))
        assertEquals("Puerta cerrada", AccessRolePolicy.formatUserFriendlyAction("CIERRE_PUERTA"))
        assertEquals(
            "Cuenta bloqueada por intentos fallidos",
            AccessRolePolicy.formatUserFriendlyAction("BLOQUEO_POR_INTENTOS")
        )
        assertEquals("OTRO_EVENTO", AccessRolePolicy.formatUserFriendlyAction("OTRO_EVENTO"))

        assertEquals("Abierta", AccessRolePolicy.formatUserFriendlyDoorState("ABIERTO"))
        assertEquals("Cerrada", AccessRolePolicy.formatUserFriendlyDoorState("CERRADO"))
    }
}
