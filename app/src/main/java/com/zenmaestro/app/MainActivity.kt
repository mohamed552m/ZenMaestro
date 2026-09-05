package com.zenmaestro.app

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.zenmaestro.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), AppNavigator {

    private lateinit var binding: ActivityMainBinding
    private var selectedMainTab: MainTab? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        NotificationCoordinator.createChannel(this)
        setupBottomNavigation()
        setupBackNavigation()
        observeVisibleFragment()

        if (savedInstanceState == null) {
            replaceWholeFlow(SplashFragment(), TAG_SPLASH)
        } else {
            syncChrome(supportFragmentManager.primaryNavigationFragment)
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { itemId ->
            MainTab.fromNavItem(itemId)?.let(::showMainTab)
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    supportFragmentManager.backStackEntryCount > 0 -> {
                        supportFragmentManager.popBackStack()
                    }
                    selectedMainTab != null && selectedMainTab != MainTab.HOME -> {
                        showMainTab(MainTab.HOME)
                    }
                    else -> finish()
                }
            }
        })
    }

    private fun observeVisibleFragment() {
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentResumed(fragmentManager: FragmentManager, fragment: Fragment) {
                    syncChrome(fragment)
                }
            },
            false
        )
    }

    override fun showLogin() {
        replaceWholeFlow(LoginFragment(), TAG_LOGIN)
    }

    override fun showSignup() {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out,
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
            .replace(R.id.fragmentContainer, SignupFragment(), TAG_SIGNUP)
            .setPrimaryNavigationFragment(null)
            .addToBackStack(TAG_SIGNUP)
            .commit()
    }

    override fun showForgotPassword(email: String?) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out,
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
            .replace(
                R.id.fragmentContainer,
                ForgotPasswordFragment.newInstance(email),
                TAG_FORGOT_PASSWORD
            )
            .addToBackStack(TAG_FORGOT_PASSWORD)
            .commit()
    }

    override fun showMainTab(tab: MainTab) {
        supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        val current = supportFragmentManager.primaryNavigationFragment
        val existing = supportFragmentManager.findFragmentByTag(tab.fragmentTag)
        val target = existing ?: createMainFragment(tab)

        if (current === target && target.isVisible) {
            selectedMainTab = tab
            syncChrome(target)
            return
        }

        val transaction = supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)

        supportFragmentManager.fragments
            .filter { it.isAdded && it !== target }
            .forEach { fragment ->
                transaction.hide(fragment)
                transaction.setMaxLifecycle(fragment, Lifecycle.State.STARTED)
            }

        if (target.isAdded) {
            transaction.show(target)
        } else {
            transaction.add(R.id.fragmentContainer, target, tab.fragmentTag)
        }

        transaction
            .setMaxLifecycle(target, Lifecycle.State.RESUMED)
            .setPrimaryNavigationFragment(target)
            .commit()
        selectedMainTab = tab
        syncChrome(target)
    }

    override fun openFocus(taskId: String) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out,
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
            .replace(R.id.fragmentContainer, FocusFragment.newInstance(taskId), TAG_FOCUS)
            .addToBackStack(TAG_FOCUS)
            .commit()
    }

    override fun closeFocus() {
        supportFragmentManager.popBackStack()
    }

    override fun signOut() {
        NotificationCoordinator.cancelAll(this)
        FirebaseAuth.getInstance().signOut()
        lifecycleScope.launch {
            runCatching {
                CredentialManager.create(this@MainActivity)
                    .clearCredentialState(ClearCredentialStateRequest())
            }
        }
        showLogin()
    }

    private fun replaceWholeFlow(fragment: Fragment, tag: String) {
        supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        selectedMainTab = null
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.fragmentContainer, fragment, tag)
            .setPrimaryNavigationFragment(fragment)
            .commit()
        syncChrome(fragment)
    }

    private fun createMainFragment(tab: MainTab): Fragment = when (tab) {
        MainTab.HOME -> HomeFragment()
        MainTab.PLAN -> PlanFragment()
        MainTab.COACH -> CoachFragment()
        MainTab.PROGRESS -> ProgressFragment()
        MainTab.PROFILE -> ProfileFragment()
    }

    private fun syncChrome(fragment: Fragment?) {
        val tab = when (fragment) {
            is HomeFragment -> MainTab.HOME
            is PlanFragment -> MainTab.PLAN
            is CoachFragment -> MainTab.COACH
            is ProgressFragment -> MainTab.PROGRESS
            is ProfileFragment -> MainTab.PROFILE
            else -> null
        }
        selectedMainTab = tab
        binding.bottomNavigation.visibility = if (tab == null) View.GONE else View.VISIBLE
        tab?.let { binding.bottomNavigation.setSelectedItem(it.navItemId) }

        val darkSystemBar = fragment is LoginFragment ||
            fragment is SignupFragment ||
            fragment is ForgotPasswordFragment ||
            fragment is FocusFragment
        val barColor = if (darkSystemBar) R.color.chalkboard else R.color.chalk_white
        window.statusBarColor = ContextCompat.getColor(this, barColor)
        window.navigationBarColor = ContextCompat.getColor(
            this,
            if (fragment is FocusFragment) R.color.chalkboard else R.color.chalk_white
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkSystemBar
            isAppearanceLightNavigationBars = fragment !is FocusFragment
        }
    }

    companion object {
        private const val TAG_SPLASH = "flow_splash"
        private const val TAG_LOGIN = "flow_login"
        private const val TAG_SIGNUP = "flow_signup"
        private const val TAG_FORGOT_PASSWORD = "flow_forgot_password"
        private const val TAG_FOCUS = "flow_focus"
    }
}
