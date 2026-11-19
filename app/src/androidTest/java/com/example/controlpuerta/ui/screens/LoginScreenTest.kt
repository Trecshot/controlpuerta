package com.example.controlpuerta.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.controlpuerta.model.Usuario
import com.example.controlpuerta.repository.DoorAuthRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Repositorio falso: registra las llamadas y responde de forma controlada (sin Firebase). */
private class FakeAuthRepository(
    private val result: Triple<Boolean, Usuario?, String>
) : DoorAuthRepository {
    var authCalls = 0
    var lastUser: String? = null

    override fun autenticar(
        usernameInput: String,
        passwordInput: String,
        onResult: (success: Boolean, usuario: Usuario?, mensaje: String) -> Unit
    ) {
        authCalls++
        lastUser = usernameInput
        onResult(result.first, result.second, result.third)
    }

    override fun verificarSesionActiva(
        username: String,
        expectedRol: String,
        onResult: (restoredUser: Usuario?, errorMessage: String?, shouldClearLocalSession: Boolean) -> Unit
    ) = onResult(null, null, false)
}

@RunWith(AndroidJUnit4::class)
class LoginScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyFieldsShowLocalErrorWithoutCallingRepository() {
        val repo = FakeAuthRepository(Triple(false, null, "no debería usarse"))
        composeRule.setContent { LoginScreen(repository = repo, onLoginSuccess = {}) }

        composeRule.onNodeWithText("Iniciar Sesión").performClick()

        composeRule.onNodeWithText("Debe ingresar usuario y contraseña.").assertIsDisplayed()
        assertEquals(0, repo.authCalls)
    }

    @Test
    fun failedLoginShowsRepositoryMessage() {
        val repo = FakeAuthRepository(Triple(false, null, "Usuario o contraseña incorrectos."))
        composeRule.setContent { LoginScreen(repository = repo, onLoginSuccess = {}) }

        composeRule.onNodeWithText("Usuario").performTextInput("operador1")
        composeRule.onNodeWithText("Contraseña").performTextInput("Incorrecta#1")
        composeRule.onNodeWithText("Iniciar Sesión").performClick()

        composeRule.onNodeWithText("Usuario o contraseña incorrectos.").assertIsDisplayed()
        assertEquals(1, repo.authCalls)
    }

    @Test
    fun successfulLoginInvokesCallbackWithUser() {
        val user = Usuario(username = "operador1", rol = "USUARIO")
        val repo = FakeAuthRepository(Triple(true, user, "Bienvenido"))
        var received: Usuario? = null
        composeRule.setContent { LoginScreen(repository = repo, onLoginSuccess = { received = it }) }

        composeRule.onNodeWithText("Usuario").performTextInput("operador1")
        composeRule.onNodeWithText("Contraseña").performTextInput("PuertaSegura#9")
        composeRule.onNodeWithText("Iniciar Sesión").performClick()

        composeRule.waitForIdle()
        assertNotNull(received)
        assertEquals("operador1", received?.username)
    }
}
