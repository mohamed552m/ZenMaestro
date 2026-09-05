package com.zenmaestro.app

import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.UserProfileChangeRequest
import com.zenmaestro.app.databinding.ActivitySignupBinding

class SignupFragment : Fragment() {

    private var _binding: ActivitySignupBinding? = null
    private val binding get() = _binding!!
    private val auth by lazy { FirebaseAuth.getInstance() }
    private var authRequestRunning = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivitySignupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.backButton.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        binding.openLoginButton.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        binding.signupButton.setOnClickListener { createAccount() }
    }

    private fun createAccount() {
        if (authRequestRunning) return

        val name = binding.nameInput.text?.toString()?.trim().orEmpty()
        val email = binding.emailInput.text?.toString()?.trim().orEmpty()
        val password = binding.passwordInput.text?.toString().orEmpty()
        val confirmPassword = binding.confirmPasswordInput.text?.toString().orEmpty()
        binding.nameInput.error = null
        binding.emailInput.error = null
        binding.passwordInput.error = null
        binding.confirmPasswordInput.error = null

        when {
            name.length < 2 -> binding.nameInput.error = getString(R.string.auth_full_name)
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                binding.emailInput.error = getString(R.string.auth_valid_email)
            }
            password.length < 6 -> binding.passwordInput.error = getString(R.string.auth_password_length)
            password != confirmPassword -> binding.confirmPasswordInput.error = getString(R.string.auth_passwords_match)
            !binding.termsCheckbox.isChecked -> {
                Toast.makeText(requireContext(), R.string.auth_accept_terms, Toast.LENGTH_SHORT).show()
            }
            else -> {
                setAuthLoading(true)
                auth.createUserWithEmailAndPassword(email, password)
                    .addOnCompleteListener { task ->
                        if (!isAdded || _binding == null) return@addOnCompleteListener
                        setAuthLoading(false)
                        if (task.isSuccessful) {
                            val profile = UserProfileChangeRequest.Builder()
                                .setDisplayName(name)
                                .build()
                            auth.currentUser?.updateProfile(profile)?.addOnCompleteListener {
                                if (isAdded) (requireActivity() as AppNavigator).showMainTab(MainTab.HOME)
                            } ?: (requireActivity() as AppNavigator).showMainTab(MainTab.HOME)
                        } else {
                            showAuthError(task.exception)
                        }
                    }
            }
        }
    }

    private fun setAuthLoading(loading: Boolean) {
        authRequestRunning = loading
        binding.signupButton.isEnabled = !loading
        binding.signupButton.alpha = if (loading) 0.65f else 1f
    }

    private fun showAuthError(error: Exception?) {
        val message = when (error) {
            is FirebaseAuthUserCollisionException -> getString(R.string.auth_account_exists)
            is FirebaseAuthWeakPasswordException -> getString(R.string.auth_weak_password)
            is FirebaseAuthInvalidCredentialsException -> getString(R.string.auth_invalid_signup_data)
            is FirebaseNetworkException -> getString(R.string.auth_network_error)
            is FirebaseTooManyRequestsException -> getString(R.string.auth_too_many_requests)
            else -> getString(R.string.auth_signup_failed)
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
