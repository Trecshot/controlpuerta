package com.example.controlpuerta

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import com.example.controlpuerta.model.Usuario
import com.example.controlpuerta.repository.FirebaseRepository
import com.example.controlpuerta.ui.screens.AdminScreen
import com.example.controlpuerta.ui.screens.LoginScreen
import com.example.controlpuerta.ui.screens.UserScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = FirebaseRepository()
        repository.inicializarSistema()

        setContent {
            MaterialTheme {
                Surface {
                    var currentUser by remember { mutableStateOf<Usuario?>(null) }

                    when (val user = currentUser) {
                        null -> LoginScreen(
                            repository = repository,
                            onLoginSuccess = { loggedUser -> currentUser = loggedUser }
                        )
                        else -> {
                            if (user.rol == "ADMIN") {
                                AdminScreen(
                                    currentUser = user,
                                    repository = repository,
                                    onLogout = { currentUser = null }
                                )
                            } else {
                                UserScreen(
                                    currentUser = user,
                                    repository = repository,
                                    onLogout = { currentUser = null }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
