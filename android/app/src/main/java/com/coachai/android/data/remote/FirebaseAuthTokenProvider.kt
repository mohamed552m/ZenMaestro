package com.coachai.android.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseUser
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Supplies the backend with the current Firebase user's ID token.
 * Firebase refreshes an expired token when forceRefresh is false.
 */
class FirebaseAuthTokenProvider(
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance(),
) : AuthTokenProvider {
    private val refreshMutex = Mutex()
    private var refreshedUid: String? = null
    private var lastToken: String? = null

    override suspend fun firebaseIdToken(): String? = refreshMutex.withLock {
        val currentUser = firebaseAuth.currentUser ?: run {
            clearCachedToken()
            return@withLock null
        }
        lastToken
            ?.takeIf { refreshedUid == currentUser.uid }
            ?.let { return@withLock it }
        val forceRefresh = refreshedUid != currentUser.uid
        requestToken(currentUser, forceRefresh)
    }

    override suspend fun refreshFirebaseIdToken(rejectedToken: String): String? =
        refreshMutex.withLock {
            val currentUser = firebaseAuth.currentUser ?: run {
                clearCachedToken()
                return@withLock null
            }

            // Another request may already have refreshed this exact rejected token
            // while this coroutine was waiting for the mutex.
            lastToken
                ?.takeIf { refreshedUid == currentUser.uid && it != rejectedToken }
                ?.let { return@withLock it }

            requestToken(currentUser, forceRefresh = true)
        }

    override suspend fun invalidateSession(rejectedToken: String) = refreshMutex.withLock {
        val currentUser = firebaseAuth.currentUser ?: run {
            clearCachedToken()
            return@withLock
        }

        // A late 401 from an older request must not sign out a newer login or a
        // request that already obtained a newer Firebase token.
        if (refreshedUid != currentUser.uid || lastToken != rejectedToken) {
            return@withLock
        }

        val invalidFirebaseUser = isInvalidFirebaseUser(currentUser)
        clearCachedToken()

        // A backend 401 can be caused by temporary certificate/provider state.
        // Only Firebase itself may confirm that the account must be signed out.
        if (invalidFirebaseUser && firebaseAuth.currentUser?.uid == currentUser.uid) {
            firebaseAuth.signOut()
        }
    }

    private suspend fun isInvalidFirebaseUser(currentUser: FirebaseUser): Boolean =
        withTimeoutOrNull(FIREBASE_TASK_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                currentUser.reload().addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    continuation.resume(task.exception is FirebaseAuthInvalidUserException)
                }
            }
        } ?: false

    private suspend fun requestToken(
        currentUser: FirebaseUser,
        forceRefresh: Boolean,
    ): String? {
        val token = withTimeout(FIREBASE_TASK_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                currentUser.getIdToken(forceRefresh).addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener

                    val error = task.exception
                    when {
                        task.isSuccessful -> continuation.resume(task.result?.token)
                        error != null -> continuation.resumeWithException(error)
                        else -> continuation.resumeWithException(
                            IllegalStateException("Firebase did not return an ID token"),
                        )
                    }
                }
            }
        }
        if (!token.isNullOrBlank()) {
            refreshedUid = currentUser.uid
            lastToken = token
        }
        return token
    }

    private fun clearCachedToken() {
        refreshedUid = null
        lastToken = null
    }

    private companion object {
        const val FIREBASE_TASK_TIMEOUT_MILLIS = 15_000L
    }
}
