package com.example.controlpuerta.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.controlpuerta.repository.FirebaseRepository
import com.example.controlpuerta.security.SecurityUtils

private enum class BootstrapState { CHECKING, NEEDS_SETUP, READY, ERROR }

/**
 * Configuración inicial de la consola administrativa. Si la cuenta maestra no existe, el operador
 * define su clave aquí (cumpliendo la política de contraseñas). Ningún secreto viaja dentro del APK.
 */
@Composable
fun AdminBootstrapGate(
    repository: FirebaseRepository,
    content: @Composable () -> Unit
) {
    var state by rememberSaveable { mutableStateOf(BootstrapState.CHECKING) }
    var retryKey by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(retryKey) {
        if (state == BootstrapState.READY) return@LaunchedEffect
        state = BootstrapState.CHECKING
        repository.existeAdminMaestro { exists ->
            state = when (exists) {
                true -> BootstrapState.READY
                false -> BootstrapState.NEEDS_SETUP
                null -> BootstrapState.ERROR
            }
        }
    }

    when (state) {
        BootstrapState.READY -> content()
        BootstrapState.CHECKING -> Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            Text("Verificando configuración del sistema...")
        }

        BootstrapState.ERROR -> Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "No se pudo verificar la configuración. Compruebe su conexión a internet.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { retryKey++ }) { Text("Reintentar") }
        }

        BootstrapState.NEEDS_SETUP -> AdminSetupForm(
            repository = repository,
            onCreated = { state = BootstrapState.READY }
        )
    }
}

@Composable
private fun AdminSetupForm(
    repository: FirebaseRepository,
    onCreated: () -> Unit
) {
    // Las contraseñas NO usan rememberSaveable: no deben terminar en el Bundle de estado.
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Configuración inicial",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Defina la contraseña de la cuenta maestra (${FirebaseRepository.ADMIN_MASTER_USERNAME}). " +
                "Mínimo 8 caracteres, mayúscula, minúscula, número y símbolo.",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; errorMessage = null },
            label = { Text("Contraseña") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it; errorMessage = null },
            label = { Text("Confirmar contraseña") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth()
        )
        errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = {
                val validation = SecurityUtils.validatePasswordStrength(
                    FirebaseRepository.ADMIN_MASTER_USERNAME,
                    password
                )
                when {
                    password != confirm -> errorMessage = "Las contraseñas no coinciden."
                    !validation.isValid -> errorMessage = validation.errorMessage
                    else -> {
                        isSaving = true
                        repository.crearAdminMaestro(password) { ok, msg ->
                            isSaving = false
                            if (ok) {
                                password = ""
                                confirm = ""
                                onCreated()
                            } else {
                                errorMessage = msg
                            }
                        }
                    }
                }
            },
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
        ) {
            if (isSaving) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
            } else {
                Text("Crear cuenta maestra")
            }
        }
    }
}
