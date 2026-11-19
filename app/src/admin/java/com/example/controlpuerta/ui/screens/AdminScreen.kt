package com.example.controlpuerta.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.controlpuerta.model.EstadoPuerta
import com.example.controlpuerta.model.RegistroAcceso
import com.example.controlpuerta.model.Usuario
import com.example.controlpuerta.repository.FirebaseRepository
import com.example.controlpuerta.security.AccessRolePolicy
import com.example.controlpuerta.security.SecurityUtils
import com.example.controlpuerta.util.rememberIsNetworkConnected
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    currentUser: Usuario,
    repository: FirebaseRepository,
    onLogout: (reason: String?) -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var usuarios by remember { mutableStateOf<List<Usuario>>(emptyList()) }
    var bitacora by remember { mutableStateOf<List<RegistroAcceso>>(emptyList()) }
    var estadoPuerta by remember { mutableStateOf(EstadoPuerta()) }

    var conexionActiva by rememberSaveable { mutableStateOf(true) }
    var showFailSafeDialog by rememberSaveable { mutableStateOf(false) }
    val isNetworkConnected by rememberIsNetworkConnected()
    val enlaceRemotoOperativo = isNetworkConnected && conexionActiva

    var userToUnlock by rememberSaveable { mutableStateOf<String?>(null) }
    // Las contraseñas no usan rememberSaveable: no deben persistir en el Bundle de estado.
    var newResetPassword by remember { mutableStateOf("") }
    var unlockFeedback by remember { mutableStateOf("") }
    var isUnlocking by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }

    DisposableEffect(currentUser.username) {
        val removeUsers = repository.observarUsuarios(onError = notify) { usuarios = it }
        val removeLogs = repository.observarBitacora(onError = notify) { bitacora = it }
        val removeDoor = repository.observarEstadoPuerta(onError = notify) { estado, _ -> estadoPuerta = estado }
        val removeSession = repository.observarSesionUsuario(
            username = currentUser.username,
            expectedRol = currentUser.rol,
            onRevoked = { message -> onLogout(message) }
        )
        onDispose {
            removeUsers()
            removeLogs()
            removeDoor()
            removeSession()
        }
    }

    val ejecutarFailSafeAdmin: () -> Unit = {
        estadoPuerta = estadoPuerta.copy(
            abierta = false,
            sensorReed = AccessRolePolicy.SENSOR_CERRADO,
            buzzerActivo = false,
            ultimoUsuario = currentUser.username,
            ultimaActualizacion = System.currentTimeMillis()
        )
        repository.aplicarBloqueoFailSafe(currentUser) { ok, msg -> if (!ok) notify(msg) }
    }

    // Fail-Safe / Fail-Secure automático ante pérdida de Wi-Fi/Internet con puerta abierta.
    LaunchedEffect(isNetworkConnected) {
        if (!isNetworkConnected && estadoPuerta.abierta) {
            ejecutarFailSafeAdmin()
            showFailSafeDialog = true
        }
    }

    if (showFailSafeDialog) {
        AlertDialog(
            onDismissRequest = { },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Alerta Fail-Safe / Fail-Secure: Enlace Perdido") },
            text = {
                Text(
                    "Se interrumpió la energía o el enlace inalámbrico con el nodo IoT. " +
                        "Por política Fail-Secure, la puerta quedó automáticamente CERRADA y BLOQUEADA por defecto. " +
                        "¿Desea restablecer la gestión remota por Wi-Fi ahora?"
                )
            },
            confirmButton = {
                Button(onClick = {
                    conexionActiva = true
                    showFailSafeDialog = false
                    Log.i("ControlPuertaIoT", "FAIL_SAFE_RECOVERY: Gestión remota Wi-Fi restablecida por ${currentUser.username}.")
                }) {
                    Text("Reconectar Wi-Fi / Cloud")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFailSafeDialog = false }) {
                    Text("Mantener Bloqueado")
                }
            }
        )
    }

    userToUnlock?.let { targetName ->
        val dismiss = {
            userToUnlock = null
            newResetPassword = ""
            unlockFeedback = ""
            isUnlocking = false
        }
        AlertDialog(
            onDismissRequest = { if (!isUnlocking) dismiss() },
            title = { Text("Desbloquear / Clave: $targetName") },
            text = {
                Column {
                    Text("Asigne una nueva contraseña segura (mín. 8 caracteres, mayúscula, minúscula, número y símbolo):")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newResetPassword,
                        onValueChange = { newResetPassword = it },
                        label = { Text("Nueva contraseña") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !isUnlocking
                    )
                    if (unlockFeedback.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(unlockFeedback, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isUnlocking,
                    onClick = {
                        isUnlocking = true
                        repository.desbloquearYResetearClave(
                            username = targetName,
                            nuevaClave = newResetPassword,
                            adminUsername = currentUser.username
                        ) { ok, msg ->
                            isUnlocking = false
                            if (ok) {
                                dismiss()
                                notify(msg)
                            } else {
                                unlockFeedback = msg
                            }
                        }
                    }
                ) {
                    Text("Desbloquear y Guardar")
                }
            },
            dismissButton = {
                TextButton(enabled = !isUnlocking, onClick = dismiss) {
                    Text("Cancelar")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Admin: ${currentUser.username}") },
                actions = {
                    IconButton(onClick = { onLogout(null) }) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Cerrar sesión")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Puerta IoT") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Usuarios") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Bitácora") })
            }

            when (selectedTab) {
                0 -> PuertaControlTab(
                    estadoPuerta = estadoPuerta,
                    conexionActiva = enlaceRemotoOperativo,
                    onTogglePuerta = { abrir ->
                        repository.cambiarEstadoPuerta(abrir, currentUser) { ok, msg -> if (!ok) notify(msg) }
                    },
                    onSimularDesconexion = {
                        conexionActiva = false
                        ejecutarFailSafeAdmin()
                        Log.e("ControlPuertaIoT", "FAIL_SAFE_TRIGGER: Corte de energía/enlace detectado -> Puerta CERRADA y bloqueada.")
                        showFailSafeDialog = true
                    }
                )
                1 -> GestionUsuariosTab(
                    usuarios = usuarios,
                    currentUser = currentUser,
                    repository = repository,
                    onRequestUnlock = { userToUnlock = it.username }
                )
                2 -> BitacoraTab(bitacora = bitacora)
            }
        }
    }
}

