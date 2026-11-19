package com.example.controlpuerta.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.controlpuerta.BuildConfig
import com.example.controlpuerta.model.EstadoPuerta
import com.example.controlpuerta.model.RegistroAcceso
import com.example.controlpuerta.model.Usuario
import com.example.controlpuerta.repository.BluetoothController
import com.example.controlpuerta.repository.DoorLinkPolicy
import com.example.controlpuerta.repository.DoorOperatorRepository
import com.example.controlpuerta.security.AccessRolePolicy
import com.example.controlpuerta.util.rememberIsNetworkConnected
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val USER_SCREEN_TAG = "ControlPuertaIoT"
private const val COMMAND_CONFIRMATION_TIMEOUT_MS = 10_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserScreen(
    currentUser: Usuario,
    repository: DoorOperatorRepository,
    onLogout: (reason: String?) -> Unit,
    allowSimulation: Boolean = BuildConfig.ALLOW_SIMULATION
) {
    var estadoPuerta by remember { mutableStateOf(EstadoPuerta()) }
    var hasLoadedDoorState by remember { mutableStateOf(false) }
    var isFromCache by remember { mutableStateOf(false) }
    var bitacoraPropia by remember { mutableStateOf<List<RegistroAcceso>>(emptyList()) }
    var hasLoadedLogs by remember { mutableStateOf(false) }
    var syncError by remember { mutableStateOf<String?>(null) }
    var isCommandPending by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val btController = remember(context) { BluetoothController(context) }
    val btConectado by btController.conectado.collectAsState()
    val btConectando by btController.conectando.collectAsState()
    val btNombre by btController.dispositivoNombre.collectAsState()

    // En producción (release) la simulación está deshabilitada: la APK de usuario solo opera hardware real.
    var modoSimulacion by rememberSaveable { mutableStateOf(allowSimulation) }
    var mostrarSelectorBt by remember { mutableStateOf(false) }
    var dispositivosVinculados by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }

    val isNetworkConnected by rememberIsNetworkConnected()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val formatter = remember { SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()) }
    val currentOnLogout by rememberUpdatedState(onLogout)
    val registrosVisibles = remember(bitacoraPropia) { AccessRolePolicy.distinctRecords(bitacoraPropia) }


    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permisos ->
        val concedido = permisos.values.isNotEmpty() && permisos.values.all { it }
        if (concedido) {
            dispositivosVinculados = btController.obtenerDispositivosVinculados()
            mostrarSelectorBt = true
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Se necesitan permisos de Bluetooth para conectar con la cerradura.")
            }
        }
    }

    val abrirSelectorBluetooth: () -> Unit = {
        if (!btController.isBluetoothSupported()) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Este dispositivo no cuenta con adaptador Bluetooth.")
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !btController.hasBluetoothPermission()) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            )
        } else {
            dispositivosVinculados = btController.obtenerDispositivosVinculados()
            mostrarSelectorBt = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            btController.release()
        }
    }

    DisposableEffect(currentUser.username) {
        val removeDoorListener = repository.observarEstadoPuerta(
            onError = { message -> syncError = message }
        ) { nuevoEstado, fromCache ->
            estadoPuerta = nuevoEstado
            isFromCache = fromCache
            hasLoadedDoorState = true
            syncError = null
        }

        val removeLogsListener = repository.observarBitacoraUsuario(
            username = currentUser.username,
            onError = { message ->
                syncError = message
                hasLoadedLogs = true
            }
        ) { registros ->
            bitacoraPropia = registros
            hasLoadedLogs = true
        }

        val removeSessionWatcher = repository.observarSesionUsuario(
            username = currentUser.username,
            expectedRol = currentUser.rol
        ) { message ->
            // Cuenta bloqueada/eliminada/cambio de rol por el administrador: cierre inmediato.
            currentOnLogout(message)
        }

        onDispose {
            removeDoorListener()
            removeLogsListener()
            removeSessionWatcher()
        }
    }

    val activarCierreFailSafe: () -> Unit = {
        isCommandPending = false
        estadoPuerta = estadoPuerta.copy(
            abierta = false,
            sensorReed = AccessRolePolicy.SENSOR_CERRADO,
            buzzerActivo = false,
            ultimoUsuario = currentUser.username,
            ultimaActualizacion = System.currentTimeMillis()
        )
        repository.aplicarBloqueoFailSafe(currentUser)
    }

    // Fail-Safe / Fail-Secure real: ante corte de energía del Arduino/HC-05 o pérdida pasiva de Bluetooth,
    // la puerta queda CERRADA y bloqueada por defecto.
    LaunchedEffect(btController) {
        btController.conexionPerdida.collect {
            activarCierreFailSafe()
            snackbarHostState.showSnackbar(
                "Corte de Bluetooth/energía detectado. Fail-Secure activo: Puerta CERRADA y bloqueada."
            )
        }
    }

    // Si la puerta estaba abierta y cae el enlace operativo (red en simulación o Bluetooth en hardware),
    // se fuerza inmediatamente el estado seguro CERRADO y bloqueado (Fail-Secure).
    LaunchedEffect(isNetworkConnected, btConectado, modoSimulacion) {
        val linkOk = DoorLinkPolicy.isLinkOperational(modoSimulacion, isNetworkConnected, btConectado)
        if (!linkOk && estadoPuerta.abierta) {
            activarCierreFailSafe()
            snackbarHostState.showSnackbar(
                "Enlace interrumpido con puerta abierta. Fail-Secure activo: Puerta CERRADA y bloqueada."
            )
        }
    }

    // Telemetría asíncrona del sensor magnético Reed enviada por el Arduino (si el sketch reporta por Serial).
    LaunchedEffect(btController, modoSimulacion) {
        if (!modoSimulacion) {
            btController.telemetriaReed.collect { reedReportado ->
                if (estadoPuerta.sensorReed != reedReportado) {
                    estadoPuerta = estadoPuerta.copy(
                        sensorReed = reedReportado,
                        ultimaActualizacion = System.currentTimeMillis()
                    )
                }
            }
        }
    }

    // Un comando nunca queda "pendiente" indefinidamente (p. ej. sin red la confirmación del servidor tarda).
    LaunchedEffect(isCommandPending) {
        if (isCommandPending) {
            delay(COMMAND_CONFIRMATION_TIMEOUT_MS)
            isCommandPending = false
            snackbarHostState.showSnackbar(
                "Sin confirmación del servidor todavía. El registro se sincronizará al recuperar la conexión."
            )
        }
    }

    val enviarComandoPuerta: (Boolean) -> Unit = { abrir ->
        val canExecute = DoorLinkPolicy.canExecuteDoorAction(
            modoSimulacion = modoSimulacion,
            isNetworkConnected = isNetworkConnected,
            btConectado = btConectado,
            hasLoadedDoorState = hasLoadedDoorState,
            isCommandPending = isCommandPending,
            isDoorCurrentlyOpen = estadoPuerta.abierta,
            requestedOpen = abrir
        )
        if (canExecute) {
            isCommandPending = true
            if (modoSimulacion) {
                repository.cambiarEstadoPuerta(abrir, currentUser) { success, message ->
                    isCommandPending = false
                    if (!success) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(message)
                        }
                    }
                }
            } else {
                val comando = DoorLinkPolicy.commandForDoorAction(abrir)
                btController.enviarComando(comando) { exito, _, reedReportado ->
                    if (exito) {
                        repository.cambiarEstadoPuerta(
                            abierta = abrir,
                            usuario = currentUser,
                            sensorReedConfirmado = reedReportado
                        ) { success, message ->
                            isCommandPending = false
                            if (!success) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        "La cerradura respondió, pero no se pudo registrar el evento: $message"
                                    )
                                }
                            }
                        }
                    } else {
                        // Fallo de transmisión Bluetooth: se aplica Fail-Secure (puerta CERRADA y bloqueada).
                        if (estadoPuerta.abierta) {
                            activarCierreFailSafe()
                        } else {
                            isCommandPending = false
                        }
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                "Fallo de enlace con la cerradura. Fail-Secure activo: Puerta CERRADA y bloqueada."
                            )
                        }
                    }
                }
            }
        }
    }

    if (mostrarSelectorBt) {
        BluetoothDeviceSelectorDialog(
            isBluetoothEnabled = btController.isBluetoothEnabled(),
            dispositivosVinculados = dispositivosVinculados,
            onSelectDevice = { device ->
                mostrarSelectorBt = false
                btController.conectarDispositivo(device) { _, msg ->
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(msg)
                    }
                }
            },
            onDismiss = { mostrarSelectorBt = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Usuario: ${currentUser.username}") },
                actions = {
                    IconButton(onClick = { onLogout(null) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = "Cerrar sesión"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            DoorControlBottomBar(
                canOpenDoor = DoorLinkPolicy.canExecuteDoorAction(
                    modoSimulacion = modoSimulacion,
                    isNetworkConnected = isNetworkConnected,
                    btConectado = btConectado,
                    hasLoadedDoorState = hasLoadedDoorState,
                    isCommandPending = isCommandPending,
                    isDoorCurrentlyOpen = estadoPuerta.abierta,
                    requestedOpen = true
                ),
                canCloseDoor = DoorLinkPolicy.canExecuteDoorAction(
                    modoSimulacion = modoSimulacion,
                    isNetworkConnected = isNetworkConnected,
                    btConectado = btConectado,
                    hasLoadedDoorState = hasLoadedDoorState,
                    isCommandPending = isCommandPending,
                    isDoorCurrentlyOpen = estadoPuerta.abierta,
                    requestedOpen = false
                ),
                onOpenDoor = { enviarComandoPuerta(true) },
                onCloseDoor = { enviarComandoPuerta(false) }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "connection_failsafe") {
                    ConnectionModeAndFailSafeCard(
                        modoSimulacion = modoSimulacion,
                        allowSimulation = allowSimulation,
                        isNetworkConnected = isNetworkConnected,
                        isFromCache = isFromCache,
                        btConectado = btConectado,
                        btConectando = btConectando,
                        btNombre = btNombre,
                        onToggleHardwareMode = { usarHardwareReal ->
                            modoSimulacion = !usarHardwareReal
                            if (usarHardwareReal) {
                                abrirSelectorBluetooth()
                            } else {
                                btController.desconectar()
                            }
                        },
                        onSelectBluetoothDevice = abrirSelectorBluetooth
                    )
                }

                item(key = "door_telemetry") {
                    if (!hasLoadedDoorState && syncError == null) {
                        DoorStatusSkeletonCard()
                    } else {
                        DoorStatusCard(
                            estadoPuerta = estadoPuerta,
                            formattedTime = formatter.format(Date(estadoPuerta.ultimaActualizacion))
                        )
                    }
                }

                syncError?.let { errorMsg ->
                    item(key = "sync_error") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = errorMsg,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                item(key = "logs_header") {
                    Text(
                        text = "Mis Registros de Acceso",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (!hasLoadedLogs && syncError == null) {
                    items(count = 3, key = { idx -> "skeleton_log_$idx" }) {
                        LogSkeletonCard()
                    }
                } else if (registrosVisibles.isEmpty()) {
                    item(key = "empty_logs") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Aún no tienes registros de acceso.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                } else {
                    items(
                        items = registrosVisibles,
                        key = { item -> AccessRolePolicy.recordKey(item) }
                    ) { item ->
                        AccessLogItemCard(
                            registro = item,
                            formattedTime = formatter.format(Date(item.timestamp))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionModeAndFailSafeCard(
    modoSimulacion: Boolean,
    allowSimulation: Boolean,
    isNetworkConnected: Boolean,
    isFromCache: Boolean,
    btConectado: Boolean,
    btConectando: Boolean,
    btNombre: String,
    onToggleHardwareMode: (Boolean) -> Unit,
    onSelectBluetoothDevice: () -> Unit
) {
    val isOperational = DoorLinkPolicy.isLinkOperational(modoSimulacion, isNetworkConnected, btConectado)
    val containerColor = when {
        isOperational -> MaterialTheme.colorScheme.secondaryContainer
        btConectando -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when {
        isOperational -> MaterialTheme.colorScheme.onSecondaryContainer
        btConectando -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onErrorContainer
    }
    val statusIcon = when {
        modoSimulacion && isNetworkConnected -> Icons.Default.Wifi
        modoSimulacion -> Icons.Default.WifiOff
        btConectado -> Icons.Default.BluetoothConnected
        btConectando -> Icons.Default.Bluetooth
        else -> Icons.Default.BluetoothDisabled
    }
    val statusText = when {
        modoSimulacion && !isNetworkConnected -> "Sin conexión con la puerta (Bloqueo de seguridad activo)"
        modoSimulacion && isFromCache -> "Conexión: Modo de prueba (Actualizando...)"
        modoSimulacion -> "Conexión: Modo de prueba"
        btConectando -> "Conectando con la cerradura..."
        btConectado && !isNetworkConnected -> "Puerta vinculada ($btNombre) • Sin internet"
        btConectado -> "Conexión: Puerta vinculada ($btNombre)"
        else -> "Sin conexión con la puerta (Bloqueo de seguridad activo)"
    }
    val detailText = when {
        modoSimulacion && !isNetworkConnected ->
            "Se perdió la conexión a internet. Fail-Secure activo: la puerta queda CERRADA y bloqueada por defecto."
        modoSimulacion ->
            "Modo de prueba activo (sin conexión física a la puerta)."
        btConectando ->
            "Vinculando con la cerradura por Bluetooth (proximidad física en puerta)..."
        btConectado && !isNetworkConnected ->
            "Proximidad Bluetooth HC-05 activa. Puede operar sin internet; la bitácora se sincronizará al recuperar señal."
        btConectado ->
            "Proximidad física Bluetooth (HC-05) activa. Listo para abrir o cerrar en la puerta."
        else ->
            "Fail-Secure activo (Puerta CERRADA y bloqueada por defecto). Conecte el Bluetooth HC-05 frente a la puerta para operar."
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = contentColor
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = contentColor
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                val switchBadgeText = when {
                    modoSimulacion -> "SIMULACIÓN"
                    btConectado -> "HC-05"
                    btConectando -> "CONECTANDO"
                    else -> "DESCONECTADO"
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = switchBadgeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor
                    )
                    if (allowSimulation) {
                        Switch(
                            checked = !modoSimulacion,
                            onCheckedChange = onToggleHardwareMode,
                            modifier = Modifier.semantics {
                                contentDescription = "Alternar entre modo de prueba y conexión física a la cerradura"
                            }
                        )
                    }
                }
            }

            Text(
                text = detailText,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor
            )

            if (btConectando) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (!modoSimulacion) {
                OutlinedButton(
                    onClick = onSelectBluetoothDevice,
                    enabled = !btConectando,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bluetooth,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (btConectado) "Cambiar cerradura" else "Seleccionar cerradura")
                }
            }
        }
    }
}

@Composable
private fun BluetoothDeviceSelectorDialog(
    isBluetoothEnabled: Boolean,
    dispositivosVinculados: List<BluetoothDevice>,
    onSelectDevice: (BluetoothDevice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Seleccionar Cerradura Bluetooth") },
        text = {
            when {
                !isBluetoothEnabled -> {
                    Text(
                        "El Bluetooth del teléfono está apagado. Enciéndalo en Ajustes, vincule la cerradura y vuelva a intentarlo."
                    )
                }

                dispositivosVinculados.isEmpty() -> {
                    Text(
                        "No se encontraron cerraduras vinculadas. Vaya a Ajustes > Bluetooth de su teléfono, empareje la cerradura (PIN 1234 o 0000) y vuelva a intentarlo."
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = dispositivosVinculados,
                            key = { device -> device.address }
                        ) { device ->
                            @SuppressLint("MissingPermission")
                            val nombre = try {
                                device.name ?: "Dispositivo desconocido"
                            } catch (e: SecurityException) {
                                Log.w(USER_SCREEN_TAG, "BT_NAME_READ_WARN: ${e.message}")
                                "Dispositivo Bluetooth"
                            }
                            OutlinedButton(
                                onClick = { onSelectDevice(device) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Text(
                                        text = nombre,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = device.address,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
}

@Composable
private fun DoorStatusCard(
    estadoPuerta: EstadoPuerta,
    formattedTime: String
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Estado de la Puerta",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (estadoPuerta.abierta) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Text(
                        text = if (estadoPuerta.abierta) "Abierta" else "Cerrada",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (estadoPuerta.abierta) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            TelemetryRow(
                icon = Icons.Default.Sensors,
                label = "Verificación física: Puerta ${AccessRolePolicy.formatUserFriendlyDoorState(estadoPuerta.sensorReed).lowercase()}"
            )
            TelemetryRow(
                icon = if (estadoPuerta.buzzerActivo) {
                    Icons.Default.NotificationsActive
                } else {
                    Icons.Default.NotificationsOff
                },
                label = "Alarma sonora: ${if (estadoPuerta.buzzerActivo) "Encendida" else "Apagada"}"
            )
            TelemetryRow(
                icon = Icons.Default.Shield,
                label = "Última actualización: $formattedTime"
            )
        }
    }
}

@Composable
private fun TelemetryRow(
    icon: ImageVector,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun DoorControlBottomBar(
    canOpenDoor: Boolean,
    canCloseDoor: Boolean,
    onOpenDoor: () -> Unit,
    onCloseDoor: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 6.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOpenDoor()
                    },
                    enabled = canOpenDoor,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LockOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Abrir Puerta")
                }

                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onCloseDoor()
                    },
                    enabled = canCloseDoor,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Cerrar Puerta")
                }
            }
        }
    }
}

@Composable
private fun AccessLogItemCard(
    registro: RegistroAcceso,
    formattedTime: String
) {
    val accionLegible = AccessRolePolicy.formatUserFriendlyAction(registro.accion)
    val estadoFisico = AccessRolePolicy.formatUserFriendlyDoorState(registro.sensorReed)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = accionLegible,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Estado físico: $estadoFisico • $formattedTime",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DoorStatusSkeletonCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SkeletonBox(widthFraction = 0.55f, heightDp = 20)
            SkeletonBox(widthFraction = 0.75f, heightDp = 16)
            SkeletonBox(widthFraction = 0.65f, heightDp = 16)
            SkeletonBox(widthFraction = 0.80f, heightDp = 14)
        }
    }
}

@Composable
private fun LogSkeletonCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SkeletonBox(widthFraction = 0.45f, heightDp = 16)
            SkeletonBox(widthFraction = 0.70f, heightDp = 14)
        }
    }
}

@Composable
private fun SkeletonBox(widthFraction: Float, heightDp: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(heightDp.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}
