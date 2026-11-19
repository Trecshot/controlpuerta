package com.example.controlpuerta.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorLinkPolicyTest {

    private fun canExecute(
        sim: Boolean,
        net: Boolean,
        bt: Boolean,
        loaded: Boolean = true,
        pending: Boolean = false,
        open: Boolean,
        requestedOpen: Boolean
    ) = DoorLinkPolicy.canExecuteDoorAction(sim, net, bt, loaded, pending, open, requestedOpen)

    @Test
    fun mapsDoorActionsToExpectedBluetoothProtocolCommands() {
        assertEquals("1", DoorLinkPolicy.commandForDoorAction(abrir = true))
        assertEquals("0", DoorLinkPolicy.commandForDoorAction(abrir = false))
    }

    @Test
    fun simulationModeDependsOnlyOnNetworkLink() {
        assertTrue(DoorLinkPolicy.isLinkOperational(modoSimulacion = true, isNetworkConnected = true, btConectado = false))
        assertFalse(DoorLinkPolicy.isLinkOperational(modoSimulacion = true, isNetworkConnected = false, btConectado = true))
    }

    @Test
    fun hardwareModeDependsOnBluetoothAndToleratesNoInternet() {
        // Sin Bluetooth nunca se opera, haya red o no.
        assertFalse(DoorLinkPolicy.isLinkOperational(modoSimulacion = false, isNetworkConnected = true, btConectado = false))
        assertFalse(DoorLinkPolicy.isLinkOperational(modoSimulacion = false, isNetworkConnected = false, btConectado = false))
        // Con Bluetooth se opera aunque no haya internet (cochera/subsuelo); la bitácora se encola.
        assertTrue(DoorLinkPolicy.isLinkOperational(modoSimulacion = false, isNetworkConnected = false, btConectado = true))
        assertTrue(DoorLinkPolicy.isLinkOperational(modoSimulacion = false, isNetworkConnected = true, btConectado = true))
    }

    @Test
    fun canExecuteDoorActionRespectsStatePendingAndFailSafe() {
        assertTrue(canExecute(sim = true, net = true, bt = false, open = false, requestedOpen = true))
        assertFalse(canExecute(sim = false, net = true, bt = false, open = false, requestedOpen = true))
        assertFalse(canExecute(sim = true, net = true, bt = false, loaded = false, open = false, requestedOpen = true))
        assertFalse(canExecute(sim = true, net = true, bt = false, pending = true, open = false, requestedOpen = true))
        assertTrue(canExecute(sim = false, net = true, bt = true, open = true, requestedOpen = false))
    }

    @Test
    fun redundantActionsAreRejected() {
        // Abrir una puerta ya abierta / cerrar una ya cerrada no debe enviar comandos.
        assertFalse(canExecute(sim = false, net = true, bt = true, open = true, requestedOpen = true))
        assertFalse(canExecute(sim = false, net = true, bt = true, open = false, requestedOpen = false))
        assertFalse(canExecute(sim = true, net = true, bt = false, open = true, requestedOpen = true))
        assertFalse(canExecute(sim = true, net = true, bt = false, open = false, requestedOpen = false))
    }

    @Test
    fun hardwareOperatesOfflineButSimulationDoesNot() {
        assertTrue(canExecute(sim = false, net = false, bt = true, open = false, requestedOpen = true))
        assertFalse(canExecute(sim = true, net = false, bt = true, open = false, requestedOpen = true))
    }

    @Test
    fun pendingCommandBlocksBothDirectionsEvenWithHealthyLink() {
        assertFalse(canExecute(sim = false, net = true, bt = true, pending = true, open = false, requestedOpen = true))
        assertFalse(canExecute(sim = false, net = true, bt = true, pending = true, open = true, requestedOpen = false))
    }

    @Test
    fun parsesArduinoSerialTelemetryFramesForOpenAndClosedStates() {
        listOf("1", "ABIERTO", "abierta", " open ", "REED:1", "REED:ABIERTO", "REED=ABIERTO", "ACK:1", "OK:1", "STATE:OPEN").forEach { frame ->
            assertEquals("Falló al parsear '$frame'", "ABIERTO", DoorLinkPolicy.parseIncomingTelemetry(frame))
        }
        listOf("0", "CERRADO", "cerrada", " closed ", "CLOSE", "REED:0", "REED:CERRADO", "REED=CERRADO", "ACK:0", "OK:0", "STATE:CLOSED").forEach { frame ->
            assertEquals("Falló al parsear '$frame'", "CERRADO", DoorLinkPolicy.parseIncomingTelemetry(frame))
        }
    }

    @Test
    fun ignoresUnknownOrEmptySerialNoise() {
        listOf("", "   ", "PING", "BOOTING...", "TEMP:25", "2", "REED:ERROR").forEach { noise ->
            org.junit.Assert.assertNull("Debió ignorar '$noise'", DoorLinkPolicy.parseIncomingTelemetry(noise))
        }
    }

    @Test
    fun resolveReedStatePrefersArduinoTelemetryAndFallsBackToCommandConfirmation() {
        // Si el Arduino reporta telemetría física (incluso si contradice el comando por atasco físico), prevalece el sensor real.
        assertEquals("CERRADO", DoorLinkPolicy.resolveReedState(abrir = true, telemetryReed = "CERRADO"))
        assertEquals("ABIERTO", DoorLinkPolicy.resolveReedState(abrir = false, telemetryReed = "ABIERTO"))
        assertEquals("DESCONOCIDO", DoorLinkPolicy.resolveReedState(abrir = true, telemetryReed = "DESCONOCIDO"))

        // Si el Arduino solo recibe comandos (telemetryReed == null), usa confirmación por envío.
        assertEquals("ABIERTO", DoorLinkPolicy.resolveReedState(abrir = true, telemetryReed = null))
        assertEquals("CERRADO", DoorLinkPolicy.resolveReedState(abrir = false, telemetryReed = null))
        assertEquals("ABIERTO", DoorLinkPolicy.resolveReedState(abrir = true, telemetryReed = "RUIDO"))
    }
}
