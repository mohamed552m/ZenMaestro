package com.zenmaestro.app

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.zenmaestro.app.databinding.ActivitySignupBinding

class SignupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignupBinding
    private lateinit var auth: FirebaseAuth
    private var authRequestRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        styleSystemBars()
        auth = FirebaseAuth.getInstance()

        binding.backButton.setOnClickListener { finish() }
        binding.openLoginButton.setOnClickListener { finish() }
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
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> binding.emailInput.error = getString(R.string.auth_valid_email)
            password.length < 6 -> binding.passwordInput.error = getString(R.string.auth_password_length)
            password != confirmPassword -> binding.confirmPasswordInput.error = getString(R.string.auth_passwords_match)
            !binding.termsCheckbox.isChecked -> Toast.makeText(this, R.string.auth_accept_terms, Toast.LENGTH_SHORT).show()
            else -> {
                setAuthLoading(true)
                auth.createUserWithEmailAndPassword(email, password)
                    .addOnCompleteListener(this) { task ->
                        setAuthLoading(false)
                        if (task.isSuccessful) {
                            openHome()
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

    private fun openHome() {
        val intent = Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
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
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun styleSystemBars() {
        window.statusBarColor = ContextCompat.getColor(this, R.color.chalkboard)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.chalk_white)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }
    }
}
