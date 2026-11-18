package com.example.controlpuerta.repository

import android.util.Log
import com.example.controlpuerta.BuildConfig
import com.example.controlpuerta.model.RegistroAcceso
import com.example.controlpuerta.model.Usuario
import com.example.controlpuerta.security.SecurityUtils
import com.google.firebase.firestore.Query

/**
 * Repositorio completo para la variante administrativa (incluye gestión de usuarios y bitácora global).
 */
class FirebaseRepository : BaseFirebaseDoorRepository(allowAdminLogin = true) {

    fun inicializarSistema() {
        usersCol.document("admin_maestro").get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    val seedPassword = BuildConfig.INITIAL_ADMIN_PASSWORD
                    if (seedPassword.isBlank()) {
                        Log.w(
                            TAG,
                            "SEED_SKIP: admin.initial.password no configurada en local.properties; omitiendo creación automática de admin_maestro."
                        )
                        return@addOnSuccessListener
                    }
                    val validacionSeed = SecurityUtils.validatePasswordStrength("admin_maestro", seedPassword)
                    if (!validacionSeed.isValid) {
                        Log.e(
                            TAG,
                            "SEED_ERROR: admin.initial.password no cumple política de seguridad: ${validacionSeed.errorMessage}"
                        )
                        return@addOnSuccessListener
                    }

                    val adminInicial = Usuario(
                        username = "admin_maestro",
                        passwordHash = SecurityUtils.hashSha512(seedPassword),
                        rol = "ADMIN",
                        intentosFallidos = 0,
                        bloqueado = false
                    )
                    usersCol.document("admin_maestro").set(adminInicial)
                        .addOnSuccessListener {
                            Log.i(TAG, "SEED_OK: Administrador maestro guardado en servidor Firestore.")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "SEED_ERROR: Error al guardar admin_maestro: ${e.message}", e)
                        }
                } else {
                    Log.i(TAG, "SEED_SKIP: admin_maestro ya existe (fromCache=${doc.metadata.isFromCache}).")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "SEED_ERROR: Error al leer admin_maestro: ${e.message}", e)
            }

        inicializarEstadoPuerta()
    }

    fun guardarUsuario(
        username: String,
        rawPassword: String,
        rol: String,
        onComplete: (Boolean, String) -> Unit
    ) {
        val cleanUser = username.trim()
        if (cleanUser.isEmpty()) {
            onComplete(false, "El nombre de usuario no puede estar vacío.")
            return
        }

        val validacion = SecurityUtils.validatePasswordStrength(cleanUser, rawPassword)
        if (!validacion.isValid) {
            Log.w(TAG, "PWD_POLICY_REJECT: Contraseña rechazada para '$cleanUser' -> ${validacion.errorMessage}")
            onComplete(false, validacion.errorMessage)
            return
        }

        val nuevoUsuario = Usuario(
            username = cleanUser,
            passwordHash = SecurityUtils.hashSha512(rawPassword),
            rol = rol,
            intentosFallidos = 0,
            bloqueado = false
        )

        usersCol.document(cleanUser).set(nuevoUsuario)
            .addOnSuccessListener {
                Log.i(TAG, "ADMIN_CRUD: Usuario '$cleanUser' ($rol) guardado con hash SHA-512.")
                onComplete(true, "Usuario '$cleanUser' guardado correctamente.")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "ADMIN_CRUD_ERROR: Error al guardar '$cleanUser': ${e.message}", e)
                onComplete(false, "Error al guardar usuario: ${e.message}")
            }
    }

    fun desbloquearYResetearClave(
        username: String,
        nuevaClave: String,
        adminUsername: String,
        onComplete: (Boolean, String) -> Unit
    ) {
        val validacion = SecurityUtils.validatePasswordStrength(username, nuevaClave)
        if (!validacion.isValid) {
            onComplete(false, validacion.errorMessage)
            return
        }

        usersCol.document(username).update(
            mapOf(
                "passwordHash" to SecurityUtils.hashSha512(nuevaClave),
                "intentosFallidos" to 0,
                "bloqueado" to false
            )
        ).addOnSuccessListener {
            Log.i(TAG, "ADMIN_UNLOCK: Cuenta '$username' desbloqueada por '$adminUsername'.")
            registrarEvento(username, "ADMIN:$adminUsername", "DESBLOQUEO_ADMIN", "CERRADO")
            onComplete(true, "Cuenta '$username' desbloqueada y clave restablecida.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "ADMIN_UNLOCK_ERROR: Error al desbloquear '$username': ${e.message}", e)
            onComplete(false, "Error al desbloquear: ${e.message}")
        }
    }

    fun bloquearUsuarioManual(
        username: String,
        adminUsername: String,
        onComplete: (Boolean, String) -> Unit
    ) {
        if (username == "admin_maestro" || username == adminUsername) {
            onComplete(false, "No puedes bloquear la cuenta maestra ni tu propia sesión activa.")
            return
        }

        usersCol.document(username).update(
            mapOf(
                "bloqueado" to true,
                "intentosFallidos" to MAX_INTENTOS
            )
        ).addOnSuccessListener {
            Log.w(TAG, "ADMIN_LOCK: Usuario '$username' bloqueado manualmente por '$adminUsername'.")
            registrarEvento(username, "ADMIN:$adminUsername", "BLOQUEO_MANUAL_ADMIN", "CERRADO")
            onComplete(true, "Usuario '$username' bloqueado correctamente.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "ADMIN_LOCK_ERROR: Error al bloquear '$username': ${e.message}", e)
            onComplete(false, "Error al bloquear usuario: ${e.message}")
        }
    }

    fun eliminarUsuario(username: String, onComplete: (Boolean, String) -> Unit) {
        if (username == "admin_maestro") {
            onComplete(false, "No se puede eliminar el administrador maestro.")
            return
        }
        usersCol.document(username).delete()
            .addOnSuccessListener {
                Log.i(TAG, "ADMIN_DELETE: Usuario '$username' eliminado.")
                onComplete(true, "Usuario eliminado.")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "ADMIN_DELETE_ERROR: Error al eliminar '$username': ${e.message}", e)
                onComplete(false, "Error al eliminar: ${e.message}")
            }
    }

    fun observarUsuarios(onUpdate: (List<Usuario>) -> Unit): () -> Unit {
        val registration = usersCol.addSnapshotListener { snapshots, error ->
            if (error != null) {
                Log.e(TAG, "ADMIN_USERS_LISTENER_ERROR: ${error.message}", error)
                return@addSnapshotListener
            }
            if (snapshots == null) return@addSnapshotListener
            onUpdate(snapshots.toObjects(Usuario::class.java))
        }
        return { registration.remove() }
    }

    fun observarBitacora(onUpdate: (List<RegistroAcceso>) -> Unit): () -> Unit {
        val registration = logsCol.orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(30)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "ADMIN_LOGS_LISTENER_ERROR: ${error.message}", error)
                    return@addSnapshotListener
                }
                if (snapshots == null) return@addSnapshotListener
                onUpdate(snapshots.toObjects(RegistroAcceso::class.java))
            }
        return { registration.remove() }
    }
}
