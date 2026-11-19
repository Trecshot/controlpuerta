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
        assertEquals("Sin verificar", AccessRolePolicy.formatUserFriendlyDoorState("DESCONOCIDO"))
        assertEquals(
            "Cerrada y bloqueada por seguridad (Fail-Safe)",
            AccessRolePolicy.formatUserFriendlyAction("CIERRE_FAIL_SAFE")
        )
        assertEquals(
            "Cerrada y bloqueada por pérdida de enlace",
            AccessRolePolicy.formatUserFriendlyAction("ENLACE_PERDIDO")
        )
    }

    @Test
    fun deniedMessageMatchesRejectedRole() {
        assertEquals(AccessRolePolicy.MSG_MOBILE_USER_ONLY, AccessRolePolicy.deniedMessageForRole("ADMIN"))
        assertEquals(AccessRolePolicy.MSG_UNRECOGNIZED_ROLE, AccessRolePolicy.deniedMessageForRole("GUEST"))
        assertEquals(AccessRolePolicy.MSG_UNRECOGNIZED_ROLE, AccessRolePolicy.deniedMessageForRole(""))
    }

    @Test
    fun onlyKnownRolesAreAssignable() {
        assertTrue(AccessRolePolicy.isAssignableRole("USUARIO"))
        assertTrue(AccessRolePolicy.isAssignableRole("ADMIN"))
        assertFalse(AccessRolePolicy.isAssignableRole("admin"))
        assertFalse(AccessRolePolicy.isAssignableRole("ADMIN:root"))
        assertFalse(AccessRolePolicy.isAssignableRole(""))
    }

    @Test
    fun filterOwnAccessRecordsHandlesNonPositiveMaxItemsAndAdminEvents() {
        val logs = listOf(
            RegistroAcceso("1", "operador1", "USUARIO", "APERTURA_PUERTA", "ABIERTO", 1L),
            RegistroAcceso("2", "operador1", "ADMIN:root", "BLOQUEO_MANUAL_ADMIN", "CERRADO", 2L)
        )
        assertTrue(AccessRolePolicy.filterOwnAccessRecords(logs, "operador1", maxItems = 0).isEmpty())
        assertTrue(AccessRolePolicy.filterOwnAccessRecords(logs, "operador1", maxItems = -3).isEmpty())
        assertEquals(listOf("1"), AccessRolePolicy.filterOwnAccessRecords(logs, "operador1").map { it.id })
    }

    @Test
    fun distinctRecordsPreventsDuplicateLazyListKeys() {
        val sameWithoutId = RegistroAcceso("", "operador1", "USUARIO", "APERTURA_PUERTA", "ABIERTO", 10L)
        val result = AccessRolePolicy.distinctRecords(listOf(sameWithoutId, sameWithoutId.copy()))
        assertEquals(1, result.size)
        assertEquals(
            result.map { AccessRolePolicy.recordKey(it) }.toSet().size,
            result.size
        )
    }
}
