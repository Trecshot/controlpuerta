package com.example.controlpuerta.ui.session

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.example.controlpuerta.model.Usuario

/**
 * Conserva la sesión activa ante rotaciones de pantalla sin almacenar hashes de contraseña en el Bundle.
 * La restauración es tolerante a datos corruptos: ante cualquier tipo inesperado devuelve `null`.
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
        val username = saved.getOrNull(0) as? String
        val rol = saved.getOrNull(1) as? String
        val intentos = saved.getOrNull(2) as? Int
        val bloqueado = saved.getOrNull(3) as? Boolean
        if (username == null || rol == null || intentos == null || bloqueado == null) {
            null
        } else {
            Usuario(
                username = username,
                passwordHash = "",
                rol = rol,
                intentosFallidos = intentos,
                bloqueado = bloqueado
            )
        }
    }
)
