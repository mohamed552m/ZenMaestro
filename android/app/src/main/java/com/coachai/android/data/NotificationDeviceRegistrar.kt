package com.coachai.android.data

import android.content.Context
import com.coachai.android.BuildConfig
import com.coachai.android.data.remote.AuthTokenProvider
import com.coachai.android.data.remote.NotificationDeviceApi
import com.coachai.android.data.remote.NotificationDeviceRemoteResult
import com.coachai.android.data.remote.withTokenRefreshRetry
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

class NotificationDeviceRegistrar(
    context: Context,
    private val tokens: AuthTokenProvider,
    private val api: NotificationDeviceApi,
) {
    private val preferences = context.getSharedPreferences("notification_device", Context.MODE_PRIVATE)
    private val deviceId: String
        get() = preferences.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    suspend fun registerCurrentToken(): Boolean {
        val pushToken = try {
            firebasePushToken()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        return registerPushToken(pushToken)
    }

    suspend fun registerPushToken(pushToken: String): Boolean {
        if (pushToken.isBlank()) return false
        val locale = if (Locale.getDefault().language == "ar") "ar" else "en"
        val idempotencyKey = "device-register-${UUID.randomUUID()}"
        val result = try {
            tokens.withTokenRefreshRetry(
                isUnauthorized = { it == NotificationDeviceRemoteResult.Unauthorized },
                request = { idToken ->
                    api.register(
                        idToken = idToken,
                        deviceId = deviceId,
                        pushToken = pushToken,
                        appVersion = BuildConfig.VERSION_NAME,
                        locale = locale,
                        timezone = ZoneId.systemDefault().id,
                        idempotencyKey = idempotencyKey,
                    )
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        return result == NotificationDeviceRemoteResult.Success
    }

    suspend fun unregister(): Boolean {
        val idempotencyKey = "device-invalidate-${UUID.randomUUID()}"
        val result = try {
            tokens.withTokenRefreshRetry(
                isUnauthorized = { it == NotificationDeviceRemoteResult.Unauthorized },
                request = { idToken ->
                    api.invalidate(
                        idToken = idToken,
                        deviceId = deviceId,
                        idempotencyKey = idempotencyKey,
                    )
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        return result == NotificationDeviceRemoteResult.Success
    }

    private suspend fun firebasePushToken(): String {
        return suspendCancellableCoroutine { continuation ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!continuation.isActive) return@addOnCompleteListener
                when {
                    task.isSuccessful && !task.result.isNullOrBlank() -> continuation.resume(task.result)
                    task.exception != null -> continuation.resumeWithException(task.exception!!)
                    else -> continuation.resumeWithException(IllegalStateException("FCM token was unavailable"))
                }
            }
        }
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
    }
}
