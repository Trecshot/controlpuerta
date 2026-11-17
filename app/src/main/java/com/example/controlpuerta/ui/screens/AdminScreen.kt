package com.example.controlpuerta.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.controlpuerta.model.EstadoPuerta
import com.example.controlpuerta.model.RegistroAcceso
import com.example.controlpuerta.model.Usuario
import com.example.controlpuerta.repository.FirebaseRepository
import com.example.controlpuerta.security.SecurityUtils
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    currentUser: Usuario,
    repository: FirebaseRepository,
    onLogout: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var usuarios by remember { mutableStateOf<List<Usuario>>(emptyList()) }
    var bitacora by remember { mutableStateOf<List<RegistroAcceso>>(emptyList()) }
    var estadoPuerta by remember { mutableStateOf(EstadoPuerta()) }

    var conexionActiva by remember { mutableStateOf(true) }
    var showFailSafeDialog by remember { mutableStateOf(false) }

    var userToUnlock by remember { mutableStateOf<Usuario?>(null) }
    var newResetPassword by remember { mutableStateOf("") }
    var unlockFeedback by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        repository.observarUsuarios { usuarios = it }
        repository.observarBitacora { bitacora = it }
        repository.observarEstadoPuerta { estadoPuerta = it }
    }

    if (showFailSafeDialog) {
        AlertDialog(
            onDismissRequest = { },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Alerta Fail-Safe: Conexión Perdida") },
            text = {
                Text("Se perdió el enlace inalámbrico con el módulo IoT. Por seguridad, la puerta permanece en estado CERRADO. ¿Desea reconectar ahora?")
            },
            confirmButton = {
                Button(onClick = {
                    conexionActiva = true
                    showFailSafeDialog = false
                    Log.i("ControlPuertaIoT", "FAIL_SAFE_RECOVERY: Enlace inalámbrico restablecido por ${currentUser.username}.")
                }) {
                    Text("Reconectar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFailSafeDialog = false }) {
                    Text("Mantener Seguro")
                }
            }
        )
    }

    userToUnlock?.let { target ->
        AlertDialog(
            onDismissRequest = {
                userToUnlock = null
                newResetPassword = ""
                unlockFeedback = ""
            },
            title = { Text("Desbloquear / Clave: ${target.username}") },
            text = {
                Column {
                    Text("Asigne una nueva contraseña segura (mín. 8 caracteres, mayúscula, minúscula, número y símbolo):")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newResetPassword,
                        onValueChange = { newResetPassword = it },
                        label = { Text("Nueva contraseña") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    if (unlockFeedback.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(unlockFeedback, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    repository.desbloquearYResetearClave(
                        username = target.username,
                        nuevaClave = newResetPassword,
                        adminUsername = currentUser.username
                    ) { ok, msg ->
                        if (ok) {
                            userToUnlock = null
                            newResetPassword = ""
                            unlockFeedback = ""
                        } else {
                            unlockFeedback = msg
                        }
                    }
                }) {
                    Text("Desbloquear y Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    userToUnlock = null
                    newResetPassword = ""
                    unlockFeedback = ""
                }) {
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
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "Cerrar sesión")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Puerta IoT") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Usuarios") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Bitácora") })
            }

            when (selectedTab) {
                0 -> PuertaControlTab(
                    estadoPuerta = estadoPuerta,
                    conexionActiva = conexionActiva,
                    onTogglePuerta = { abrir -> repository.cambiarEstadoPuerta(abrir, currentUser) },
                    onSimularDesconexion = {
                        conexionActiva = false
                        if (estadoPuerta.abierta) {
                            repository.cambiarEstadoPuerta(false, currentUser)
                        }
                        Log.e("ControlPuertaIoT", "FAIL_SAFE_TRIGGER: Pérdida de conexión inalámbrica detectada.")
                        showFailSafeDialog = true
                    }
                )
                1 -> GestionUsuariosTab(
                    usuarios = usuarios,
                    currentUser = currentUser,
                    repository = repository,
                    onRequestUnlock = { userToUnlock = it }
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Telemetría Nodo Arduino", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Enlace Bluetooth/Red: ${if (conexionActiva) "CONECTADO" else "DESCONECTADO (FAIL-SAFE)"}")
                Text("Estado Puerta: ${if (estadoPuerta.abierta) "ABIERTA" else "CERRADA"}")
                Text("Sensor Reed Switch: ${estadoPuerta.sensorReed}")
                Text("Buzzer Alerta: ${if (estadoPuerta.buzzerActivo) "ACTIVO" else "INACTIVO"}")
                Text("Última acción por: ${estadoPuerta.ultimoUsuario}")
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { onTogglePuerta(true) },
                enabled = conexionActiva && !estadoPuerta.abierta,
                modifier = Modifier.weight(1f)
            ) {
                Text("Abrir Puerta")
            }
            OutlinedButton(
                onClick = { onTogglePuerta(false) },
                enabled = conexionActiva && estadoPuerta.abierta,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cerrar Puerta")
            }
        }

        HorizontalDivider()

        OutlinedButton(
            onClick = onSimularDesconexion,
            modifier = Modifier.fillMaxWidth()
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
    var newUsername by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var selectedRol by remember { mutableStateOf("USUARIO") }
    var statusMsg by remember { mutableStateOf("") }
    var isStatusError by remember { mutableStateOf(false) }
    var soloBloqueados by remember { mutableStateOf(false) }

    val pwdCheck = remember(newUsername, newPassword) {
        SecurityUtils.validatePasswordStrength(newUsername, newPassword)
    }

    val bloqueadosCount = remember(usuarios) { usuarios.count { it.bloqueado } }
    val listaFiltrada = remember(usuarios, soloBloqueados) {
        if (soloBloqueados) usuarios.filter { it.bloqueado } else usuarios
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
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("Contraseña segura") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
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
                        RadioButton(selected = selectedRol == "USUARIO", onClick = { selectedRol = "USUARIO" })
                        Text("Usuario")
                        Spacer(modifier = Modifier.width(16.dp))
                        RadioButton(selected = selectedRol == "ADMIN", onClick = { selectedRol = "ADMIN" })
                        Text("Administrador")
                    }

                    Button(
                        onClick = {
                            repository.guardarUsuario(newUsername, newPassword, selectedRol) { ok, msg ->
                                statusMsg = msg
                                isStatusError = !ok
                                if (ok) {
                                    newUsername = ""
                                    newPassword = ""
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Registrar Usuario")
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
                        Text(
                            text = "SHA-512: ${user.passwordHash.take(18)}...",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (user.bloqueado) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        if (user.bloqueado) {
                            IconButton(onClick = { onRequestUnlock(user) }) {
                                Icon(Icons.Default.LockOpen, contentDescription = "Desbloquear / Cambiar clave")
                            }
                        } else if (user.username != "admin_maestro" && user.username != currentUser.username) {
                            IconButton(onClick = {
                                repository.bloquearUsuarioManual(user.username, currentUser.username) { ok, msg ->
                                    statusMsg = msg
                                    isStatusError = !ok
                                }
                            }) {
                                Icon(Icons.Default.Lock, contentDescription = "Bloquear usuario")
                            }
                        }
                        if (user.username != "admin_maestro" && user.username != currentUser.username) {
                            IconButton(onClick = {
                                repository.eliminarUsuario(user.username) { ok, msg ->
                                    statusMsg = msg
                                    isStatusError = !ok
                                }
                            }) {
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(bitacora, key = { it.id.ifEmpty { "${it.timestamp}_${it.username}_${it.accion}" } }) { item ->
            val esAlertaSeguridad = item.accion == "BLOQUEO_POR_INTENTOS" || item.accion == "BLOQUEO_MANUAL_ADMIN"
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
