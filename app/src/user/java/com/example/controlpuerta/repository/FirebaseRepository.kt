package com.example.controlpuerta.repository

/**
 * Repositorio exclusivo para la APK móvil de usuario.
 * No expone métodos administrativos ni inicialización de cuentas maestras.
 */
class FirebaseRepository : BaseFirebaseDoorRepository(allowAdminLogin = false)
