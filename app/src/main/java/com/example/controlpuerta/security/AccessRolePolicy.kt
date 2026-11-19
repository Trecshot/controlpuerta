package com.example.controlpuerta.security

import com.example.controlpuerta.model.RegistroAcceso

/**
 * Políticas de autorización por rol, aislamiento de registros y traducción a lenguaje natural.
 */
object AccessRolePolicy {
    const val ROL_USUARIO: String = "USUARIO"
    const val ROL_ADMIN: String = "ADMIN"

    const val SENSOR_ABIERTO: String = "ABIERTO"
    const val SENSOR_CERRADO: String = "CERRADO"
    const val SENSOR_DESCONOCIDO: String = "DESCONOCIDO"

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

    /** Valida que [rol] pertenezca al catálogo cerrado de roles asignables. */
    fun isAssignableRole(rol: String): Boolean = rol == ROL_USUARIO || rol == ROL_ADMIN

    /** Mensaje de denegación coherente con el rol rechazado (fuente única de verdad). */
    fun deniedMessageForRole(rol: String): String =
        if (rol == ROL_ADMIN) MSG_MOBILE_USER_ONLY else MSG_UNRECOGNIZED_ROLE

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

    /** Clave estable y única para listas; evita crashes de LazyColumn por claves duplicadas. */
    fun recordKey(record: RegistroAcceso): String =
        record.id.ifEmpty { "${record.timestamp}_${record.username}_${record.accion}" }

    fun distinctRecords(records: List<RegistroAcceso>): List<RegistroAcceso> =
        records.distinctBy { recordKey(it) }

    /**
     * Traduce códigos de evento internos a lenguaje natural para la interfaz del usuario final.
     */
    fun formatUserFriendlyAction(accion: String): String {
        return when (accion) {
            "APERTURA_PUERTA" -> "Puerta abierta"
            "CIERRE_PUERTA" -> "Puerta cerrada"
            "CIERRE_FAIL_SAFE" -> "Cerrada y bloqueada por seguridad (Fail-Safe)"
            "BLOQUEO_POR_INTENTOS" -> "Cuenta bloqueada por intentos fallidos"
            "ENLACE_PERDIDO" -> "Cerrada y bloqueada por pérdida de enlace"
            else -> accion
        }
    }

    /**
     * Traduce el estado crudo del sensor magnético a lenguaje cotidiano.
     */
    fun formatUserFriendlyDoorState(sensorReed: String): String {
        return when (sensorReed) {
            SENSOR_ABIERTO -> "Abierta"
            SENSOR_DESCONOCIDO -> "Sin verificar"
            else -> "Cerrada"
        }
    }
}
