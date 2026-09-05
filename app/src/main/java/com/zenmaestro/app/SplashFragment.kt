package com.zenmaestro.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import com.zenmaestro.app.databinding.ActivitySplashBinding

class SplashFragment : Fragment() {

    private var _binding: ActivitySplashBinding? = null
    private val binding get() = _binding!!
    private val handler = Handler(Looper.getMainLooper())
    private val openNextScreen = Runnable {
        if (!isAdded) return@Runnable
        val navigator = requireActivity() as AppNavigator
        if (FirebaseAuth.getInstance().currentUser == null) {
            navigator.showLogin()
        } else {
            navigator.showMainTab(MainTab.HOME)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivitySplashBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.splashImage.alpha = 0f
        binding.splashImage.animate().alpha(1f).setDuration(280L).start()
        handler.postDelayed(openNextScreen, SPLASH_DURATION_MS)
    }

    override fun onDestroyView() {
        handler.removeCallbacks(openNextScreen)
        binding.splashImage.animate().cancel()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val SPLASH_DURATION_MS = 3500L
    }
}
