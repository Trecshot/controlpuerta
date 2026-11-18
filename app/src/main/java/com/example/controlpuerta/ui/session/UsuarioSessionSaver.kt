package com.example.controlpuerta.ui.session

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.example.controlpuerta.model.Usuario

/**
 * Conserva la sesión activa ante rotaciones de pantalla sin almacenar hashes de contraseña en el Bundle.
 */
val UsuarioSessionSaver: Saver<Usuario?, Any> = listSaver(
    save = { usuario ->
        if (usuario == null) {
            emptyList()
        } else {
            listOf(
                usuario.username,
                usuario.rol,
                usuario.intentosFallidos,
                usuario.bloqueado
            )
        }
    },
    restore = { saved ->
        if (saved.size < 4) {
            null
        } else {
            Usuario(
                username = saved[0] as String,
                passwordHash = "",
                rol = saved[1] as String,
                intentosFallidos = saved[2] as Int,
                bloqueado = saved[3] as Boolean
            )
        }
    }
)
