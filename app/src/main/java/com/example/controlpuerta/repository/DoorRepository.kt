package com.example.controlpuerta.repository

import android.util.Log
import com.example.controlpuerta.model.EstadoPuerta
import com.example.controlpuerta.model.RegistroAcceso
import com.example.controlpuerta.model.Usuario
import com.example.controlpuerta.security.AccessRolePolicy
import com.example.controlpuerta.security.SecurityUtils
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Contrato de autenticación compartido por la pantalla de inicio de sesión.
 */
interface DoorAuthRepository {
    fun autenticar(
        usernameInput: String,
        passwordInput: String,
        onResult: (success: Boolean, usuario: Usuario?, mensaje: String) -> Unit
    )

    fun verificarSesionActiva(
        username: String,
        expectedRol: String,
        onResult: (restoredUser: Usuario?, errorMessage: String?, shouldClearLocalSession: Boolean) -> Unit
    )
}

/**
 * Contrato operativo de puerta y bitácora propia para el operador móvil.
 */
interface DoorOperatorRepository : DoorAuthRepository {
    fun cambiarEstadoPuerta(
        abierta: Boolean,
        usuario: Usuario,
        onComplete: ((success: Boolean, message: String) -> Unit)? = null
    )

    fun observarEstadoPuerta(
        onError: (String) -> Unit = {},
        onUpdate: (estado: EstadoPuerta, isFromCache: Boolean) -> Unit
    ): () -> Unit

    fun observarBitacoraUsuario(
        username: String,
        onError: (String) -> Unit = {},
        onUpdate: (List<RegistroAcceso>) -> Unit
    ): () -> Unit
}

/**
 * Implementación base sobre Cloud Firestore compartida entre las variantes `user` y `admin`.
 * La política de roles permitidos se fija por compilación mediante [allowAdminLogin].
 */
