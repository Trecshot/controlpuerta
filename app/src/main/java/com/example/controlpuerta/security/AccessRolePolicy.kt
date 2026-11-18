package com.example.controlpuerta.security

import com.example.controlpuerta.model.RegistroAcceso

/**
 * Políticas de autorización por rol, aislamiento de registros y traducción a lenguaje natural.
 */
object AccessRolePolicy {
    const val ROL_USUARIO: String = "USUARIO"
    const val ROL_ADMIN: String = "ADMIN"

    const val MSG_MOBILE_USER_ONLY: String =
        "Acceso denegado: Esta aplicación está restringida exclusivamente a cuentas de Usuario."

    const val MSG_UNRECOGNIZED_ROLE: String =
        "Acceso denegado: La cuenta no posee un rol autorizado para operar."

    /**
     * Valida de forma fail-closed si [rol] está habilitado para operar en la compilación actual.
     */
    fun isRoleAllowedForClient(rol: String, allowAdmin: Boolean): Boolean {
        return when (rol) {
            ROL_USUARIO -> true
            ROL_ADMIN -> allowAdmin
            else -> false
        }
    }

    /**
     * Filtra la bitácora para que el operador móvil solo vea sus propios registros de acceso,
     * excluyendo acciones internas de administración y eventos de otros usuarios.
     */
    fun filterOwnAccessRecords(
        records: List<RegistroAcceso>,
        username: String,
        maxItems: Int = 30
    ): List<RegistroAcceso> {
        if (username.isBlank() || maxItems <= 0) return emptyList()
        return records
            .asSequence()
            .filter { it.username == username && !it.rol.startsWith("ADMIN:") }
            .sortedByDescending { it.timestamp }
            .take(maxItems)
            .toList()
    }

    /**
     * Traduce códigos de evento internos a lenguaje natural para la interfaz del usuario final.
     */
    fun formatUserFriendlyAction(accion: String): String {
        return when (accion) {
            "APERTURA_PUERTA" -> "Puerta abierta"
            "CIERRE_PUERTA" -> "Puerta cerrada"
            "BLOQUEO_POR_INTENTOS" -> "Cuenta bloqueada por intentos fallidos"
            else -> accion
        }
    }

    /**
     * Traduce el estado crudo del sensor magnético a lenguaje cotidiano.
     */
    fun formatUserFriendlyDoorState(sensorReed: String): String {
        return if (sensorReed == "ABIERTO") "Abierta" else "Cerrada"
    }
}
