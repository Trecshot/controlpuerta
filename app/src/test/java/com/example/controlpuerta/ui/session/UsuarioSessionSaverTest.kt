package com.example.controlpuerta.ui.session

import androidx.compose.runtime.saveable.SaverScope
import com.example.controlpuerta.model.Usuario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsuarioSessionSaverTest {

    private val alwaysAllowScope = SaverScope { true }

    @Test
    fun savesAndRestoresActiveUserWhileStrippingSensitiveCredentials() {
        val original = Usuario(
            username = "operador1",
            passwordHash = "secreto_hash_pbkdf2",
            passwordSalt = "secreto_salt_hex",
            hashIterations = 120_000,
            rol = "USUARIO",
            intentosFallidos = 1,
            bloqueado = false
        )

        val saved = with(UsuarioSessionSaver) { alwaysAllowScope.save(original) }
        assertNotNull(saved)
        val savedList = saved as List<*>
        // Nunca debe serializar hash, salt ni iteraciones en el Bundle de Android.
        assertEquals(4, savedList.size)
        assertTrue(savedList.none { it.toString().contains("secreto") })

        val restored = UsuarioSessionSaver.restore(saved)
        assertNotNull(restored)
        assertEquals("operador1", restored?.username)
        assertEquals("USUARIO", restored?.rol)
        assertEquals(1, restored?.intentosFallidos)
        assertEquals(false, restored?.bloqueado)
        assertEquals("", restored?.passwordHash)
        assertEquals("", restored?.passwordSalt)
        assertEquals(0, restored?.hashIterations)
    }

    @Test
    fun savesAndRestoresNullUserSafely() {
        // listSaver devuelve null cuando save() entrega emptyList(), evitando escribir entradas vacías en el Bundle.
        val saved = with(UsuarioSessionSaver) { alwaysAllowScope.save(null) }
        assertNull(saved)
        assertNull(UsuarioSessionSaver.restore(emptyList<Any>()))
    }

    @Test
    fun corruptedOrMalformedBundleReturnsNullWithoutThrowing() {
        assertNull(UsuarioSessionSaver.restore(emptyList<Any>()))
        assertNull(UsuarioSessionSaver.restore(listOf("operador1", "USUARIO")))
        // Tipos alterados en el Bundle: no debe lanzar ClassCastException.
        assertNull(UsuarioSessionSaver.restore(listOf(123, "USUARIO", 0, false)))
        assertNull(UsuarioSessionSaver.restore(listOf("operador1", "USUARIO", "no_es_int", false)))
        assertNull(UsuarioSessionSaver.restore(listOf("operador1", "USUARIO", 0, "no_es_bool")))
    }
}