open class BaseFirebaseDoorRepository(
    private val allowAdminLogin: Boolean
) : DoorOperatorRepository {

    protected val db: FirebaseFirestore = FirebaseFirestore.getInstance()
    protected val usersCol: CollectionReference = db.collection("usuarios")
    protected val logsCol: CollectionReference = db.collection("bitacora_accesos")
    protected val doorDoc: DocumentReference =
        db.collection("dispositivo_iot").document("puerta_principal")

    companion object {
        protected const val TAG: String = "ControlPuertaIoT"
        const val MAX_INTENTOS: Int = 3
    }

    /**
     * Garantiza la existencia del documento IoT inicial sin sembrar cuentas administrativas.
     */
    fun inicializarEstadoPuerta() {
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

    override fun autenticar(
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

                val usuario = snapshot.toObject(Usuario::class.java)
                if (usuario == null) {
                    Log.e(TAG, "AUTH_PARSE_ERROR: Documento inválido para '$cleanUser'.")
                    onResult(false, null, "No se pudo leer el perfil del usuario.")
                    return@addOnSuccessListener
                }

                if (usuario.bloqueado) {
                    Log.e(TAG, "AUTH_BLOCKED: Acceso denegado. La cuenta '$cleanUser' está bloqueada.")
                    onResult(
                        false,
                        null,
                        "Cuenta bloqueada por exceder $MAX_INTENTOS intentos. Contacte al Administrador."
                    )
                    return@addOnSuccessListener
                }

                val inputHash = SecurityUtils.hashSha512(passwordInput)
                if (inputHash != usuario.passwordHash) {
                    registrarIntentoFallido(cleanUser, usuario, onResult)
                    return@addOnSuccessListener
                }

                if (!AccessRolePolicy.isRoleAllowedForClient(usuario.rol, allowAdminLogin)) {
                    val deniedMessage = if (usuario.rol == AccessRolePolicy.ROL_ADMIN) {
                        AccessRolePolicy.MSG_MOBILE_USER_ONLY
                    } else {
                        AccessRolePolicy.MSG_UNRECOGNIZED_ROLE
                    }
                    Log.w(
                        TAG,
                        "AUTH_ROLE_DENIED: Acceso bloqueado para '$cleanUser' [Rol: ${usuario.rol}] en cliente móvil."
                    )
                    onResult(false, null, deniedMessage)
                    return@addOnSuccessListener
                }

                if (usuario.intentosFallidos != 0) {
                    usersCol.document(cleanUser).update("intentosFallidos", 0)
                        .addOnFailureListener { e ->
                            Log.w(TAG, "AUTH_WARN: No se pudo reiniciar intentosFallidos de '$cleanUser': ${e.message}", e)
                        }
                }
                Log.i(TAG, "AUTH_OK: Sesión iniciada correctamente por '$cleanUser' [Rol: ${usuario.rol}].")
                onResult(true, usuario.copy(intentosFallidos = 0), "Bienvenido, ${usuario.username}")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "NETWORK_ERROR: Error de conexión con Firestore: ${e.message}", e)
                onResult(false, null, "Error de red al validar credenciales.")
            }
    }

    override fun verificarSesionActiva(
        username: String,
        expectedRol: String,
        onResult: (restoredUser: Usuario?, errorMessage: String?, shouldClearLocalSession: Boolean) -> Unit
    ) {
        val cleanUser = username.trim()
        if (cleanUser.isEmpty() || !AccessRolePolicy.isRoleAllowedForClient(expectedRol, allowAdminLogin)) {
            val message = if (expectedRol == AccessRolePolicy.ROL_ADMIN) {
                AccessRolePolicy.MSG_MOBILE_USER_ONLY
            } else {
                AccessRolePolicy.MSG_UNRECOGNIZED_ROLE
            }
            onResult(null, message, true)
            return
        }

        usersCol.document(cleanUser).get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.exists()) {
                    Log.w(TAG, "SESSION_INVALID: La cuenta '$cleanUser' ya no existe.")
                    onResult(null, "Tu cuenta ya no está disponible. Inicia sesión nuevamente.", true)
                    return@addOnSuccessListener
                }

                val usuario = snapshot.toObject(Usuario::class.java)
                if (usuario == null) {
                    Log.e(TAG, "SESSION_PARSE_ERROR: Documento inválido para '$cleanUser'.")
                    onResult(null, "No se pudo validar tu perfil. Inicia sesión nuevamente.", true)
                    return@addOnSuccessListener
                }

                if (usuario.bloqueado) {
                    Log.w(TAG, "SESSION_REVOKED_LOCKED: Cuenta '$cleanUser' bloqueada en Firestore.")
                    onResult(null, "Cuenta bloqueada. Contacte al Administrador.", true)
                    return@addOnSuccessListener
                }

                if (!AccessRolePolicy.isRoleAllowedForClient(usuario.rol, allowAdminLogin)) {
                    val deniedMessage = if (usuario.rol == AccessRolePolicy.ROL_ADMIN) {
                        AccessRolePolicy.MSG_MOBILE_USER_ONLY
                    } else {
                        AccessRolePolicy.MSG_UNRECOGNIZED_ROLE
                    }
                    Log.w(TAG, "SESSION_REVOKED_ROLE: Rol '${usuario.rol}' bloqueado para '$cleanUser'.")
                    onResult(null, deniedMessage, true)
                    return@addOnSuccessListener
                }

                if (usuario.rol != expectedRol) {
                    Log.w(TAG, "SESSION_REVOKED_ROLE_CHANGE: El rol de '$cleanUser' cambió de '$expectedRol' a '${usuario.rol}'.")
                    onResult(null, "Tus permisos cambiaron. Inicia sesión nuevamente.", true)
                    return@addOnSuccessListener
                }

                Log.i(TAG, "SESSION_RESTORED: Sesión persistente restaurada para '$cleanUser' [Rol: ${usuario.rol}].")
                onResult(usuario.copy(passwordHash = ""), null, false)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "SESSION_VERIFY_ERROR: Error de red al verificar sesión de '$cleanUser': ${e.message}", e)
                onResult(null, "No se pudo verificar tu sesión en el servidor. Comprueba tu red e inicia sesión.", false)
            }
    }

    private fun registrarIntentoFallido(
        cleanUser: String,
        usuario: Usuario,
        onResult: (success: Boolean, usuario: Usuario?, mensaje: String) -> Unit
    ) {
        val nuevosIntentos = usuario.intentosFallidos + 1
        val debeBloquear = nuevosIntentos >= MAX_INTENTOS

        usersCol.document(cleanUser).update(
            mapOf(
                "intentosFallidos" to nuevosIntentos,
                "bloqueado" to debeBloquear
            )
        ).addOnFailureListener { e ->
            Log.e(TAG, "AUTH_LOCK_UPDATE_ERROR: No se pudo actualizar intentos de '$cleanUser': ${e.message}", e)
        }

        if (debeBloquear) {
            Log.e(TAG, "SECURITY_LOCKOUT: Usuario '$cleanUser' BLOQUEADO tras $nuevosIntentos intentos fallidos.")
            registrarEvento(cleanUser, usuario.rol, "BLOQUEO_POR_INTENTOS", "CERRADO")
            onResult(
                false,
                null,
                "Contraseña incorrecta. Cuenta BLOQUEADA tras $MAX_INTENTOS intentos fallidos."
            )
        } else {
            val restantes = MAX_INTENTOS - nuevosIntentos
            Log.w(TAG, "AUTH_FAIL: Contraseña incorrecta para '$cleanUser'. Intentos restantes: $restantes.")
            onResult(false, null, "Contraseña incorrecta. Te quedan $restantes intento(s).")
        }
    }

    override fun cambiarEstadoPuerta(
        abierta: Boolean,
        usuario: Usuario,
        onComplete: ((success: Boolean, message: String) -> Unit)?
    ) {
        val estadoReed = if (abierta) "ABIERTO" else "CERRADO"
        val accion = if (abierta) "APERTURA_PUERTA" else "CIERRE_PUERTA"

        val nuevoEstado = EstadoPuerta(
            abierta = abierta,
            sensorReed = estadoReed,
            buzzerActivo = abierta,
            ultimoUsuario = usuario.username,
            ultimaActualizacion = System.currentTimeMillis()
        )

        doorDoc.set(nuevoEstado)
            .addOnSuccessListener {
                Log.i(TAG, "IOT_CMD: Comando '$accion' ejecutado por '${usuario.username}'. Sensor Reed: $estadoReed.")
                registrarEvento(usuario.username, usuario.rol, accion, estadoReed)
                onComplete?.invoke(true, if (abierta) "Puerta abierta" else "Puerta cerrada")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "IOT_CMD_ERROR: Fallo al ejecutar '$accion': ${e.message}", e)
                onComplete?.invoke(false, "No se pudo actualizar la puerta: ${e.localizedMessage ?: "error de red"}")
            }
    }

    override fun observarEstadoPuerta(
        onError: (String) -> Unit,
        onUpdate: (estado: EstadoPuerta, isFromCache: Boolean) -> Unit
    ): () -> Unit {
        val registration = doorDoc.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "DOOR_LISTENER_ERROR: Error observando estado de puerta: ${error.message}", error)
                onError("Error al sincronizar estado de la puerta.")
                return@addSnapshotListener
            }
            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
            val estado = snapshot.toObject(EstadoPuerta::class.java) ?: return@addSnapshotListener
            onUpdate(estado, snapshot.metadata.isFromCache)
        }
        return { registration.remove() }
    }

    override fun observarBitacoraUsuario(
        username: String,
        onError: (String) -> Unit,
        onUpdate: (List<RegistroAcceso>) -> Unit
    ): () -> Unit {
        // Filtrado por igualdad simple para evitar índice compuesto en Firestore y no descargar registros ajenos.
        val registration = logsCol
            .whereEqualTo("username", username)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "USER_LOGS_ERROR: Error observando bitácora propia: ${error.message}", error)
                    onError("No se pudieron cargar tus registros de acceso.")
                    return@addSnapshotListener
                }
                if (snapshots == null) return@addSnapshotListener
                val propios = AccessRolePolicy.filterOwnAccessRecords(
                    records = snapshots.toObjects(RegistroAcceso::class.java),
                    username = username
                )
                onUpdate(propios)
            }
        return { registration.remove() }
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
            .addOnFailureListener { e ->
                Log.e(TAG, "AUDIT_LOG_ERROR: No se pudo registrar '$accion' para '$username': ${e.message}", e)
            }
    }
}
