package com.coachai.android.ui.auth

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coachai.android.BuildConfig
import com.google.firebase.auth.ActionCodeSettings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class PasswordResetLinkState { None, Verifying, Ready, Invalid, Complete }

data class AuthUiState(
    val signedIn: Boolean = false,
    val userId: String? = null,
    val loading: Boolean = true,
    val submitting: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val passwordResetLinkState: PasswordResetLinkState = PasswordResetLinkState.None,
    val passwordResetCode: String? = null,
    val passwordResetEmail: String? = null,
)

class AuthViewModel(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : ViewModel() {
    private var completingRegistration = false
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()
    private val listener = FirebaseAuth.AuthStateListener { firebase ->
        _uiState.value = _uiState.value.copy(
            signedIn = firebase.currentUser != null && !completingRegistration,
            userId = firebase.currentUser?.uid,
            loading = false,
            submitting = if (completingRegistration) true else false,
            error = null,
        )
    }

    init {
        auth.addAuthStateListener(listener)
        viewModelScope.launch {
            delay(4_000)
            if (_uiState.value.loading) {
                _uiState.value = _uiState.value.copy(
                    signedIn = auth.currentUser != null,
                    userId = auth.currentUser?.uid,
                    loading = false,
                )
            }
        }
    }

    fun signIn(email: String, password: String) {
        val cleanEmail = email.trim()
        if (!valid(cleanEmail, password)) return
        completingRegistration = false
        submit()
        auth.signInWithEmailAndPassword(cleanEmail, password).addOnCompleteListener { task ->
            if (!task.isSuccessful) fail(task.exception)
        }
    }

    fun register(name: String, email: String, password: String) {
        val cleanEmail = email.trim()
        if (name.trim().length < 2) {
            _uiState.value = _uiState.value.copy(error = "Enter your name.")
            return
        }
        if (!valid(cleanEmail, password)) return
        completingRegistration = true
        submit()
        auth.createUserWithEmailAndPassword(cleanEmail, password).addOnCompleteListener { task ->
            val user = task.result?.user
            if (!task.isSuccessful || user == null) {
                fail(task.exception)
                return@addOnCompleteListener
            }
            user.updateProfile(
                UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build(),
            ).addOnCompleteListener { profileTask ->
                user.getIdToken(true).addOnCompleteListener {
                    completingRegistration = false
                    _uiState.value = _uiState.value.copy(
                        signedIn = auth.currentUser != null,
                        submitting = false,
                        error = null,
                        message = if (profileTask.isSuccessful) null
                        else "Your account was created. You can update your name from Profile.",
                    )
                }
            }
        }
    }

    fun resetPassword(email: String) {
        val cleanEmail = email.trim()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
            _uiState.value = _uiState.value.copy(error = "Enter a valid email address.")
            return
        }
        submit()
        val settings = ActionCodeSettings.newBuilder()
            .setUrl("https://${BuildConfig.FIREBASE_AUTH_LINK_HOST}/auth/reset-complete")
            .setHandleCodeInApp(true)
            .setAndroidPackageName(BuildConfig.APPLICATION_ID, false, null)
            .build()
        auth.sendPasswordResetEmail(cleanEmail, settings).addOnCompleteListener { task ->
            _uiState.value = if (task.isSuccessful) {
                _uiState.value.copy(
                    submitting = false,
                    message = "Reset link sent. Open it from this phone to continue in Zen Maestro.",
                    error = null,
                )
            } else {
                _uiState.value.copy(submitting = false, error = safeMessage(task.exception))
            }
        }
    }

    fun handlePasswordResetLink(uri: Uri?) {
        val actionUri = findPasswordResetUri(uri) ?: return
        val code = actionUri.getQueryParameter("oobCode")?.takeIf { it.isNotBlank() } ?: return
        _uiState.value = _uiState.value.copy(
            passwordResetLinkState = PasswordResetLinkState.Verifying,
            passwordResetCode = code,
            passwordResetEmail = null,
            submitting = false,
            error = null,
            message = null,
        )
        auth.verifyPasswordResetCode(code).addOnCompleteListener { task ->
            _uiState.value = if (task.isSuccessful) {
                _uiState.value.copy(
                    passwordResetLinkState = PasswordResetLinkState.Ready,
                    passwordResetEmail = task.result,
                    error = null,
                )
            } else {
                _uiState.value.copy(
                    passwordResetLinkState = PasswordResetLinkState.Invalid,
                    passwordResetCode = null,
                    passwordResetEmail = null,
                    error = "This reset link is invalid or has expired. Request a new one.",
                )
            }
        }
    }

    fun confirmPasswordReset(password: String, confirmation: String) {
        val code = _uiState.value.passwordResetCode
        when {
            code.isNullOrBlank() -> reportError("Request a new password reset link.")
            password.length < 8 -> reportError("Password must be at least 8 characters.")
            password != confirmation -> reportError("Passwords don't match.")
            else -> {
                submit()
                auth.confirmPasswordReset(code, password).addOnCompleteListener { task ->
                    _uiState.value = if (task.isSuccessful) {
                        _uiState.value.copy(
                            submitting = false,
                            passwordResetLinkState = PasswordResetLinkState.Complete,
                            passwordResetCode = null,
                            message = "Password changed successfully. You can log in now.",
                            error = null,
                        )
                    } else {
                        _uiState.value.copy(
                            submitting = false,
                            passwordResetLinkState = PasswordResetLinkState.Invalid,
                            passwordResetCode = null,
                            error = "This reset link is invalid or has expired. Request a new one.",
                        )
                    }
                }
            }
        }
    }

    fun closePasswordReset() {
        _uiState.value = _uiState.value.copy(
            passwordResetLinkState = PasswordResetLinkState.None,
            passwordResetCode = null,
            passwordResetEmail = null,
            submitting = false,
            error = null,
            message = null,
        )
    }

    fun signInWithGoogle(idToken: String) {
        if (idToken.isBlank()) {
            reportError("Google sign-in couldn't be completed. Try again.")
            return
        }
        completingRegistration = false
        submit()
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential).addOnCompleteListener { task ->
            if (!task.isSuccessful) fail(task.exception)
        }
    }

    fun reportError(message: String) {
        _uiState.value = _uiState.value.copy(
            submitting = false,
            error = message,
            message = null,
        )
    }

    fun signOut() { auth.signOut() }
    fun clearFeedback() { _uiState.value = _uiState.value.copy(error = null, message = null) }

    private fun valid(email: String, password: String): Boolean {
        val error = when {
            !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> "Enter a valid email address."
            password.length < 8 -> "Password must be at least 8 characters."
            else -> null
        }
        if (error != null) _uiState.value = _uiState.value.copy(error = error)
        return error == null
    }

    private fun submit() { _uiState.value = _uiState.value.copy(submitting = true, error = null, message = null) }
    private fun fail(error: Throwable?) {
        completingRegistration = false
        _uiState.value = _uiState.value.copy(submitting = false, error = safeMessage(error))
    }
    private fun safeMessage(error: Throwable?): String = when ((error as? com.google.firebase.auth.FirebaseAuthException)?.errorCode) {
        "ERROR_INVALID_CREDENTIAL", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND" -> "Email or password is incorrect."
        "ERROR_EMAIL_ALREADY_IN_USE" -> "An account already exists with this email."
        "ERROR_NETWORK_REQUEST_FAILED" -> "Check your internet connection and try again."
        "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Try again later."
        else -> "Authentication couldn't be completed. Try again."
    }

    private fun findPasswordResetUri(uri: Uri?): Uri? {
        if (uri == null) return null
        val candidates = buildList {
            add(uri)
            listOf("link", "deep_link_id", "continueUrl").forEach { parameter ->
                uri.getQueryParameter(parameter)?.let { encoded ->
                    runCatching { Uri.parse(encoded) }.getOrNull()?.let(::add)
                }
            }
        }
        return candidates.firstOrNull { candidate ->
            candidate.getQueryParameter("mode") == "resetPassword" &&
                !candidate.getQueryParameter("oobCode").isNullOrBlank()
        }
    }

    override fun onCleared() {
        auth.removeAuthStateListener(listener)
        super.onCleared()
    }
}
