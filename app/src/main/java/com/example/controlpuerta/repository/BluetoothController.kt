package com.example.controlpuerta.repository

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gestiona la conexión Bluetooth clásico (SPP / RFCOMM) hacia módulos HC-05 / HC-06.
 *
 * Garantías:
 * - Timeout de conexión y de escritura (se cierra el socket para desbloquear la E/S bloqueante).
 * - Detección activa de pérdida de enlace (lectura del socket + broadcast ACL_DISCONNECTED).
 * - Una conexión obsoleta (cambio rápido de cerradura) nunca pisa el estado de la vigente.
 * - Comandos serializados: no se intercalan escrituras concurrentes.
 */
class BluetoothController(context: Context) {

    private val appContext: Context = context.applicationContext
    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val socketLock = Any()
    private val commandMutex = Mutex()
    private val generation = AtomicInteger(0)

    private var connectJob: Job? = null
    private var readerJob: Job? = null
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    @Volatile private var pendingSocket: BluetoothSocket? = null
    private var disconnectReceiver: BroadcastReceiver? = null

    private val _conectado = MutableStateFlow(false)
    val conectado: StateFlow<Boolean> = _conectado.asStateFlow()

    private val _conectando = MutableStateFlow(false)
    val conectando: StateFlow<Boolean> = _conectando.asStateFlow()

    private val _dispositivoNombre = MutableStateFlow("Sin vincular")
    val dispositivoNombre: StateFlow<String> = _dispositivoNombre.asStateFlow()

    private val _conexionPerdida = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emite exactamente una vez cuando un enlace ya establecido se corta de forma no solicitada. */
    val conexionPerdida: SharedFlow<Unit> = _conexionPerdida.asSharedFlow()

    private val _telemetriaReed = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Emite lecturas válidas del sensor magnético Reed (`ABIERTO` / `CERRADO`) si el Arduino responde por Serial. */
    val telemetriaReed: SharedFlow<String> = _telemetriaReed.asSharedFlow()

    companion object {
        private const val TAG = "ControlPuertaIoT"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val WRITE_TIMEOUT_MS = 3_000L
        private const val TELEMETRY_ACK_WAIT_MS = 350L
        private const val READ_BUFFER_BYTES = 64
        private const val MAX_LINE_ACCUMULATOR = 256
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

        val myGeneration = generation.incrementAndGet()
        // Cerrar el socket pendiente desbloquea el connect() bloqueante de un intento anterior.
        closeQuietly(pendingSocket)
        connectJob?.cancel()

        connectJob = scope.launch {
            _conectando.value = true
            val timedOut = AtomicBoolean(false)
            try {
                cerrarEnlaceActivo(notifyState = false)
                val tmpSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                pendingSocket = tmpSocket
                try {
                    btAdapter.cancelDiscovery()
                } catch (e: SecurityException) {
                    Log.w(TAG, "BT_DISCOVERY_WARN: No se pudo cancelar descubrimiento: ${e.message}")
                }

                val watchdog = launch {
                    delay(CONNECT_TIMEOUT_MS)
                    timedOut.set(true)
                    closeQuietly(tmpSocket)
                }
                try {
                    tmpSocket.connect()
                } finally {
                    watchdog.cancel()
                }

                if (generation.get() != myGeneration) {
                    // Un intento más reciente reemplazó a este; descartar sin tocar el estado vigente.
                    closeQuietly(tmpSocket)
                    return@launch
                }

                val tmpOutput = tmpSocket.outputStream
                synchronized(socketLock) {
                    socket = tmpSocket
                    outputStream = tmpOutput
                }
                pendingSocket = null

                val nombre = try {
                    device.name ?: device.address
                } catch (e: SecurityException) {
                    Log.w(TAG, "BT_NAME_WARN: Sin permiso para leer nombre: ${e.message}")
                    device.address
                }

                registrarReceptorDesconexion(device.address)
                iniciarLectorEnlace(tmpSocket)

                _conectado.value = true
                _dispositivoNombre.value = nombre
                Log.i(TAG, "BT_CONNECTED: Enlace RFCOMM establecido con $nombre (${device.address}).")
                withContext(Dispatchers.Main) {
                    onResultado(true, "Conectado a $nombre")
                }
            } catch (e: IOException) {
                if (generation.get() == myGeneration) {
                    cerrarEnlaceActivo(notifyState = true)
                    _dispositivoNombre.value = "Error de enlace"
                    val reason = if (timedOut.get()) {
                        "Tiempo de espera agotado. Acerque el teléfono y verifique que la cerradura esté encendida."
                    } else {
                        "No se pudo conectar con la cerradura. Verifique que esté encendida."
                    }
                    Log.e(TAG, "BT_FAIL: No se pudo conectar al módulo HC-05 (timeout=${timedOut.get()}): ${e.message}", e)
                    withContext(Dispatchers.Main) { onResultado(false, reason) }
                }
            } catch (e: SecurityException) {
                if (generation.get() == myGeneration) {
                    cerrarEnlaceActivo(notifyState = true)
                    Log.e(TAG, "BT_PERM_ERROR: Permiso denegado al conectar por RFCOMM: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        onResultado(false, "Permiso Bluetooth denegado al intentar conectar.")
                    }
                }
            } finally {
                if (generation.get() == myGeneration) {
                    _conectando.value = false
                    pendingSocket = null
                }
            }
        }
    }

