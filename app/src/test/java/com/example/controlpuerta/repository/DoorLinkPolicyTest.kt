package com.example.controlpuerta.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorLinkPolicyTest {

    @Test
    fun mapsDoorActionsToExpectedBluetoothProtocolCommands() {
        assertEquals("1", DoorLinkPolicy.commandForDoorAction(abrir = true))
        assertEquals("0", DoorLinkPolicy.commandForDoorAction(abrir = false))
    }

    @Test
    fun simulationModeDependsOnlyOnNetworkLink() {
        assertTrue(
            DoorLinkPolicy.isLinkOperational(
                modoSimulacion = true,
                isNetworkConnected = true,
                btConectado = false
            )
        )
        assertFalse(
            DoorLinkPolicy.isLinkOperational(
                modoSimulacion = true,
                isNetworkConnected = false,
                btConectado = true
            )
        )
    }

    @Test
    fun hardwareModeRequiresBothBluetoothAndNetworkLink() {
        assertFalse(
            DoorLinkPolicy.isLinkOperational(
                modoSimulacion = false,
                isNetworkConnected = true,
                btConectado = false
            )
        )
        assertFalse(
            DoorLinkPolicy.isLinkOperational(
                modoSimulacion = false,
                isNetworkConnected = false,
                btConectado = true
            )
        )
        assertTrue(
            DoorLinkPolicy.isLinkOperational(
                modoSimulacion = false,
                isNetworkConnected = true,
                btConectado = true
            )
        )
    }

    @Test
    fun canExecuteDoorActionRespectsStatePendingAndFailSafe() {
        assertTrue(
            DoorLinkPolicy.canExecuteDoorAction(
                modoSimulacion = true,
                isNetworkConnected = true,
                btConectado = false,
                hasLoadedDoorState = true,
                isCommandPending = false,
                isDoorCurrentlyOpen = false,
                requestedOpen = true
            )
        )
        assertFalse(
            DoorLinkPolicy.canExecuteDoorAction(
                modoSimulacion = false,
                isNetworkConnected = true,
                btConectado = false,
                hasLoadedDoorState = true,
                isCommandPending = false,
                isDoorCurrentlyOpen = false,
                requestedOpen = true
            )
        )
        assertFalse(
            DoorLinkPolicy.canExecuteDoorAction(
                modoSimulacion = true,
                isNetworkConnected = true,
                btConectado = false,
                hasLoadedDoorState = false,
                isCommandPending = false,
                isDoorCurrentlyOpen = false,
                requestedOpen = true
            )
        )
        assertFalse(
            DoorLinkPolicy.canExecuteDoorAction(
                modoSimulacion = true,
                isNetworkConnected = true,
                btConectado = false,
                hasLoadedDoorState = true,
                isCommandPending = true,
                isDoorCurrentlyOpen = false,
                requestedOpen = true
            )
        )
        assertTrue(
            DoorLinkPolicy.canExecuteDoorAction(
                modoSimulacion = false,
                isNetworkConnected = true,
                btConectado = true,
                hasLoadedDoorState = true,
                isCommandPending = false,
                isDoorCurrentlyOpen = true,
                requestedOpen = false
            )
        )
    }
}
