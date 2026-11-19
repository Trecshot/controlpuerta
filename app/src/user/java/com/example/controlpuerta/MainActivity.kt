package com.example.controlpuerta

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.controlpuerta.repository.FirebaseRepository
import com.example.controlpuerta.ui.screens.UserScreen
import com.example.controlpuerta.ui.session.ControlPuertaSessionRoot
import com.example.controlpuerta.ui.theme.ControlPuertaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = FirebaseRepository()
        if (savedInstanceState == null) {
            // Evita lecturas redundantes de arranque al recrear la Activity.
            repository.inicializarEstadoPuerta()
        }

        setContent {
            ControlPuertaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ControlPuertaSessionRoot(
                        repository = repository,
                        allowAdmin = false,
                        roleSubtitle = "Acceso autorizado"
                    ) { user, onLogout ->
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
