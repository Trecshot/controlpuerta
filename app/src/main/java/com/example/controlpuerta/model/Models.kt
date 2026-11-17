package com.example.controlpuerta.model

data class Usuario(
    val username: String = "",
    val passwordHash: String = "",
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
