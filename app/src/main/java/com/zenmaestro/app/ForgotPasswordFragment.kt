package com.zenmaestro.app

import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.zenmaestro.app.databinding.FragmentForgotPasswordBinding

class ForgotPasswordFragment : Fragment() {

    private var _binding: FragmentForgotPasswordBinding? = null
    private val binding get() = _binding!!
    private val auth by lazy { FirebaseAuth.getInstance() }
    private var requestRunning = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentForgotPasswordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.emailInput.setText(arguments?.getString(ARG_EMAIL).orEmpty())
        binding.backButton.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        binding.backToLoginButton.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        binding.sendResetButton.setOnClickListener { sendResetEmail() }
    }

    private fun sendResetEmail() {
        if (requestRunning) return
        val email = binding.emailInput.text?.toString()?.trim().orEmpty()
        binding.emailLayout.error = null
        binding.statusCard.visibility = View.GONE

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailLayout.error = getString(R.string.auth_valid_email)
            return
        }

        setLoading(true)
        auth.sendPasswordResetEmail(email).addOnCompleteListener { task ->
            if (!isAdded || _binding == null) return@addOnCompleteListener
            setLoading(false)
            if (task.isSuccessful) {
                binding.statusTitle.setText(R.string.reset_email_sent_title)
                binding.statusMessage.text = getString(R.string.reset_email_sent_message, email)
                binding.statusCard.visibility = View.VISIBLE
                binding.sendResetButton.setText(R.string.send_again)
            } else {
                binding.statusTitle.setText(R.string.reset_email_failed_title)
                binding.statusMessage.setText(
                    when (task.exception) {
                        is FirebaseNetworkException -> R.string.auth_network_error
                        is FirebaseTooManyRequestsException -> R.string.auth_too_many_requests
                        else -> R.string.reset_email_failed_message
                    }
                )
                binding.statusCard.visibility = View.VISIBLE
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        requestRunning = loading
        binding.sendResetButton.isEnabled = !loading
        binding.sendResetButton.alpha = if (loading) 0.65f else 1f
        binding.sendResetButton.setText(
            if (loading) R.string.sending_reset_link else R.string.send_reset_link
        )
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_EMAIL = "email"

        fun newInstance(email: String?): ForgotPasswordFragment = ForgotPasswordFragment().apply {
            arguments = Bundle().apply { putString(ARG_EMAIL, email.orEmpty()) }
        }
    }
}