@Composable
fun PuertaControlTab(
    estadoPuerta: EstadoPuerta,
    conexionActiva: Boolean,
    onTogglePuerta: (Boolean) -> Unit,
    onSimularDesconexion: () -> Unit
) {
    // verticalScroll: en horizontal o pantallas compactas los botones no quedan fuera de la vista.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Modelo Híbrido y Telemetría Nodo Arduino", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Canal Admin (Wi-Fi / Cloud Remoto): ${if (conexionActiva) "CONECTADO" else "DESCONECTADO (FAIL-SECURE ACTIVO)"}")
                Text("Canal Operador en Puerta: Bluetooth HC-05 (Proximidad física)")
                Text("Política Fail-Safe / Fail-Secure: Cerrada y bloqueada por defecto ante corte de energía o Bluetooth")
                Text("Estado Puerta: ${if (estadoPuerta.abierta) "ABIERTA" else "CERRADA Y BLOQUEADA"}")
                Text("Sensor Reed Switch: ${estadoPuerta.sensorReed}")
                Text("Buzzer Alerta: ${if (estadoPuerta.buzzerActivo) "ACTIVO" else "INACTIVO"}")
                Text("Última acción por: ${estadoPuerta.ultimoUsuario}")
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { onTogglePuerta(true) },
                enabled = conexionActiva && !estadoPuerta.abierta,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
            ) {
                Text("Abrir Puerta")
            }
            OutlinedButton(
                onClick = { onTogglePuerta(false) },
                enabled = conexionActiva && estadoPuerta.abierta,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
            ) {
                Text("Cerrar Puerta")
            }
        }

        HorizontalDivider()

        OutlinedButton(
            onClick = onSimularDesconexion,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Icon(Icons.Default.Warning, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Simular Pérdida de Conexión (Prueba Fail-Safe)")
        }
    }
}

