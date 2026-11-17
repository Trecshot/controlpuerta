package com.example.controlpuerta.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.controlpuerta.model.EstadoPuerta
import com.example.controlpuerta.model.RegistroAcceso
import com.example.controlpuerta.model.Usuario
import com.example.controlpuerta.repository.FirebaseRepository
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserScreen(
    currentUser: Usuario,
    repository: FirebaseRepository,
    onLogout: () -> Unit
) {
    var estadoPuerta by remember { mutableStateOf(EstadoPuerta()) }
    var bitacora by remember { mutableStateOf<List<RegistroAcceso>>(emptyList()) }
    val formatter = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()) }

    LaunchedEffect(Unit) {
        repository.observarEstadoPuerta { estadoPuerta = it }
        repository.observarBitacora { bitacora = it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Usuario: ${currentUser.username}") },
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "Cerrar sesión")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Control de Acceso Autorizado", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Estado actual de puerta: ${if (estadoPuerta.abierta) "ABIERTA" else "CERRADA"}")
                    Text("Sensor magnético: ${estadoPuerta.sensorReed}")
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { repository.cambiarEstadoPuerta(true, currentUser) },
                    enabled = !estadoPuerta.abierta,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Abrir Puerta")
                }
                OutlinedButton(
                    onClick = { repository.cambiarEstadoPuerta(false, currentUser) },
                    enabled = estadoPuerta.abierta,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cerrar Puerta")
                }
            }

            Text("Historial de Aperturas y Eventos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(bitacora) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("${item.accion} por ${item.username}", fontWeight = FontWeight.SemiBold)
                            Text("Sensor: ${item.sensorReed} • ${formatter.format(Date(item.timestamp))}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
