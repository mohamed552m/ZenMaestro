package com.zenmaestro.app

import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.zenmaestro.app.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginFragment : Fragment() {

    private var _binding: ActivityLoginBinding? = null
    private val binding get() = _binding!!
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val credentialManager by lazy { CredentialManager.create(requireContext()) }
    private var authRequestRunning = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.openSignupButton.setOnClickListener {
            (requireActivity() as AppNavigator).showSignup()
        }
        binding.loginButton.setOnClickListener { signIn() }
        binding.forgotPassword.setOnClickListener {
            (requireActivity() as AppNavigator).showForgotPassword(
                binding.emailInput.text?.toString()?.trim()
            )
        }
        binding.googleButton.setOnClickListener { signInWithGoogle() }
    }

    private fun signInWithGoogle() {
        if (authRequestRunning) return
        val serverClientId = googleWebClientId()
        if (serverClientId == null) {
            Toast.makeText(
                requireContext(),
                R.string.google_sign_in_setup_needed,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            setAuthLoading(loading = true, googleRequest = true)
            try {
                val googleOption = GetSignInWithGoogleOption.Builder(serverClientId).build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleOption)
                    .build()
                val result = credentialManager.getCredential(requireContext(), request)
                val credential = result.credential
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    authenticateGoogleToken(googleCredential.idToken)
                } else {
                    setAuthLoading(false)
                    showGoogleSignInError()
                }
            } catch (_: GetCredentialCancellationException) {
                setAuthLoading(false)
            } catch (_: Exception) {
                setAuthLoading(false)
                showGoogleSignInError()
            }
        }
    }

    private fun authenticateGoogleToken(idToken: String) {
        val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(firebaseCredential).addOnCompleteListener { task ->
            if (!isAdded || _binding == null) return@addOnCompleteListener
            setAuthLoading(false)
            if (task.isSuccessful) {
                (requireActivity() as AppNavigator).showMainTab(MainTab.HOME)
            } else {
                showAuthError(task.exception)
            }
        }
    }

    private fun googleWebClientId(): String? {
        val resourceId = resources.getIdentifier(
            "default_web_client_id",
            "string",
            requireContext().packageName
        )
        return resourceId.takeIf { it != 0 }
            ?.let(::getString)
            ?.takeIf { it.isNotBlank() }
    }

    private fun showGoogleSignInError() {
        Toast.makeText(requireContext(), R.string.google_sign_in_failed, Toast.LENGTH_LONG).show()
    }

    private fun signIn() {
        if (authRequestRunning) return

        val email = binding.emailInput.text?.toString()?.trim().orEmpty()
        val password = binding.passwordInput.text?.toString().orEmpty()
        binding.emailInput.error = null
        binding.passwordInput.error = null

        when {
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                binding.emailInput.error = getString(R.string.auth_valid_email)
            }
            password.length < 6 -> {
                binding.passwordInput.error = getString(R.string.auth_password_length)
            }
            else -> {
                setAuthLoading(true)
                auth.signInWithEmailAndPassword(email, password)
                    .addOnCompleteListener { task ->
                        if (!isAdded || _binding == null) return@addOnCompleteListener
                        setAuthLoading(false)
                        if (task.isSuccessful) {
                            (requireActivity() as AppNavigator).showMainTab(MainTab.HOME)
                        } else {
                            showAuthError(task.exception)
                        }
                    }
            }
        }
    }

    private fun setAuthLoading(loading: Boolean, googleRequest: Boolean = false) {
        authRequestRunning = loading
        binding.loginButton.isEnabled = !loading
        binding.loginButton.alpha = if (loading) 0.65f else 1f
        binding.googleButton.isEnabled = !loading
        binding.googleButton.alpha = if (loading) 0.65f else 1f
        binding.googleButton.setText(
            if (loading && googleRequest) R.string.signing_in else R.string.continue_google
        )
    }

    private fun showAuthError(error: Exception?) {
        val message = when (error) {
            is FirebaseAuthInvalidUserException -> getString(R.string.auth_user_not_found)
            is FirebaseAuthInvalidCredentialsException -> getString(R.string.auth_invalid_credentials)
            is FirebaseNetworkException -> getString(R.string.auth_network_error)
            is FirebaseTooManyRequestsException -> getString(R.string.auth_too_many_requests)
            else -> getString(R.string.auth_login_failed)
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
