package com.example.controlpuerta.repository

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gestiona la conexión Bluetooth clásico (SPP / RFCOMM) hacia módulos HC-05 / HC-06.
 */
class BluetoothController(context: Context) {

    private val appContext: Context = context.applicationContext
    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val socketLock = Any()
    private var connectJob: Job? = null
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    private val _conectado = MutableStateFlow(false)
    val conectado: StateFlow<Boolean> = _conectado.asStateFlow()

    private val _conectando = MutableStateFlow(false)
    val conectando: StateFlow<Boolean> = _conectando.asStateFlow()

    private val _dispositivoNombre = MutableStateFlow("Sin vincular")
    val dispositivoNombre: StateFlow<String> = _dispositivoNombre.asStateFlow()

    companion object {
        private const val TAG = "ControlPuertaIoT"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    fun isBluetoothSupported(): Boolean = adapter != null

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val connectGranted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            val scanGranted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            connectGranted && scanGranted
        } else {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun obtenerDispositivosVinculados(): List<BluetoothDevice> {
        val btAdapter = adapter
        if (btAdapter == null || !btAdapter.isEnabled || !hasBluetoothPermission()) {
            return emptyList()
        }
        return try {
            btAdapter.bondedDevices?.toList().orEmpty()
        } catch (e: SecurityException) {
            Log.e(TAG, "BT_PERM_ERROR: Permiso insuficiente al listar dispositivos vinculados: ${e.message}", e)
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    fun conectarDispositivo(
        device: BluetoothDevice,
        onResultado: (Boolean, String) -> Unit
    ) {
        if (!hasBluetoothPermission()) {
            onResultado(false, "Permiso Bluetooth requerido para conectar con la cerradura.")
            return
        }
        val btAdapter = adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            onResultado(false, "Encienda el Bluetooth del teléfono antes de conectar.")
            return
        }

        connectJob?.cancel()
        connectJob = scope.launch {
            _conectando.value = true
            try {
                desconectar()
                val tmpSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                try {
                    btAdapter.cancelDiscovery()
                } catch (e: SecurityException) {
                    Log.w(TAG, "BT_DISCOVERY_WARN: No se pudo cancelar descubrimiento: ${e.message}")
                }
                tmpSocket.connect()

                val tmpOutput = tmpSocket.outputStream
                synchronized(socketLock) {
                    socket = tmpSocket
                    outputStream = tmpOutput
                }

                val nombre = try {
                    device.name ?: device.address
                } catch (e: SecurityException) {
                    Log.w(TAG, "BT_NAME_WARN: Sin permiso para leer nombre: ${e.message}")
                    device.address
                }

                _conectado.value = true
                _dispositivoNombre.value = nombre
                Log.i(TAG, "BT_CONNECTED: Enlace RFCOMM establecido con $nombre (${device.address}).")
                withContext(Dispatchers.Main) {
                    onResultado(true, "Conectado a $nombre")
                }
            } catch (e: IOException) {
                desconectar()
                _dispositivoNombre.value = "Error de enlace"
                Log.e(TAG, "BT_FAIL: No se pudo conectar al módulo HC-05: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onResultado(false, "No se pudo conectar con la cerradura. Verifique que esté encendida.")
                }
            } catch (e: SecurityException) {
                desconectar()
                Log.e(TAG, "BT_PERM_ERROR: Permiso denegado al conectar por RFCOMM: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onResultado(false, "Permiso Bluetooth denegado al intentar conectar.")
                }
            } finally {
                _conectando.value = false
            }
        }
    }

    fun enviarComando(
        comando: String,
        onResultado: (exito: Boolean, conexionPerdida: Boolean) -> Unit
    ) {
        scope.launch {
            val activeSocket: BluetoothSocket?
            val activeOutput: OutputStream?
            synchronized(socketLock) {
                activeSocket = socket
                activeOutput = outputStream
            }

            if (activeSocket?.isConnected != true || activeOutput == null) {
                Log.w(TAG, "BT_TX_SKIP: Socket Bluetooth no conectado al enviar '$comando'.")
                desconectar()
                withContext(Dispatchers.Main) {
                    onResultado(false, true)
                }
                return@launch
            }

            try {
                activeOutput.write(comando.toByteArray(Charsets.UTF_8))
                activeOutput.flush()
                Log.i(TAG, "BT_TX: Enviado '$comando' al Arduino.")
                withContext(Dispatchers.Main) {
                    onResultado(true, false)
                }
            } catch (e: IOException) {
                Log.e(TAG, "FAIL_SAFE_BT: Pérdida física de señal Bluetooth: ${e.message}", e)
                desconectar()
                withContext(Dispatchers.Main) {
                    onResultado(false, true)
                }
            }
        }
    }

    fun desconectar() {
        val oldOutput: OutputStream?
        val oldSocket: BluetoothSocket?
        synchronized(socketLock) {
            oldOutput = outputStream
            oldSocket = socket
            outputStream = null
            socket = null
        }

        try {
            oldOutput?.close()
        } catch (e: IOException) {
            Log.w(TAG, "BT_CLOSE_WARN: Error al cerrar OutputStream: ${e.message}")
        }
        try {
            oldSocket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "BT_CLOSE_WARN: Error al cerrar BluetoothSocket: ${e.message}")
        }

        _conectado.value = false
        _conectando.value = false
        _dispositivoNombre.value = "Desconectado"
    }

    fun release() {
        connectJob?.cancel()
        desconectar()
        scope.cancel()
    }
}
