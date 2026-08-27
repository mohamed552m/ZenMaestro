package com.zenmaestro.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.firebase.auth.FirebaseAuth
import com.zenmaestro.app.databinding.ActivitySplashBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val handler = Handler(Looper.getMainLooper())
    private val openDestination = Runnable {
        val destination = if (FirebaseAuth.getInstance().currentUser != null) {
            HomeActivity::class.java
        } else {
            LoginActivity::class.java
        }
        startActivity(Intent(this, destination))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.statusBarColor = ContextCompat.getColor(this, R.color.chalk_white)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.chalk_white)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        binding.splashImage.alpha = 0f
        binding.splashImage.animate()
            .alpha(1f)
            .setDuration(280L)
            .start()

        handler.postDelayed(openDestination, 3500L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(openDestination)
        super.onDestroy()
    }
}
