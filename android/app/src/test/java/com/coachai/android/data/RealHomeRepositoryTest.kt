package com.coachai.android.data

import com.coachai.android.data.remote.AuthTokenProvider
import com.coachai.android.data.remote.HomeRemoteDataSource
import com.coachai.android.data.remote.HomeRemoteResult
import com.coachai.android.data.remote.RemoteHomeSnapshot
import com.coachai.android.data.remote.RemoteTodayPlan
import com.coachai.android.domain.HomeTask
import com.coachai.android.domain.ProgressSummary
import com.coachai.android.domain.TaskStatus
import com.coachai.android.domain.TodayPlanState
import com.coachai.android.domain.UnavailableReason
import com.coachai.android.domain.progressFor
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RealHomeRepositoryTest {
    private val requestedDate = LocalDate.of(2026, 10, 15)

    @Test
    fun blankApiConfigurationIsReportedWithoutCallingRemote() = runTest {
        var calls = 0
        val result = repository(
            baseUrl = "",
            token = "token",
            result = HomeRemoteResult.NetworkError,
            onCall = { calls++ },
        ).load(requestedDate)

        assertEquals(
            UnavailableReason.API_NOT_CONFIGURED,
            (result.planState as TodayPlanState.Unavailable).reason,
        )
        assertEquals(0, calls)
        assertNull(result.displayName)
    }

    @Test
    fun missingAuthenticationIsReportedWithoutCallingRemote() = runTest {
        var calls = 0
        val result = repository(
            baseUrl = "https://api.example.test",
            token = null,
            result = HomeRemoteResult.NetworkError,
            onCall = { calls++ },
        ).load(requestedDate)

        assertEquals(
            UnavailableReason.AUTHENTICATION_REQUIRED,
            (result.planState as TodayPlanState.Unavailable).reason,
        )
        assertEquals(0, calls)
    }

    @Test
    fun authenticatedHomeMapsServerDateNameTasksAndAggregateProgress() = runTest {
        val serverDate = LocalDate.of(2026, 10, 16)
        val progress = ProgressSummary(7, 1, 3, 1, 12)
        val tasks = listOf(
            HomeTask(
                id = 9,
                title = "Deep work",
                scheduledStart = "09:00:00",
                estimatedMinutes = 45,
                priority = 4,
                status = TaskStatus.IN_PROGRESS,
                isFixedTime = false,
            ),
        )
        val result = repository(
            baseUrl = "https://api.example.test",
            token = "verified-token",
            result = HomeRemoteResult.Success(
                RemoteHomeSnapshot(
                    date = serverDate,
                    displayName = "Mona",
                    plan = RemoteTodayPlan(tasks, progress),
                ),
            ),
        ).load(requestedDate)

        assertEquals(serverDate, result.localDate)
        assertEquals("Mona", result.displayName)
        assertEquals(tasks, (result.planState as TodayPlanState.Available).tasks)
        assertEquals(progress, progressFor(result.planState))
    }

    @Test
    fun noPlanAndEmptyPlanRemainDifferentStates() = runTest {
        val noPlan = repository(
            baseUrl = "https://api.example.test",
            token = "token",
            result = HomeRemoteResult.Success(
                RemoteHomeSnapshot(requestedDate, "User", null),
            ),
        ).load(requestedDate)
        val emptyPlan = repository(
            baseUrl = "https://api.example.test",
            token = "token",
            result = HomeRemoteResult.Success(
                RemoteHomeSnapshot(
                    requestedDate,
                    "User",
                    RemoteTodayPlan(
                        emptyList(),
                        ProgressSummary(0, 0, 0, 0, 0),
                    ),
                ),
            ),
        ).load(requestedDate)

        assertEquals(TodayPlanState.NoPlan, noPlan.planState)
        assertEquals(TodayPlanState.EmptyPlan, emptyPlan.planState)
    }

    @Test
    fun remoteFailuresMapToSafeUiStates() = runTest {
        val cases = listOf(
            HomeRemoteResult.Unauthorized to UnavailableReason.AUTHENTICATION_REQUIRED,
            HomeRemoteResult.Forbidden to UnavailableReason.AUTHENTICATION_REQUIRED,
            HomeRemoteResult.NetworkError to UnavailableReason.NETWORK_ERROR,
            HomeRemoteResult.InvalidResponse to UnavailableReason.INVALID_RESPONSE,
        )

        cases.forEach { (remoteResult, expected) ->
            val snapshot = repository(
                baseUrl = "https://api.example.test",
                token = "token",
                result = remoteResult,
            ).load(requestedDate)
            assertEquals(
                expected,
                (snapshot.planState as TodayPlanState.Unavailable).reason,
            )
        }
    }

    @Test
    fun unauthorizedResponseRefreshesTokenAndRetriesOnce() = runTest {
        val provider = RefreshingTokenProvider("stale-token", "fresh-token")
        val requestedTokens = mutableListOf<String>()
        val success = HomeRemoteResult.Success(
            RemoteHomeSnapshot(requestedDate, "Mona", null),
        )
        val repository = RealHomeRepository(
            apiBaseUrl = "https://api.example.test",
            authTokenProvider = provider,
            homeApi = HomeRemoteDataSource { token ->
                requestedTokens += token
                if (token == "stale-token") HomeRemoteResult.Unauthorized else success
            },
        )

        val result = repository.load(requestedDate)

        assertEquals(TodayPlanState.NoPlan, result.planState)
        assertEquals(listOf("stale-token", "fresh-token"), requestedTokens)
        assertEquals(1, provider.refreshCalls)
    }

    @Test
    fun forbiddenResponseDoesNotRefreshOrRetry() = runTest {
        val provider = RefreshingTokenProvider("token", "unused-token")
        var remoteCalls = 0
        val repository = RealHomeRepository(
            apiBaseUrl = "https://api.example.test",
            authTokenProvider = provider,
            homeApi = HomeRemoteDataSource {
                remoteCalls += 1
                HomeRemoteResult.Forbidden
            },
        )

        val result = repository.load(requestedDate)

        assertEquals(
            UnavailableReason.AUTHENTICATION_REQUIRED,
            (result.planState as TodayPlanState.Unavailable).reason,
        )
        assertEquals(1, remoteCalls)
        assertEquals(0, provider.refreshCalls)
    }

    private fun repository(
        baseUrl: String,
        token: String?,
        result: HomeRemoteResult,
        onCall: () -> Unit = {},
    ): RealHomeRepository = RealHomeRepository(
        apiBaseUrl = baseUrl,
        authTokenProvider = AuthTokenProvider { token },
        homeApi = HomeRemoteDataSource {
            onCall()
            result
        },
    )

    private class RefreshingTokenProvider(
        private val initialToken: String,
        private val refreshedToken: String,
    ) : AuthTokenProvider {
        var refreshCalls: Int = 0
            private set

        override suspend fun firebaseIdToken(): String = initialToken

        override suspend fun refreshFirebaseIdToken(rejectedToken: String): String {
            refreshCalls += 1
            return refreshedToken
        }
    }
}

