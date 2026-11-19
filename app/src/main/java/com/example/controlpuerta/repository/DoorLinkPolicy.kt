package com.example.controlpuerta.repository

import com.example.controlpuerta.security.AccessRolePolicy

/**
 * Protocolo de comandos Bluetooth, telemetría Serial del Arduino y reglas puras de enlace/Fail-Safe.
 */
object DoorLinkPolicy {
    const val BT_CMD_OPEN: String = "1"
    const val BT_CMD_CLOSE: String = "0"

    fun commandForDoorAction(abrir: Boolean): String = if (abrir) BT_CMD_OPEN else BT_CMD_CLOSE

    /**
     * Interpreta tramas de telemetría enviadas por el Arduino (HC-05) vía UART/Serial.
     * Si el firmware del Arduino no envía respuesta, devuelve `null` y la app usa
     * [resolveReedState] con confirmación por envío.
     */
    fun parseIncomingTelemetry(rawFrame: String): String? {
        val token = rawFrame.trim().uppercase()
        if (token.isEmpty()) return null
        return when (token) {
            "1",
            "ABIERTO",
            "ABIERTA",
            "OPEN",
            "ACK:1",
            "OK:1",
            "REED:1",
            "REED:ABIERTO",
            "REED=ABIERTO",
            "STATE:OPEN" -> AccessRolePolicy.SENSOR_ABIERTO

            "0",
            "CERRADO",
            "CERRADA",
            "CLOSED",
            "CLOSE",
            "ACK:0",
            "OK:0",
            "REED:0",
            "REED:CERRADO",
            "REED=CERRADO",
            "STATE:CLOSED" -> AccessRolePolicy.SENSOR_CERRADO

            else -> null
        }
    }

    /**
     * Determina el estado del sensor magnético Reed a registrar: prioriza la telemetría física
     * reportada por el Arduino si está disponible; si el Arduino solo recibe comandos, usa la
     * confirmación por envío.
     */
    fun resolveReedState(abrir: Boolean, telemetryReed: String?): String {
        return when (telemetryReed) {
            AccessRolePolicy.SENSOR_ABIERTO,
            AccessRolePolicy.SENSOR_CERRADO,
            AccessRolePolicy.SENSOR_DESCONOCIDO -> telemetryReed
            else -> if (abrir) AccessRolePolicy.SENSOR_ABIERTO else AccessRolePolicy.SENSOR_CERRADO
        }
    }

    /**
     * Modo simulación: depende de la red (escribe solo en Firestore).
     * Modo hardware: basta el enlace Bluetooth con la cerradura; la bitácora se encola de forma
     * duradera en la caché de Firestore y se sincroniza al recuperar internet, de modo que una
     * zona sin cobertura (cochera, subsuelo) no impide operar la puerta.
     */
    fun isLinkOperational(
        modoSimulacion: Boolean,
        isNetworkConnected: Boolean,
        btConectado: Boolean
    ): Boolean {
        return if (modoSimulacion) {
            isNetworkConnected
        } else {
            btConectado
        }
    }

    fun canExecuteDoorAction(
        modoSimulacion: Boolean,
        isNetworkConnected: Boolean,
        btConectado: Boolean,
        hasLoadedDoorState: Boolean,
        isCommandPending: Boolean,
        isDoorCurrentlyOpen: Boolean,
        requestedOpen: Boolean
    ): Boolean {
        if (!hasLoadedDoorState || isCommandPending) return false
        if (!isLinkOperational(modoSimulacion, isNetworkConnected, btConectado)) return false
        return if (requestedOpen) !isDoorCurrentlyOpen else isDoorCurrentlyOpen
    }
}
