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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.example.controlpuerta.security.SessionPolicy
import com.example.controlpuerta.ui.screens.LoginScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SESSION_EXPIRY_CHECK_MS = 15_000L

/**
 * Orquesta restauración segura de sesión TTL, verificación contra Firestore y cierre explícito.
 * Las operaciones de Keystore (AES-GCM) se ejecutan fuera del hilo principal y el TTL se
 * reevalúa de forma periódica mientras la sesión está activa.
 */
@Composable
fun ControlPuertaSessionRoot(
    repository: DoorAuthRepository,
    allowAdmin: Boolean,
    roleSubtitle: String,
    authenticatedContent: @Composable (user: Usuario, onLogout: (reason: String?) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val sessionManager = remember(context) { SessionManager(context) }
    val scope = rememberCoroutineScope()

    var currentUser by rememberSaveable(stateSaver = UsuarioSessionSaver) {
        mutableStateOf<Usuario?>(null)
    }
    var accessDeniedError by rememberSaveable { mutableStateOf<String?>(null) }
    var hasCheckedStoredSession by rememberSaveable { mutableStateOf(false) }
    // Persistente ante rotación: evita dos verificaciones paralelas con callbacks huérfanos.
    var isRestoringSession by rememberSaveable { mutableStateOf(false) }
    var sessionExpiresAt by rememberSaveable { mutableLongStateOf(0L) }

    val endSession: (String?) -> Unit = { reason ->
        scope.launch(Dispatchers.Default) { sessionManager.cerrarSesion() }
        currentUser = null
        accessDeniedError = reason
    }

    LaunchedEffect(Unit) {
        if (currentUser != null || hasCheckedStoredSession) return@LaunchedEffect
        val storedSession = withContext(Dispatchers.Default) { sessionManager.obtenerSesionVigente() }
        if (storedSession == null) {
            hasCheckedStoredSession = true
            return@LaunchedEffect
        }

        if (!AccessRolePolicy.isRoleAllowedForClient(storedSession.rol, allowAdmin = allowAdmin)) {
            hasCheckedStoredSession = true
            endSession(AccessRolePolicy.deniedMessageForRole(storedSession.rol))
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
                sessionExpiresAt = storedSession.expiresAtMillis
                currentUser = restoredUser
            } else {
                if (shouldClearLocalSession) {
                    scope.launch(Dispatchers.Default) { sessionManager.cerrarSesion() }
                }
                currentUser = null
                accessDeniedError = errorMessage
            }
        }
    }

    // Expiración en vivo del TTL de 12 h: una sesión abierta no puede operar indefinidamente.
    // Se compara contra el vencimiento en memoria (sin descifrar Keystore en cada chequeo).
    LaunchedEffect(currentUser?.username) {
        if (currentUser == null) return@LaunchedEffect
        while (true) {
            if (!SessionPolicy.isSessionActive(sessionExpiresAt, System.currentTimeMillis())) {
                endSession("Tu sesión expiró. Inicia sesión nuevamente.")
                return@LaunchedEffect
            }
            delay(SESSION_EXPIRY_CHECK_MS)
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
                    sessionExpiresAt = SessionPolicy.calculateExpiresAt(System.currentTimeMillis())
                    scope.launch {
                        withContext(Dispatchers.Default) { sessionManager.guardarSesion(loggedUser) }
                        currentUser = loggedUser
                    }
                } else {
                    endSession(AccessRolePolicy.deniedMessageForRole(loggedUser.rol))
                }
            }
        )

        else -> {
            if (AccessRolePolicy.isRoleAllowedForClient(user.rol, allowAdmin = allowAdmin)) {
                authenticatedContent(user) { reason -> endSession(reason) }
            } else {
                LaunchedEffect(user) {
                    endSession(AccessRolePolicy.deniedMessageForRole(user.rol))
                }
            }
        }
    }
}
