package com.zenmaestro.app

import androidx.annotation.IdRes

enum class MainTab(@param:IdRes val navItemId: Int, val fragmentTag: String) {
    HOME(R.id.nav_home, "main_home"),
    PLAN(R.id.nav_plan, "main_plan"),
    COACH(R.id.nav_coach, "main_coach"),
    PROGRESS(R.id.nav_progress, "main_progress"),
    PROFILE(R.id.nav_profile, "main_profile");

    companion object {
        fun fromNavItem(@IdRes itemId: Int): MainTab? = entries.firstOrNull {
            it.navItemId == itemId
        }
    }
}

interface AppNavigator {
    fun showLogin()
    fun showSignup()
    fun showForgotPassword(email: String? = null)
    fun showMainTab(tab: MainTab)
    fun openFocus(taskId: String)
    fun closeFocus()
    fun signOut()
}
