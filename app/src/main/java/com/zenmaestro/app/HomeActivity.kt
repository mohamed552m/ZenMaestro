package com.zenmaestro.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.zenmaestro.app.databinding.ActivityHomeBinding

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        styleSystemBars()

        binding.bottomNavigation.selectedItemId = R.id.nav_home
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.nav_home) {
                true
            } else {
                showComingSoon()
                false
            }
        }

        binding.notificationButton.setOnClickListener { showComingSoon() }
        binding.streakCard.setOnClickListener { showComingSoon() }
        binding.continueButton.setOnClickListener { showComingSoon() }
        binding.askZenButton.setOnClickListener { showComingSoon() }
        binding.startTaskButton.setOnClickListener { showComingSoon() }
        binding.viewAllButton.setOnClickListener { showComingSoon() }
    }

    private fun showComingSoon() {
        Toast.makeText(this, R.string.coming_soon, Toast.LENGTH_SHORT).show()
    }

    private fun styleSystemBars() {
        window.statusBarColor = ContextCompat.getColor(this, R.color.chalk_white)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.paper_white)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }
}
