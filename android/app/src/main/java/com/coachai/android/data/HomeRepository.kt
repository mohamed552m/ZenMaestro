package com.coachai.android.data

import com.coachai.android.data.remote.AuthTokenProvider
import com.coachai.android.data.remote.HomeRemoteDataSource
import com.coachai.android.data.remote.HomeRemoteResult
import com.coachai.android.data.remote.withTokenRefreshRetry
import com.coachai.android.domain.HomeSnapshot
import com.coachai.android.domain.InsightState
import com.coachai.android.domain.TodayPlanState
import com.coachai.android.domain.UnavailableReason
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

interface HomeRepository {
    suspend fun load(date: LocalDate): HomeSnapshot
}

class RealHomeRepository(
    private val apiBaseUrl: String,
    private val authTokenProvider: AuthTokenProvider,
    private val homeApi: HomeRemoteDataSource,
) : HomeRepository {
    override suspend fun load(date: LocalDate): HomeSnapshot {
        if (apiBaseUrl.isBlank()) {
            return unavailable(date, UnavailableReason.API_NOT_CONFIGURED)
        }
        val result = try {
            authTokenProvider.withTokenRefreshRetry(
                isUnauthorized = { it == HomeRemoteResult.Unauthorized },
                request = homeApi::loadToday,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return unavailable(date, UnavailableReason.NETWORK_ERROR)
        }
            ?: return unavailable(date, UnavailableReason.AUTHENTICATION_REQUIRED)

        return when (result) {
            is HomeRemoteResult.Success -> {
                val remote = result.snapshot
                val planState = when {
                    remote.plan == null -> TodayPlanState.NoPlan
                    remote.plan.tasks.isEmpty() -> TodayPlanState.EmptyPlan
                    else -> TodayPlanState.Available(
                        tasks = remote.plan.tasks,
                        progress = remote.plan.progress,
                    )
                }
                HomeSnapshot(
                    localDate = remote.date,
                    displayName = remote.displayName,
                    planState = planState,
                    streakDays = null,
                    insightState = InsightState.Unavailable,
                    planId = remote.plan?.planId,
                )
            }
            HomeRemoteResult.Unauthorized -> unavailable(
                date,
                UnavailableReason.AUTHENTICATION_REQUIRED,
            )
            HomeRemoteResult.Forbidden -> unavailable(
                date,
                UnavailableReason.AUTHENTICATION_REQUIRED,
            )
            HomeRemoteResult.NetworkError -> unavailable(date, UnavailableReason.NETWORK_ERROR)
            HomeRemoteResult.InvalidResponse -> unavailable(date, UnavailableReason.INVALID_RESPONSE)
        }
    }

    private fun unavailable(date: LocalDate, reason: UnavailableReason): HomeSnapshot =
        HomeSnapshot(
            localDate = date,
            displayName = null,
            planState = TodayPlanState.Unavailable(reason),
            streakDays = null,
            insightState = InsightState.Unavailable,
        )
}

