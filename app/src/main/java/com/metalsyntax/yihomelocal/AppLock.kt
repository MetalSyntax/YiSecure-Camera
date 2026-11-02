package com.metalsyntax.yihomelocal

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Bloqueo de la app con huella/rostro o, si no hay, el PIN/patrón del teléfono (BiometricPrompt). */
object AppLock {
    private const val AUTH = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** Tiempo en segundo plano tras el que se vuelve a pedir (el diálogo de PIN del sistema también manda la app atrás). */
    const val RELOCK_MS = 30_000L

    fun available(ctx: Context) = BiometricManager.from(ctx).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(act: FragmentActivity, onOk: () -> Unit, onError: (String) -> Unit) {
        BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) = onOk()
            override fun onAuthenticationError(code: Int, msg: CharSequence) = onError(msg.toString())
        }).authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle("YiSecure").setSubtitle("Desbloquea para ver tus cámaras")
                .setAllowedAuthenticators(AUTH).build(),
        )
    }
}
