package com.example.controlpuerta.repository

import android.util.Log
import com.example.controlpuerta.model.RegistroAcceso
import com.example.controlpuerta.model.Usuario
import com.example.controlpuerta.security.AccessRolePolicy
import com.example.controlpuerta.security.SecurityUtils
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source

/**
 * Repositorio completo para la variante administrativa (incluye gestión de usuarios y bitácora global).
 */
class FirebaseRepository : BaseFirebaseDoorRepository(allowAdminLogin = true) {

    companion object {
        const val ADMIN_MASTER_USERNAME: String = "admin_maestro"
        private const val USERS_QUERY_LIMIT: Long = 200
        private const val LOGS_QUERY_LIMIT: Long = 30
        private const val MSG_INVALID_USERNAME: String =
            "Usuario inválido: use 3 a 32 caracteres (letras, números, punto, guion o guion bajo)."
        private const val MSG_NETWORK_CRYPTO: String = "No se pudo procesar la contraseña en este dispositivo."
    }

    fun inicializarSistema() {
        inicializarEstadoPuerta()
    }

    /**
     * Consulta (en servidor) si la cuenta maestra ya existe. Entrega `null` si no pudo determinarse.
     * Reemplaza la antigua siembra con contraseña embebida en el APK (BuildConfig).
     */
    fun existeAdminMaestro(onResult: (exists: Boolean?) -> Unit) {
        usersCol.document(ADMIN_MASTER_USERNAME).get(Source.SERVER)
            .addOnSuccessListener { doc -> onResult(doc.exists()) }
            .addOnFailureListener { e ->
                Log.e(TAG, "ADMIN_BOOTSTRAP_ERROR: No se pudo consultar admin_maestro: ${e.message}", e)
                onResult(null)
            }
    }

    /**
     * Configuración inicial: el operador define la clave de la cuenta maestra en el primer arranque.
     */
    fun crearAdminMaestro(rawPassword: String, onComplete: (Boolean, String) -> Unit) {
        crearUsuarioNuevo(ADMIN_MASTER_USERNAME, rawPassword, AccessRolePolicy.ROL_ADMIN, onComplete)
    }

    fun guardarUsuario(
        username: String,
        rawPassword: String,
        rol: String,
        onComplete: (Boolean, String) -> Unit
    ) {
        crearUsuarioNuevo(username.trim(), rawPassword, rol, onComplete)
    }

    /**
     * Alta atómica: la transacción aborta si la cuenta ya existe, de modo que nunca se sobrescribe
     * silenciosamente la clave, el rol o el bloqueo de otro usuario (incluido `admin_maestro`).
     */
    private fun crearUsuarioNuevo(
        cleanUser: String,
        rawPassword: String,
        rol: String,
        onComplete: (Boolean, String) -> Unit
    ) {
        if (!SecurityUtils.isValidUsername(cleanUser)) {
            onComplete(false, MSG_INVALID_USERNAME)
            return
        }
        if (!AccessRolePolicy.isAssignableRole(rol)) {
            onComplete(false, "Rol no permitido.")
            return
        }
        val validacion = SecurityUtils.validatePasswordStrength(cleanUser, rawPassword)
        if (!validacion.isValid) {
            Log.w(TAG, "PWD_POLICY_REJECT: Contraseña rechazada para '$cleanUser' -> ${validacion.errorMessage}")
            onComplete(false, validacion.errorMessage)
            return
        }

        runCrypto(
            work = { SecurityUtils.deriveCredential(rawPassword) },
            onDone = { cred ->
                val nuevoUsuario = Usuario(
                    username = cleanUser,
                    passwordHash = cred.hash,
                    passwordSalt = cred.salt,
                    hashIterations = cred.iterations,
                    rol = rol,
                    intentosFallidos = 0,
                    bloqueado = false
                )
                val ref = usersCol.document(cleanUser)
                db.runTransaction { tx ->
                    if (tx.get(ref).exists()) {
                        throw IllegalStateException("EXISTS")
                    }
                    tx.set(ref, nuevoUsuario)
                    null
                }.addOnSuccessListener {
                    Log.i(TAG, "ADMIN_CRUD: Usuario '$cleanUser' ($rol) creado con PBKDF2 + sal.")
                    onComplete(true, "Usuario '$cleanUser' guardado correctamente.")
                }.addOnFailureListener { e ->
                    if (e is IllegalStateException || e.cause is IllegalStateException) {
                        onComplete(false, "El usuario '$cleanUser' ya existe. Elija otro nombre.")
                    } else {
                        Log.e(TAG, "ADMIN_CRUD_ERROR: Error al guardar '$cleanUser': ${e.message}", e)
                        onComplete(false, "Error al guardar usuario: ${e.message}")
                    }
                }
            },
            onError = { t ->
                Log.e(TAG, "ADMIN_CRYPTO_ERROR: ${t.message}", t)
                onComplete(false, MSG_NETWORK_CRYPTO)
            }
        )
    }