@Composable
fun GestionUsuariosTab(
    usuarios: List<Usuario>,
    currentUser: Usuario,
    repository: FirebaseRepository,
    onRequestUnlock: (Usuario) -> Unit
) {
    var newUsername by rememberSaveable { mutableStateOf("") }
    // La contraseña NO se guarda en el Bundle de estado.
    var newPassword by remember { mutableStateOf("") }
    var selectedRol by rememberSaveable { mutableStateOf(AccessRolePolicy.ROL_USUARIO) }
    var statusMsg by rememberSaveable { mutableStateOf("") }
    var isStatusError by rememberSaveable { mutableStateOf(false) }
    var soloBloqueados by rememberSaveable { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var userPendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

    val pwdCheck = remember(newUsername, newPassword) {
        SecurityUtils.validatePasswordStrength(newUsername, newPassword)
    }

    val bloqueadosCount = remember(usuarios) { usuarios.count { it.bloqueado } }
    val listaFiltrada = remember(usuarios, soloBloqueados) {
        if (soloBloqueados) usuarios.filter { it.bloqueado } else usuarios
    }

    userPendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { userPendingDelete = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Eliminar usuario") },
            text = { Text("¿Eliminar definitivamente la cuenta '$target'? Esta acción no se puede deshacer.") },
            confirmButton = {
                Button(onClick = {
                    userPendingDelete = null
                    repository.eliminarUsuario(target) { ok, msg ->
                        statusMsg = msg
                        isStatusError = !ok
                    }
                }) { Text("Eliminar") }
            },
            dismissButton = {
                TextButton(onClick = { userPendingDelete = null }) { Text("Cancelar") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Crear Cuenta de Acceso", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = newUsername,
                        onValueChange = { newUsername = it },
                        label = { Text("Nombre de usuario") },
                        supportingText = { Text("3 a 32 caracteres: letras, números, . - _") },
                        singleLine = true,
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("Contraseña segura") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "• Mínimo 8 caracteres: ${if (pwdCheck.hasMinLength) "CUMPLE" else "NO CUMPLE"}\n" +
                                "• Mayúscula y minúscula: ${if (pwdCheck.hasUpperAndLower) "CUMPLE" else "NO CUMPLE"}\n" +
                                "• Número y carácter especial: ${if (pwdCheck.hasDigit && pwdCheck.hasSpecialChar) "CUMPLE" else "NO CUMPLE"}\n" +
                                "• Sin usuario ni patrones comunes: ${if (pwdCheck.notSameAsUsername) "CUMPLE" else "NO CUMPLE"}",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedRol == AccessRolePolicy.ROL_USUARIO,
                            onClick = { selectedRol = AccessRolePolicy.ROL_USUARIO }
                        )
                        Text("Usuario")
                        Spacer(modifier = Modifier.width(16.dp))
                        RadioButton(
                            selected = selectedRol == AccessRolePolicy.ROL_ADMIN,
                            onClick = { selectedRol = AccessRolePolicy.ROL_ADMIN }
                        )
                        Text("Administrador")
                    }

                    Button(
                        onClick = {
                            isSaving = true
                            repository.guardarUsuario(newUsername, newPassword, selectedRol) { ok, msg ->
                                isSaving = false
                                statusMsg = msg
                                isStatusError = !ok
                                if (ok) {
                                    newUsername = ""
                                    newPassword = ""
                                }
                            }
                        },
                        enabled = !isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Registrar Usuario")
                        }
                    }

                    if (statusMsg.isNotEmpty()) {
                        Text(
                            text = statusMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isStatusError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = !soloBloqueados,
                    onClick = { soloBloqueados = false },
                    label = { Text("Todos (${usuarios.size})") }
                )
                FilterChip(
                    selected = soloBloqueados,
                    onClick = { soloBloqueados = true },
                    label = { Text("Bloqueados ($bloqueadosCount)") }
                )
            }
        }

        if (soloBloqueados && listaFiltrada.isEmpty()) {
            item {
                Text(
                    text = "No hay cuentas bloqueadas.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }

        items(listaFiltrada, key = { it.username }) { user ->
            val isProtected = user.username == FirebaseRepository.ADMIN_MASTER_USERNAME ||
                user.username == currentUser.username
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = if (user.bloqueado) {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                } else {
                    CardDefaults.cardColors()
                }
            ) {
                Row(
                    modifier = Modifier
                        .padding(12.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${user.username} (${user.rol})",
                            fontWeight = FontWeight.Bold,
                            color = if (user.bloqueado) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (user.bloqueado) "ESTADO: BLOQUEADO (3/3)" else "Estado: Activo (Fallos: ${user.intentosFallidos}/3)",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (user.bloqueado) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        if (user.bloqueado) {
                            IconButton(onClick = { onRequestUnlock(user) }) {
                                Icon(Icons.Default.LockOpen, contentDescription = "Desbloquear / Cambiar clave")
                            }
                        } else if (!isProtected) {
                            IconButton(onClick = {
                                repository.bloquearUsuarioManual(user.username, currentUser.username) { ok, msg ->
                                    statusMsg = msg
                                    isStatusError = !ok
                                }
                            }) {
                                Icon(Icons.Default.Lock, contentDescription = "Bloquear usuario")
                            }
                        }
                        if (!isProtected) {
                            IconButton(onClick = { userPendingDelete = user.username }) {
                                Icon(Icons.Default.Delete, contentDescription = "Eliminar usuario")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BitacoraTab(bitacora: List<RegistroAcceso>) {
    val formatter = remember { SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()) }
    val registros = remember(bitacora) { AccessRolePolicy.distinctRecords(bitacora) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(registros, key = { AccessRolePolicy.recordKey(it) }) { item ->
            val esAlertaSeguridad = item.accion == "BLOQUEO_POR_INTENTOS" ||
                item.accion == "BLOQUEO_MANUAL_ADMIN" ||
                item.accion == "CIERRE_FAIL_SAFE" ||
                item.accion == "ENLACE_PERDIDO"
            val esDesbloqueoAdmin = item.accion == "DESBLOQUEO_ADMIN"
            val cardColors = when {
                esAlertaSeguridad -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                esDesbloqueoAdmin -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                else -> CardDefaults.cardColors()
            }
            val textColor = when {
                esAlertaSeguridad -> MaterialTheme.colorScheme.onErrorContainer
                esDesbloqueoAdmin -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            }
            val subTextColor = when {
                esAlertaSeguridad -> MaterialTheme.colorScheme.onErrorContainer
                esDesbloqueoAdmin -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = cardColors
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "${item.accion} - ${item.username} (${item.rol})",
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                    Text(
                        text = "Sensor Reed: ${item.sensorReed} | Fecha y hora: ${formatter.format(Date(item.timestamp))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = subTextColor
                    )
                }
            }
        }
    }
}
