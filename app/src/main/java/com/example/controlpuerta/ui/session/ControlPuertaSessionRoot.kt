package com.example.controlpuerta.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.controlpuerta.model.Usuario
import com.example.controlpuerta.repository.DoorAuthRepository
import com.example.controlpuerta.security.AccessRolePolicy
import com.example.controlpuerta.security.SessionManager
import com.example.controlpuerta.ui.screens.LoginScreen

/**
 * Orquesta restauración segura de sesión TTL, verificación contra Firestore y cierre explícito.
 */
@Composable
fun ControlPuertaSessionRoot(
    repository: DoorAuthRepository,
    allowAdmin: Boolean,
    roleSubtitle: String,
    authenticatedContent: @Composable (user: Usuario, onLogout: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    val sessionManager = remember(context) { SessionManager(context) }

    var currentUser by rememberSaveable(stateSaver = UsuarioSessionSaver) {
        mutableStateOf<Usuario?>(null)
    }
    var accessDeniedError by rememberSaveable { mutableStateOf<String?>(null) }
    var hasCheckedStoredSession by rememberSaveable { mutableStateOf(false) }
    var isRestoringSession by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (currentUser != null || hasCheckedStoredSession) return@LaunchedEffect
        val storedSession = sessionManager.obtenerSesionVigente() ?: run {
            hasCheckedStoredSession = true
            return@LaunchedEffect
        }

        if (!AccessRolePolicy.isRoleAllowedForClient(storedSession.rol, allowAdmin = allowAdmin)) {
            hasCheckedStoredSession = true
            sessionManager.cerrarSesion()
            accessDeniedError = if (storedSession.rol == AccessRolePolicy.ROL_ADMIN) {
                AccessRolePolicy.MSG_MOBILE_USER_ONLY
            } else {
                AccessRolePolicy.MSG_UNRECOGNIZED_ROLE
            }
            return@LaunchedEffect
        }

        isRestoringSession = true
        repository.verificarSesionActiva(
            username = storedSession.username,
            expectedRol = storedSession.rol
        ) { restoredUser, errorMessage, shouldClearLocalSession ->
            hasCheckedStoredSession = true
            isRestoringSession = false
            if (restoredUser != null &&
                AccessRolePolicy.isRoleAllowedForClient(restoredUser.rol, allowAdmin = allowAdmin)
            ) {
                accessDeniedError = null
                currentUser = restoredUser
            } else {
                if (shouldClearLocalSession) {
                    sessionManager.cerrarSesion()
                }
                currentUser = null
                accessDeniedError = errorMessage
            }
        }
    }

    if (isRestoringSession) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Verificando sesión segura...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    when (val user = currentUser) {
        null -> LoginScreen(
            repository = repository,
            roleSubtitle = roleSubtitle,
            externalErrorMessage = accessDeniedError,
            onDismissExternalError = { accessDeniedError = null },
            onLoginSuccess = { loggedUser ->
                if (AccessRolePolicy.isRoleAllowedForClient(loggedUser.rol, allowAdmin = allowAdmin)) {
                    accessDeniedError = null
                    sessionManager.guardarSesion(loggedUser)
                    currentUser = loggedUser
                } else {
                    sessionManager.cerrarSesion()
                    currentUser = null
                    accessDeniedError = if (loggedUser.rol == AccessRolePolicy.ROL_ADMIN) {
                        AccessRolePolicy.MSG_MOBILE_USER_ONLY
                    } else {
                        AccessRolePolicy.MSG_UNRECOGNIZED_ROLE
                    }
                }
            }
        )

        else -> {
            if (AccessRolePolicy.isRoleAllowedForClient(user.rol, allowAdmin = allowAdmin)) {
                authenticatedContent(user) {
                    sessionManager.cerrarSesion()
                    currentUser = null
                    accessDeniedError = null
                }
            } else {
                LaunchedEffect(user) {
                    sessionManager.cerrarSesion()
                    currentUser = null
                    accessDeniedError = AccessRolePolicy.MSG_MOBILE_USER_ONLY
                }
            }
        }
    }
}