    fun desbloquearYResetearClave(
        username: String,
        nuevaClave: String,
        adminUsername: String,
        onComplete: (Boolean, String) -> Unit
    ) {
        if (!SecurityUtils.isValidUsername(username)) {
            onComplete(false, MSG_INVALID_USERNAME)
            return
        }
        val validacion = SecurityUtils.validatePasswordStrength(username, nuevaClave)
        if (!validacion.isValid) {
            onComplete(false, validacion.errorMessage)
            return
        }

        runCrypto(
            work = { SecurityUtils.deriveCredential(nuevaClave) },
            onDone = { cred ->
                usersCol.document(username).update(
                    mapOf(
                        "passwordHash" to cred.hash,
                        "passwordSalt" to cred.salt,
                        "hashIterations" to cred.iterations,
                        "intentosFallidos" to 0,
                        "bloqueado" to false
                    )
                ).addOnSuccessListener {
                    Log.i(TAG, "ADMIN_UNLOCK: Cuenta '$username' desbloqueada por '$adminUsername'.")
                    registrarEvento(username, "ADMIN:$adminUsername", "DESBLOQUEO_ADMIN", AccessRolePolicy.SENSOR_CERRADO)
                    onComplete(true, "Cuenta '$username' desbloqueada y clave restablecida.")
                }.addOnFailureListener { e ->
                    Log.e(TAG, "ADMIN_UNLOCK_ERROR: Error al desbloquear '$username': ${e.message}", e)
                    onComplete(false, "Error al desbloquear: ${e.message}")
                }
            },
            onError = { t ->
                Log.e(TAG, "ADMIN_CRYPTO_ERROR: ${t.message}", t)
                onComplete(false, MSG_NETWORK_CRYPTO)
            }
        )
    }

    fun bloquearUsuarioManual(
        username: String,
        adminUsername: String,
        onComplete: (Boolean, String) -> Unit
    ) {
        if (username == ADMIN_MASTER_USERNAME || username == adminUsername) {
            onComplete(false, "No puedes bloquear la cuenta maestra ni tu propia sesión activa.")
            return
        }
        if (!SecurityUtils.isValidUsername(username)) {
            onComplete(false, MSG_INVALID_USERNAME)
            return
        }

        val ref = usersCol.document(username)
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            if (!snap.exists()) {
                throw IllegalStateException("NOT_FOUND")
            }
            val updates = mutableMapOf<String, Any>(
                "bloqueado" to true,
                "intentosFallidos" to MAX_INTENTOS
            )
            if (!snap.contains("passwordSalt")) updates["passwordSalt"] = ""
            if (!snap.contains("hashIterations")) updates["hashIterations"] = 0
            tx.update(ref, updates)
            null
        }.addOnSuccessListener {
            Log.w(TAG, "ADMIN_LOCK: Usuario '$username' bloqueado manualmente por '$adminUsername'.")
            registrarEvento(username, "ADMIN:$adminUsername", "BLOQUEO_MANUAL_ADMIN", AccessRolePolicy.SENSOR_CERRADO)
            onComplete(true, "Usuario '$username' bloqueado correctamente.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "ADMIN_LOCK_ERROR: Error al bloquear '$username': ${e.message}", e)
            onComplete(false, "Error al bloquear usuario: ${e.message}")
        }
    }

    fun eliminarUsuario(username: String, onComplete: (Boolean, String) -> Unit) {
        if (username == ADMIN_MASTER_USERNAME) {
            onComplete(false, "No se puede eliminar el administrador maestro.")
            return
        }
        if (!SecurityUtils.isValidUsername(username)) {
            onComplete(false, MSG_INVALID_USERNAME)
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

    fun observarUsuarios(
        onError: (String) -> Unit = {},
        onUpdate: (List<Usuario>) -> Unit
    ): () -> Unit {
        val registration = usersCol.limit(USERS_QUERY_LIMIT).addSnapshotListener { snapshots, error ->
            if (error != null) {
                Log.e(TAG, "ADMIN_USERS_LISTENER_ERROR: ${error.message}", error)
                onError("No se pudo sincronizar la lista de usuarios.")
                return@addSnapshotListener
            }
            if (snapshots == null) return@addSnapshotListener
            onUpdate(snapshots.toObjects(Usuario::class.java))
        }
        return { registration.remove() }
    }

    fun observarBitacora(
        onError: (String) -> Unit = {},
        onUpdate: (List<RegistroAcceso>) -> Unit
    ): () -> Unit {
        val registration = logsCol.orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(LOGS_QUERY_LIMIT)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "ADMIN_LOGS_LISTENER_ERROR: ${error.message}", error)
                    onError("No se pudo sincronizar la bitácora.")
                    return@addSnapshotListener
                }
                if (snapshots == null) return@addSnapshotListener
                onUpdate(snapshots.toObjects(RegistroAcceso::class.java))
            }
        return { registration.remove() }
    }
}
