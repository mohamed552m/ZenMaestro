package com.zenmaestro.app

import com.google.android.gms.tasks.Tasks
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseUser

data class BackendAuthTokens(
    val idToken: String,
    val appCheckToken: String
)

object BackendAuthTokenProvider {

    fun get(user: FirebaseUser, forceRefresh: Boolean): BackendAuthTokens {
        val idToken = Tasks.await(user.getIdToken(forceRefresh)).token.orEmpty()
        val appCheckToken = Tasks.await(
            FirebaseAppCheck.getInstance().getAppCheckToken(forceRefresh)
        ).token.orEmpty()
        check(idToken.isNotBlank()) { "Firebase ID token is unavailable" }
        check(appCheckToken.isNotBlank()) { "Firebase App Check token is unavailable" }
        return BackendAuthTokens(idToken, appCheckToken)
    }
}
