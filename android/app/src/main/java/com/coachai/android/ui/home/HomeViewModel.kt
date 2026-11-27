package com.coachai.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.coachai.android.data.AnalyticsRepository
import com.coachai.android.data.HomeRepository
import com.coachai.android.data.quote.DailyQuote
import com.coachai.android.data.quote.DailyQuoteSelector
import com.coachai.android.domain.HomeSnapshot
import com.coachai.android.domain.InsightState
import com.coachai.android.domain.TodayPlanState
import com.coachai.android.domain.UnavailableReason
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import org.json.JSONObject

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Ready(
        val snapshot: HomeSnapshot,
        val quote: DailyQuote,
        val localTime: LocalTime,
    ) : HomeUiState
}

class HomeViewModel(
    private val repository: HomeRepository,
    private val analyticsRepository: AnalyticsRepository,
    private val quoteSelector: DailyQuoteSelector,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        val previous = _uiState.value as? HomeUiState.Ready
        // Keep a confirmed snapshot visible while refreshing. Loading is only
        // shown before the first successful response, avoiding a blank/flicker.
        if (_uiState.value !is HomeUiState.Ready) {
            _uiState.value = HomeUiState.Loading
        }
        refreshJob = viewModelScope.launch {
            val date = LocalDate.now(clock)
            val quote = quoteSelector.select(date)
            val snapshot = try {
                coroutineScope {
                    val homeRequest = async { repository.load(date) }
                    val analyticsRequest = async { analyticsRepository.loadProfile(windowDays = 30) }
                    val home = homeRequest.await()
                    analyticsRequest.await().getOrNull()?.let { home.withAnalytics(it) } ?: home
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                HomeSnapshot(
                    localDate = date,
                    displayName = null,
                    planState = TodayPlanState.Unavailable(UnavailableReason.NETWORK_ERROR),
                    streakDays = null,
                    insightState = InsightState.Unavailable,
                )
            }
            val transientNetworkFailure =
                (snapshot.planState as? TodayPlanState.Unavailable)?.reason ==
                    UnavailableReason.NETWORK_ERROR
            _uiState.value = if (
                transientNetworkFailure &&
                previous != null &&
                previous.snapshot.localDate == date
            ) {
                previous.copy(localTime = LocalTime.now(clock))
            } else {
                HomeUiState.Ready(
                    snapshot = snapshot,
                    quote = quote,
                    localTime = LocalTime.now(clock),
                )
            }
        }
    }

    class Factory(
        private val repository: HomeRepository,
        private val analyticsRepository: AnalyticsRepository,
        private val quoteSelector: DailyQuoteSelector,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HomeViewModel::class.java))
            return HomeViewModel(repository, analyticsRepository, quoteSelector) as T
        }
    }
}

private fun HomeSnapshot.withAnalytics(payload: String): HomeSnapshot = runCatching {
    val root = JSONObject(payload)
    val sampleSize = root.optInt("sample_size", root.optInt("total_tasks", 0)).coerceAtLeast(0)
    val consistency = root.optJSONObject("consistency")
    val streak = consistency?.optInt("current_streak", 0)?.takeIf { it > 0 }
    val insights = root.optJSONObject("insights")?.optJSONArray("insights")
    val firstInsight = insights?.optJSONObject(0)
    val observation = firstInsight?.optString("observation")?.trim().orEmpty()
    val evidence = firstInsight?.optString("evidence")?.trim().orEmpty()
    val insight = when {
        observation.isNotEmpty() -> InsightState.Available(
            title = observation,
            evidence = evidence.ifEmpty { "Based on your latest $sampleSize recorded tasks." },
        )
        sampleSize < 5 -> InsightState.OnboardingSuggestion(
            title = "Your first insight is close",
            detail = "Complete ${5 - sampleSize} more scheduled ${if (5 - sampleSize == 1) "task" else "tasks"} to unlock it.",
        )
        else -> InsightState.OnboardingSuggestion(
            title = "Keep building your pattern",
            detail = "More completed and failed tasks will make the next recommendation more reliable.",
        )
    }
    copy(streakDays = streak, insightState = insight)
}.getOrDefault(this)

