package com.example.controlpuerta.repository

import android.util.Log
import com.example.controlpuerta.BuildConfig
import com.example.controlpuerta.model.EstadoPuerta
import com.example.controlpuerta.model.RegistroAcceso
import com.example.controlpuerta.model.Usuario
import com.example.controlpuerta.security.SecurityUtils
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

class FirebaseRepository {

    private val db = FirebaseFirestore.getInstance()
    private val usersCol = db.collection("usuarios")
    private val logsCol = db.collection("bitacora_accesos")
    private val doorDoc = db.collection("dispositivo_iot").document("puerta_principal")

    companion object {
        private const val TAG = "ControlPuertaIoT"
        const val MAX_INTENTOS = 3
    }

    fun inicializarSistema() {
        usersCol.document("admin_maestro").get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    val seedPassword = BuildConfig.INITIAL_ADMIN_PASSWORD
                    if (seedPassword.isBlank()) {
                        Log.w(TAG, "SEED_SKIP: admin.initial.password no configurada en local.properties; omitiendo creación automática de admin_maestro.")
                        return@addOnSuccessListener
                    }
                    val validacionSeed = SecurityUtils.validatePasswordStrength("admin_maestro", seedPassword)
                    if (!validacionSeed.isValid) {
                        Log.e(TAG, "SEED_ERROR: admin.initial.password no cumple política de seguridad: ${validacionSeed.errorMessage}")
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

        doorDoc.get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    doorDoc.set(EstadoPuerta())
                        .addOnSuccessListener {
                            Log.i(TAG, "SEED_OK: Estado inicial de puerta IoT guardado en servidor Firestore.")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "SEED_ERROR: Error al guardar puerta_principal: ${e.message}", e)
                        }
                } else {
                    Log.i(TAG, "SEED_SKIP: puerta_principal ya existe (fromCache=${doc.metadata.isFromCache}).")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "SEED_ERROR: Error al leer puerta_principal: ${e.message}", e)
            }
    }

    fun autenticar(
        usernameInput: String,
        passwordInput: String,
        onResult: (success: Boolean, usuario: Usuario?, mensaje: String) -> Unit
    ) {
        val cleanUser = usernameInput.trim()
        if (cleanUser.isEmpty() || passwordInput.isEmpty()) {
            Log.w(TAG, "AUTH_FAIL: Intento de login con campos vacíos.")
            onResult(false, null, "Debe ingresar usuario y contraseña.")
            return
        }

        usersCol.document(cleanUser).get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.exists()) {
                    Log.e(TAG, "AUTH_FAIL: El usuario '$cleanUser' no existe en la base de datos.")
                    onResult(false, null, "Este usuario no existe.")
                    return@addOnSuccessListener
                }

                val usuario = snapshot.toObject(Usuario::class.java) ?: return@addOnSuccessListener

                if (usuario.bloqueado) {
                    Log.e(TAG, "AUTH_BLOCKED: Acceso denegado. La cuenta '$cleanUser' está bloqueada.")
                    onResult(false, null, "Cuenta bloqueada por exceder 3 intentos. Contacte al Administrador.")
                    return@addOnSuccessListener
                }

                val inputHash = SecurityUtils.hashSha512(passwordInput)
                if (inputHash == usuario.passwordHash) {
                    usersCol.document(cleanUser).update("intentosFallidos", 0)
                    Log.i(TAG, "AUTH_OK: Sesión iniciada correctamente por '$cleanUser' [Rol: ${usuario.rol}].")
                    onResult(true, usuario.copy(intentosFallidos = 0), "Bienvenido, ${usuario.username}")
                } else {
                    val nuevosIntentos = usuario.intentosFallidos + 1
                    val debeBloquear = nuevosIntentos >= MAX_INTENTOS

                    usersCol.document(cleanUser).update(
                        mapOf(
                            "intentosFallidos" to nuevosIntentos,
                            "bloqueado" to debeBloquear
                        )
                    )

                    if (debeBloquear) {
                        Log.e(TAG, "SECURITY_LOCKOUT: Usuario '$cleanUser' BLOQUEADO tras $nuevosIntentos intentos fallidos.")
                        registrarEvento(cleanUser, usuario.rol, "BLOQUEO_POR_INTENTOS", "CERRADO")
                        onResult(false, null, "Contraseña incorrecta. Cuenta BLOQUEADA tras 3 intentos fallidos.")
                    } else {
                        val restantes = MAX_INTENTOS - nuevosIntentos
                        Log.w(TAG, "AUTH_FAIL: Contraseña incorrecta para '$cleanUser'. Intentos restantes: $restantes.")
                        onResult(false, null, "Contraseña incorrecta. Te quedan $restantes intento(s).")
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "NETWORK_ERROR: Error de conexión con Firestore: ${e.message}")
                onResult(false, null, "Error de red al validar credenciales.")
            }
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
                onComplete(false, "Error al eliminar: ${e.message}")
            }
    }

    fun observarUsuarios(onUpdate: (List<Usuario>) -> Unit) {
        usersCol.addSnapshotListener { snapshots, error ->
            if (error != null || snapshots == null) return@addSnapshotListener
            val lista = snapshots.toObjects(Usuario::class.java)
            onUpdate(lista)
        }
    }

    fun cambiarEstadoPuerta(abierta: Boolean, usuario: Usuario) {
        val estadoReed = if (abierta) "ABIERTO" else "CERRADO"
        val accion = if (abierta) "APERTURA_PUERTA" else "CIERRE_PUERTA"

        val nuevoEstado = EstadoPuerta(
            abierta = abierta,
            sensorReed = estadoReed,
            buzzerActivo = abierta,
            ultimoUsuario = usuario.username,
            ultimaActualizacion = System.currentTimeMillis()
        )

        doorDoc.set(nuevoEstado).addOnSuccessListener {
            Log.i(TAG, "IOT_CMD: Comando '$accion' ejecutado por '${usuario.username}'. Sensor Reed: $estadoReed.")
            registrarEvento(usuario.username, usuario.rol, accion, estadoReed)
        }
    }

    fun observarEstadoPuerta(onUpdate: (EstadoPuerta) -> Unit) {
        doorDoc.addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
            snapshot.toObject(EstadoPuerta::class.java)?.let(onUpdate)
        }
    }

    fun registrarEvento(username: String, rol: String, accion: String, sensorReed: String) {
        val docRef = logsCol.document()
        val registro = RegistroAcceso(
            id = docRef.id,
            username = username,
            rol = rol,
            accion = accion,
            sensorReed = sensorReed,
            timestamp = System.currentTimeMillis()
        )
        docRef.set(registro)
    }

    fun observarBitacora(onUpdate: (List<RegistroAcceso>) -> Unit) {
        logsCol.orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(30)
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null) return@addSnapshotListener
                onUpdate(snapshots.toObjects(RegistroAcceso::class.java))
            }
    }
}
