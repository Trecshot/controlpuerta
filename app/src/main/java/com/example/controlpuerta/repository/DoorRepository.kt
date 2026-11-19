package com.example.controlpuerta.repository

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.controlpuerta.model.EstadoPuerta
import com.example.controlpuerta.model.RegistroAcceso
import com.example.controlpuerta.model.Usuario
import com.example.controlpuerta.security.AccessRolePolicy
import com.example.controlpuerta.security.LoginAttemptPolicy
import com.example.controlpuerta.security.SecurityUtils
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
        sensorReedConfirmado: String? = null,
        onComplete: ((success: Boolean, message: String) -> Unit)? = null
    )

    /**
     * Protocolo Fail-Safe / Fail-Secure: ante corte de energía, Bluetooth o red, la cerradura
     * vuelve a su estado seguro por defecto (CERRADA y bloqueada: `abierta = false`,
     * `sensorReed = "CERRADO"`, `buzzerActivo = false`) y se registra el evento en la bitácora.
     */
    fun aplicarBloqueoFailSafe(
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

    /**
     * Vigila la cuenta en tiempo real y notifica cuando es bloqueada, eliminada o cambia de rol.
     */
    fun observarSesionUsuario(
        username: String,
        expectedRol: String,
        onRevoked: (message: String) -> Unit
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

    private val cryptoExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        protected const val TAG: String = "ControlPuertaIoT"
        const val MAX_INTENTOS: Int = LoginAttemptPolicy.MAX_ATTEMPTS
        const val MSG_BAD_CREDENTIALS: String = "Usuario o contraseña incorrectos."
        const val MSG_NETWORK_AUTH: String = "Error de red al validar credenciales."
        const val ACCION_CIERRE_FAIL_SAFE: String = "CIERRE_FAIL_SAFE"
        private const val BITACORA_QUERY_LIMIT: Long = 50
    }

    /**
     * Ejecuta trabajo costoso (KDF) fuera del hilo principal y devuelve el resultado en Main.
     */
    protected fun <T> runCrypto(work: () -> T, onDone: (T) -> Unit, onError: (Throwable) -> Unit) {
        cryptoExecutor.execute {
            try {
                val result = work()
                mainHandler.post { onDone(result) }
            } catch (t: Throwable) {
                mainHandler.post { onError(t) }
            }
        }
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

    private data class AuthComputation(val valid: Boolean, val upgrade: com.example.controlpuerta.security.DerivedCredential?)

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
        // Allowlist estricta: evita rutas de documento inválidas (crash) y consultas anómalas.
        if (!SecurityUtils.isValidUsername(cleanUser) || passwordInput.length > SecurityUtils.MAX_PASSWORD_LENGTH) {
            Log.w(TAG, "AUTH_FAIL: Credenciales con formato inválido rechazadas localmente.")
            onResult(false, null, MSG_BAD_CREDENTIALS)
            return
        }

        // Source.SERVER: la autenticación jamás debe apoyarse en datos obsoletos de la caché local.
        usersCol.document(cleanUser).get(Source.SERVER)
            .addOnSuccessListener { snapshot ->
                if (!snapshot.exists()) {
                    Log.w(TAG, "AUTH_FAIL: Usuario inexistente.")
                    onResult(false, null, MSG_BAD_CREDENTIALS)
                    return@addOnSuccessListener
                }

                val usuario = snapshot.toObject(Usuario::class.java)
                if (usuario == null) {
                    Log.e(TAG, "AUTH_PARSE_ERROR: Documento inválido para '$cleanUser'.")
                    onResult(false, null, "No se pudo leer el perfil del usuario.")
                    return@addOnSuccessListener
                }

                if (usuario.bloqueado) {
                    Log.w(TAG, "AUTH_BLOCKED: Acceso denegado. La cuenta '$cleanUser' está bloqueada.")
                    onResult(
                        false,
                        null,
                        "Cuenta bloqueada por exceder $MAX_INTENTOS intentos. Contacte al Administrador."
                    )
                    return@addOnSuccessListener
                }

                runCrypto(
                    work = {
                        val valid = SecurityUtils.verifyPassword(passwordInput, usuario)
                        val upgrade = if (valid && SecurityUtils.isLegacyHash(usuario)) {
                            SecurityUtils.deriveCredential(passwordInput)
                        } else {
                            null
                        }
                        AuthComputation(valid, upgrade)
                    },
                    onDone = { computed -> completarAutenticacion(cleanUser, usuario, computed, onResult) },
                    onError = { t ->
                        Log.e(TAG, "AUTH_CRYPTO_ERROR: ${t.message}", t)
                        onResult(false, null, "No se pudo validar la contraseña en este dispositivo.")
                    }
                )
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "NETWORK_ERROR: Error de conexión con Firestore: ${e.message}", e)
                onResult(false, null, MSG_NETWORK_AUTH)
            }
    }

    private fun completarAutenticacion(
        cleanUser: String,
        usuario: Usuario,
        computed: AuthComputation,
        onResult: (success: Boolean, usuario: Usuario?, mensaje: String) -> Unit
    ) {
        if (!computed.valid) {
            registrarIntentoFallido(cleanUser, usuario.rol, onResult)
            return
        }

        if (!AccessRolePolicy.isRoleAllowedForClient(usuario.rol, allowAdminLogin)) {
            Log.w(TAG, "AUTH_ROLE_DENIED: Acceso bloqueado para '$cleanUser' [Rol: ${usuario.rol}] en cliente móvil.")
            onResult(false, null, AccessRolePolicy.deniedMessageForRole(usuario.rol))
            return
        }

        val updates = mutableMapOf<String, Any>()
        if (usuario.intentosFallidos != 0) updates["intentosFallidos"] = 0
        computed.upgrade?.let { cred ->
            updates["passwordHash"] = cred.hash
            updates["passwordSalt"] = cred.salt
            updates["hashIterations"] = cred.iterations
        }
        if (updates.isNotEmpty()) {
            usersCol.document(cleanUser).update(updates)
                .addOnSuccessListener {
                    if (computed.upgrade != null) {
                        Log.i(TAG, "AUTH_HASH_UPGRADED: Credencial de '$cleanUser' migrada a PBKDF2 con sal.")
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "AUTH_WARN: No se pudo actualizar credenciales de '$cleanUser': ${e.message}", e)
                }
        }
        Log.i(TAG, "AUTH_OK: Sesión iniciada correctamente por '$cleanUser' [Rol: ${usuario.rol}].")
        // Nunca se conservan hash/sal en memoria tras autenticar.
        onResult(
            true,
            usuario.copy(passwordHash = "", passwordSalt = "", hashIterations = 0, intentosFallidos = 0),
            "Bienvenido, ${usuario.username}"
        )
    }

    override fun verificarSesionActiva(
        username: String,
        expectedRol: String,
        onResult: (restoredUser: Usuario?, errorMessage: String?, shouldClearLocalSession: Boolean) -> Unit
    ) {
        val cleanUser = username.trim()
        if (!SecurityUtils.isValidUsername(cleanUser) ||
            !AccessRolePolicy.isRoleAllowedForClient(expectedRol, allowAdminLogin)
        ) {
            onResult(null, AccessRolePolicy.deniedMessageForRole(expectedRol), true)
            return
        }

        usersCol.document(cleanUser).get(Source.SERVER)
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
                    Log.w(TAG, "SESSION_REVOKED_ROLE: Rol '${usuario.rol}' bloqueado para '$cleanUser'.")
                    onResult(null, AccessRolePolicy.deniedMessageForRole(usuario.rol), true)
                    return@addOnSuccessListener
                }

                if (usuario.rol != expectedRol) {
                    Log.w(TAG, "SESSION_REVOKED_ROLE_CHANGE: El rol de '$cleanUser' cambió de '$expectedRol' a '${usuario.rol}'.")
                    onResult(null, "Tus permisos cambiaron. Inicia sesión nuevamente.", true)
                    return@addOnSuccessListener
                }

                Log.i(TAG, "SESSION_RESTORED: Sesión persistente restaurada para '$cleanUser' [Rol: ${usuario.rol}].")
                onResult(usuario.copy(passwordHash = "", passwordSalt = "", hashIterations = 0), null, false)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "SESSION_VERIFY_ERROR: Error de red al verificar sesión de '$cleanUser': ${e.message}", e)
                onResult(null, "No se pudo verificar tu sesión en el servidor. Comprueba tu red e inicia sesión.", false)
            }
    }

    override fun observarSesionUsuario(
        username: String,
        expectedRol: String,
        onRevoked: (message: String) -> Unit
    ): () -> Unit {
        if (!SecurityUtils.isValidUsername(username)) return { }
        val registration = usersCol.document(username).addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener
            if (!snapshot.exists()) {
                // Un documento ausente en caché no prueba su eliminación; solo se revoca con dato de servidor.
                if (!snapshot.metadata.isFromCache) {
                    onRevoked("Tu cuenta ya no está disponible. Inicia sesión nuevamente.")
                }
                return@addSnapshotListener
            }
            val usuario = snapshot.toObject(Usuario::class.java) ?: return@addSnapshotListener
            when {
                usuario.bloqueado -> onRevoked("Cuenta bloqueada. Contacte al Administrador.")
                !AccessRolePolicy.isRoleAllowedForClient(usuario.rol, allowAdminLogin) ->
                    onRevoked(AccessRolePolicy.deniedMessageForRole(usuario.rol))
                usuario.rol != expectedRol -> onRevoked("Tus permisos cambiaron. Inicia sesión nuevamente.")
            }
        }
        return { registration.remove() }
    }

    private data class AttemptResult(val outcome: LoginAttemptPolicy.Outcome, val newlyBlocked: Boolean)

    /**
     * Incrementa el contador dentro de una transacción atómica (sin lectura-modificación-escritura
     * en el cliente) y solo informa a la UI cuando el servidor confirmó el resultado.
     */
    private fun registrarIntentoFallido(
        cleanUser: String,
        rol: String,
        onResult: (success: Boolean, usuario: Usuario?, mensaje: String) -> Unit
    ) {
        val ref = usersCol.document(cleanUser)
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            val wasBlocked = snap.getBoolean("bloqueado") == true
            val outcome = LoginAttemptPolicy.nextOutcome(
                currentAttempts = (snap.getLong("intentosFallidos") ?: 0L).toInt(),
                alreadyBlocked = wasBlocked
            )
            if (snap.exists() && !wasBlocked) {
                val updates = mutableMapOf<String, Any>(
                    "intentosFallidos" to outcome.attempts,
                    "bloqueado" to outcome.blocked
                )
                if (!snap.contains("passwordSalt")) updates["passwordSalt"] = ""
                if (!snap.contains("hashIterations")) updates["hashIterations"] = 0
                tx.update(ref, updates)
            }
            AttemptResult(outcome, newlyBlocked = outcome.blocked && !wasBlocked)
        }.addOnSuccessListener { result ->
            if (result.newlyBlocked) {
                Log.w(TAG, "SECURITY_LOCKOUT: Usuario '$cleanUser' BLOQUEADO tras $MAX_INTENTOS intentos fallidos.")
                registrarEvento(cleanUser, rol, "BLOQUEO_POR_INTENTOS", AccessRolePolicy.SENSOR_CERRADO)
            }
            if (result.outcome.blocked) {
                onResult(false, null, "Contraseña incorrecta. Cuenta BLOQUEADA tras $MAX_INTENTOS intentos fallidos.")
            } else {
                Log.w(TAG, "AUTH_FAIL: Contraseña incorrecta para '$cleanUser'. Intentos restantes: ${result.outcome.remaining}.")
                onResult(false, null, "Contraseña incorrecta. Te quedan ${result.outcome.remaining} intento(s).")
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "AUTH_LOCK_UPDATE_ERROR: No se pudo registrar intento de '$cleanUser': ${e.message}", e)
            onResult(false, null, MSG_NETWORK_AUTH)
        }
    }

    /**
     * Estado de la puerta y evento de auditoría se confirman en un único lote atómico:
     * jamás queda un cambio de estado sin su registro en bitácora (ni viceversa).
     */
    override fun cambiarEstadoPuerta(
        abierta: Boolean,
        usuario: Usuario,
        sensorReedConfirmado: String?,
        onComplete: ((success: Boolean, message: String) -> Unit)?
    ) {
        val estadoReed = DoorLinkPolicy.resolveReedState(abierta, sensorReedConfirmado)
        val accion = if (abierta) "APERTURA_PUERTA" else "CIERRE_PUERTA"
        val now = System.currentTimeMillis()

        val nuevoEstado = EstadoPuerta(
            abierta = abierta,
            sensorReed = estadoReed,
            buzzerActivo = abierta,
            ultimoUsuario = usuario.username,
            ultimaActualizacion = now
        )
        val logRef = logsCol.document()
        val registro = RegistroAcceso(
            id = logRef.id,
            username = usuario.username,
            rol = usuario.rol,
            accion = accion,
            sensorReed = estadoReed,
            timestamp = now
        )

        db.batch()
            .set(doorDoc, nuevoEstado)
            .set(logRef, registro)
            .commit()
            .addOnSuccessListener {
                Log.i(TAG, "IOT_CMD: Comando '$accion' ejecutado por '${usuario.username}'. Sensor Reed: $estadoReed.")
                onComplete?.invoke(true, if (abierta) "Puerta abierta" else "Puerta cerrada")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "IOT_CMD_ERROR: Fallo al ejecutar '$accion': ${e.message}", e)
                onComplete?.invoke(false, "No se pudo actualizar la puerta: ${e.localizedMessage ?: "error de red"}")
            }
    }

    override fun aplicarBloqueoFailSafe(
        usuario: Usuario,
        onComplete: ((success: Boolean, message: String) -> Unit)?
    ) {
        val now = System.currentTimeMillis()
        val estadoSeguro = EstadoPuerta(
            abierta = false,
            sensorReed = AccessRolePolicy.SENSOR_CERRADO,
            buzzerActivo = false,
            ultimoUsuario = usuario.username,
            ultimaActualizacion = now
        )
        val logRef = logsCol.document()
        val registro = RegistroAcceso(
            id = logRef.id,
            username = usuario.username,
            rol = usuario.rol,
            accion = ACCION_CIERRE_FAIL_SAFE,
            sensorReed = AccessRolePolicy.SENSOR_CERRADO,
            timestamp = now
        )
        db.batch()
            .set(doorDoc, estadoSeguro)
            .set(logRef, registro)
            .commit()
            .addOnSuccessListener {
                Log.w(TAG, "FAIL_SAFE_LOCK: Puerta cerrada y bloqueada por seguridad (${usuario.username}).")
                onComplete?.invoke(true, "Fail-Safe activo: Puerta cerrada y bloqueada.")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "FAIL_SAFE_AUDIT_ERROR: No se pudo sincronizar cierre Fail-Safe: ${e.message}", e)
                onComplete?.invoke(false, "Fail-Safe local activo (pendiente de sincronizar con servidor).")
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

    /**
     * Consulta acotada y ordenada en servidor (índice compuesto `username` + `timestamp DESC`,
     * ver `firestore.indexes.json`). Si el índice aún no fue desplegado, degrada de forma segura
     * a una consulta por igualdad con filtrado local para no dejar la bitácora inutilizable.
     */
    override fun observarBitacoraUsuario(
        username: String,
        onError: (String) -> Unit,
        onUpdate: (List<RegistroAcceso>) -> Unit
    ): () -> Unit {
        var registration: com.google.firebase.firestore.ListenerRegistration? = null
        var removed = false

        fun deliver(snapshots: com.google.firebase.firestore.QuerySnapshot) {
            onUpdate(
                AccessRolePolicy.filterOwnAccessRecords(
                    records = snapshots.toObjects(RegistroAcceso::class.java),
                    username = username
                )
            )
        }

        fun listenFallback() {
            if (removed) return
            Log.w(TAG, "USER_LOGS_INDEX_MISSING: Falta índice (username, timestamp). Usando consulta degradada.")
            registration = logsCol
                .whereEqualTo("username", username)
                .limit(BITACORA_QUERY_LIMIT)
                .addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.e(TAG, "USER_LOGS_ERROR: ${error.message}", error)
                        onError("No se pudieron cargar tus registros de acceso.")
                        return@addSnapshotListener
                    }
                    if (snapshots != null) deliver(snapshots)
                }
        }

        registration = logsCol
            .whereEqualTo("username", username)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(BITACORA_QUERY_LIMIT)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    registration?.remove()
                    if (error.code == FirebaseFirestoreException.Code.FAILED_PRECONDITION) {
                        listenFallback()
                    } else {
                        Log.e(TAG, "USER_LOGS_ERROR: Error observando bitácora propia: ${error.message}", error)
                        onError("No se pudieron cargar tus registros de acceso.")
                    }
                    return@addSnapshotListener
                }
                if (snapshots != null) deliver(snapshots)
            }
        return {
            removed = true
            registration?.remove()
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
            .addOnFailureListener { e ->
                Log.e(TAG, "AUDIT_LOG_ERROR: No se pudo registrar '$accion' para '$username': ${e.message}", e)
            }
    }
}
