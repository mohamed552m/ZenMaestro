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
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.zenmaestro.app.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth
    private var authRequestRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        styleSystemBars()
        auth = FirebaseAuth.getInstance()

        binding.openSignupButton.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }

        binding.loginButton.setOnClickListener { signIn() }

        binding.forgotPassword.setOnClickListener { showComingSoon() }
        binding.googleButton.setOnClickListener { showComingSoon() }
    }

    private fun showComingSoon() {
        Toast.makeText(this, R.string.coming_soon, Toast.LENGTH_SHORT).show()
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
        binding.loginButton.isEnabled = !loading
        binding.loginButton.alpha = if (loading) 0.65f else 1f
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
            is FirebaseAuthInvalidUserException -> getString(R.string.auth_user_not_found)
            is FirebaseAuthInvalidCredentialsException -> getString(R.string.auth_invalid_credentials)
            is FirebaseNetworkException -> getString(R.string.auth_network_error)
            is FirebaseTooManyRequestsException -> getString(R.string.auth_too_many_requests)
            else -> getString(R.string.auth_login_failed)
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