    /**
     * Lee el socket de forma continua:
     * 1. Si el Arduino transmite tramas UART (ej. `ABIERTO`/`CERRADO` o `1`/`0`), las interpreta y emite en [telemetriaReed].
     * 2. Un fin de flujo o una excepción de E/S revela de inmediato que la cerradura se apagó o salió de alcance.
     */
    private fun iniciarLectorEnlace(activeSocket: BluetoothSocket) {
        readerJob?.cancel()
        readerJob = scope.launch {
            val buffer = ByteArray(READ_BUFFER_BYTES)
            val lineBuilder = StringBuilder()
            try {
                val input = activeSocket.inputStream
                while (isActive) {
                    val readCount = input.read(buffer)
                    if (readCount < 0) break
                    if (readCount == 0) continue

                    val chunk = String(buffer, 0, readCount, Charsets.UTF_8)
                    for (ch in chunk) {
                        if (ch == '\n' || ch == '\r') {
                            if (lineBuilder.isNotEmpty()) {
                                procesarTramaRecibida(lineBuilder.toString())
                                lineBuilder.setLength(0)
                            }
                        } else if (lineBuilder.length < MAX_LINE_ACCUMULATOR) {
                            lineBuilder.append(ch)
                        } else {
                            lineBuilder.setLength(0)
                        }
                    }
                    // Soporte para sketches de Arduino que envían un token sin salto de línea (ej. Serial.print("1")).
                    if (lineBuilder.isNotEmpty()) {
                        val directParsed = DoorLinkPolicy.parseIncomingTelemetry(lineBuilder.toString())
                        if (directParsed != null) {
                            Log.i(TAG, "BT_RX_TELEMETRY: Sensor Reed reportado por Arduino -> $directParsed")
                            _telemetriaReed.tryEmit(directParsed)
                            lineBuilder.setLength(0)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.i(TAG, "BT_READER_END: Lectura finalizada: ${e.message}")
            }
            onEnlacePerdido(activeSocket)
        }
    }

    private fun procesarTramaRecibida(rawLine: String) {
        val parsed = DoorLinkPolicy.parseIncomingTelemetry(rawLine) ?: return
        Log.i(TAG, "BT_RX_TELEMETRY: Sensor Reed reportado por Arduino -> $parsed")
        _telemetriaReed.tryEmit(parsed)
    }

    @SuppressLint("MissingPermission")
    private fun registrarReceptorDesconexion(address: String) {
        desregistrarReceptorDesconexion()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
                val device = IntentCompat.getParcelableExtra(
                    intent,
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java
                ) ?: return
                val sameDevice = try {
                    device.address == address
                } catch (e: SecurityException) {
                    false
                }
                if (!sameDevice) return
                val current = synchronized(socketLock) { socket } ?: return
                scope.launch { onEnlacePerdido(current) }
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        disconnectReceiver = receiver
    }

    private fun desregistrarReceptorDesconexion() {
        val receiver = disconnectReceiver ?: return
        disconnectReceiver = null
        try {
            appContext.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "BT_RECEIVER_WARN: Receptor ya desregistrado: ${e.message}")
        }
    }

    /**
     * Cierra el enlace solo si [lost] sigue siendo el socket vigente, y emite un único evento.
     */
    private fun onEnlacePerdido(lost: BluetoothSocket) {
        val wasActive = synchronized(socketLock) { socket === lost }
        if (!wasActive) return
        Log.w(TAG, "FAIL_SAFE_BT: Enlace Bluetooth perdido de forma no solicitada.")
        desconectar()
        _conexionPerdida.tryEmit(Unit)
    }

    fun enviarComando(
        comando: String,
        onResultado: (exito: Boolean, conexionPerdida: Boolean, sensorReedReportado: String?) -> Unit
    ) {
        scope.launch {
            commandMutex.withLock {
                val activeSocket: BluetoothSocket?
                val activeOutput: OutputStream?
                synchronized(socketLock) {
                    activeSocket = socket
                    activeOutput = outputStream
                }

                if (activeSocket == null || activeOutput == null || !activeSocket.isConnected) {
                    Log.w(TAG, "BT_TX_SKIP: Socket Bluetooth no conectado al enviar '$comando'.")
                    if (activeSocket != null) onEnlacePerdido(activeSocket) else desconectar()
                    withContext(Dispatchers.Main) { onResultado(false, true, null) }
                    return@withLock
                }

                val watchdog = launch {
                    delay(WRITE_TIMEOUT_MS)
                    Log.e(TAG, "BT_TX_TIMEOUT: La escritura excedió $WRITE_TIMEOUT_MS ms; cerrando enlace.")
                    closeQuietly(activeSocket)
                }
                try {
                    activeOutput.write(comando.toByteArray(Charsets.UTF_8))
                    activeOutput.flush()
                    watchdog.cancel()
                    Log.i(TAG, "BT_TX: Enviado '$comando' al Arduino.")
                    // Espera breve por telemetría del Arduino; si el sketch no envía respuesta, continúa con null.
                    val reedFromArduino = withTimeoutOrNull(TELEMETRY_ACK_WAIT_MS) {
                        telemetriaReed.first()
                    }
                    withContext(Dispatchers.Main) { onResultado(true, false, reedFromArduino) }
                } catch (e: IOException) {
                    watchdog.cancel()
                    Log.e(TAG, "FAIL_SAFE_BT: Pérdida física de señal Bluetooth: ${e.message}", e)
                    onEnlacePerdido(activeSocket)
                    withContext(Dispatchers.Main) { onResultado(false, true, null) }
                }
            }
        }
    }

    /** Desconexión solicitada por el usuario o por limpieza interna (no emite pérdida de enlace). */
    fun desconectar() {
        generation.incrementAndGet()
        closeQuietly(pendingSocket)
        pendingSocket = null
        connectJob?.cancel()
        cerrarEnlaceActivo(notifyState = true)
    }

    private fun cerrarEnlaceActivo(notifyState: Boolean) {
        val oldOutput: OutputStream?
        val oldSocket: BluetoothSocket?
        synchronized(socketLock) {
            oldOutput = outputStream
            oldSocket = socket
            outputStream = null
            socket = null
        }
        readerJob?.cancel()
        readerJob = null
        desregistrarReceptorDesconexion()

        try {
            oldOutput?.close()
        } catch (e: IOException) {
            Log.w(TAG, "BT_CLOSE_WARN: Error al cerrar OutputStream: ${e.message}")
        }
        closeQuietly(oldSocket)

        if (notifyState) {
            _conectado.value = false
            _conectando.value = false
            _dispositivoNombre.value = "Desconectado"
        }
    }

    private fun closeQuietly(target: BluetoothSocket?) {
        try {
            target?.close()
        } catch (e: IOException) {
            Log.w(TAG, "BT_CLOSE_WARN: Error al cerrar BluetoothSocket: ${e.message}")
        }
    }

    fun release() {
        generation.incrementAndGet()
        closeQuietly(pendingSocket)
        pendingSocket = null
        connectJob?.cancel()
        cerrarEnlaceActivo(notifyState = true)
        scope.cancel()
    }
}
