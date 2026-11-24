package com.coachai.android.data

import android.content.Context
import com.coachai.android.data.remote.AuthTokenProvider
import com.coachai.android.data.remote.NotificationPreferences
import com.coachai.android.data.remote.NotificationPreferencesRemoteDataSource
import com.coachai.android.data.remote.NotificationRemoteResult
import com.coachai.android.data.remote.withTokenRefreshRetry
import kotlinx.coroutines.CancellationException

enum class NotificationFailureReason {
    AUTHENTICATION_REQUIRED,
    NETWORK_ERROR,
    INVALID_RESPONSE,
}

class NotificationRepositoryException(val reason: NotificationFailureReason) : Exception(reason.name)

interface NotificationPreferencesRepository {
    suspend fun load(): Result<NotificationPreferences>
    suspend fun update(changes: Map<String, Any?>, idempotencyKey: String): Result<NotificationPreferences>
}

/**
 * The delivery preferences also have to be available to FirebaseMessagingService, which can be
 * started without any screen or ViewModel. This small local mirror is updated only after the
 * server confirms a load/update, so it never claims an optimistic value that failed to save.
 */
data class NotificationPresentationSettings(
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
)

interface NotificationPresentationStore {
    fun read(): NotificationPresentationSettings
    fun save(preferences: NotificationPreferences)
}

class SharedPreferencesNotificationPresentationStore(context: Context) : NotificationPresentationStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read() = NotificationPresentationSettings(
        soundEnabled = preferences.getBoolean(KEY_SOUND, true),
        vibrationEnabled = preferences.getBoolean(KEY_VIBRATION, true),
    )

    override fun save(preferences: NotificationPreferences) {
        this.preferences.edit()
            .putBoolean(KEY_SOUND, preferences.soundEnabled)
            .putBoolean(KEY_VIBRATION, preferences.vibrationEnabled)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "notification_presentation"
        const val KEY_SOUND = "sound_enabled"
        const val KEY_VIBRATION = "vibration_enabled"
    }
}

class RealNotificationPreferencesRepository(
    private val authTokenProvider: AuthTokenProvider,
    private val api: NotificationPreferencesRemoteDataSource,
    private val presentationStore: NotificationPresentationStore? = null,
) : NotificationPreferencesRepository {
    override suspend fun load(): Result<NotificationPreferences> = withToken(api::load)

    override suspend fun update(
        changes: Map<String, Any?>,
        idempotencyKey: String,
    ): Result<NotificationPreferences> = withToken { api.update(it, changes, idempotencyKey) }

    private suspend fun withToken(
        block: suspend (String) -> NotificationRemoteResult,
    ): Result<NotificationPreferences> {
        val result = try {
            authTokenProvider.withTokenRefreshRetry(
                isUnauthorized = { it == NotificationRemoteResult.Unauthorized },
                request = block,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure(NotificationFailureReason.NETWORK_ERROR)
        }
        if (result == null) return failure(NotificationFailureReason.AUTHENTICATION_REQUIRED)
        return when (result) {
            is NotificationRemoteResult.Success -> {
                presentationStore?.save(result.preferences)
                Result.success(result.preferences)
            }
            NotificationRemoteResult.Unauthorized -> failure(NotificationFailureReason.AUTHENTICATION_REQUIRED)
            NotificationRemoteResult.Forbidden -> failure(NotificationFailureReason.AUTHENTICATION_REQUIRED)
            NotificationRemoteResult.NetworkError -> failure(NotificationFailureReason.NETWORK_ERROR)
            NotificationRemoteResult.InvalidResponse -> failure(NotificationFailureReason.INVALID_RESPONSE)
        }
    }

    private fun failure(reason: NotificationFailureReason): Result<NotificationPreferences> =
        Result.failure(NotificationRepositoryException(reason))
}
