package com.example.controlpuerta.model

data class Usuario(
    val username: String = "",
    val passwordHash: String = "",
    /** Sal aleatoria (hex). Vacía en cuentas heredadas con SHA-512 plano, que se migran al iniciar sesión. */
    val passwordSalt: String = "",
    /** Iteraciones PBKDF2. 0 indica hash heredado SHA-512. */
    val hashIterations: Int = 0,
    val rol: String = "USUARIO",
    val intentosFallidos: Int = 0,
    val bloqueado: Boolean = false
)

data class RegistroAcceso(
    val id: String = "",
    val username: String = "",
    val rol: String = "",
    val accion: String = "",
    val sensorReed: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class EstadoPuerta(
    val abierta: Boolean = false,
    val sensorReed: String = "CERRADO",
    val buzzerActivo: Boolean = false,
    val ultimoUsuario: String = "Sistema",
    val ultimaActualizacion: Long = System.currentTimeMillis()
)
