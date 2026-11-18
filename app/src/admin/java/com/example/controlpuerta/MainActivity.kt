package com.example.controlpuerta

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.controlpuerta.repository.FirebaseRepository
import com.example.controlpuerta.security.AccessRolePolicy
import com.example.controlpuerta.ui.screens.AdminScreen
import com.example.controlpuerta.ui.screens.UserScreen
import com.example.controlpuerta.ui.session.ControlPuertaSessionRoot
import com.example.controlpuerta.ui.theme.ControlPuertaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = FirebaseRepository()
        repository.inicializarSistema()

        setContent {
            ControlPuertaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ControlPuertaSessionRoot(
                        repository = repository,
                        allowAdmin = true,
                        roleSubtitle = "Acceso autorizado"
                    ) { user, onLogout ->
                        if (user.rol == AccessRolePolicy.ROL_ADMIN) {
                            AdminScreen(
                                currentUser = user,
                                repository = repository,
                                onLogout = onLogout
                            )
                        } else {
                            UserScreen(
                                currentUser = user,
                                repository = repository,
                                onLogout = onLogout
                            )
                        }
                    }
                }
            }
        }
    }
}
