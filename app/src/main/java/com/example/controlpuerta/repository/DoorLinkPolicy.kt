package com.example.controlpuerta.repository

/**
 * Protocolo de comandos Bluetooth y reglas puras de enlace operativo/Fail-Safe.
 */
object DoorLinkPolicy {
    const val BT_CMD_OPEN: String = "1"
    const val BT_CMD_CLOSE: String = "0"

    fun commandForDoorAction(abrir: Boolean): String = if (abrir) BT_CMD_OPEN else BT_CMD_CLOSE

    fun isLinkOperational(
        modoSimulacion: Boolean,
        isNetworkConnected: Boolean,
        btConectado: Boolean
    ): Boolean {
        return if (modoSimulacion) {
            isNetworkConnected
        } else {
            btConectado && isNetworkConnected
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
