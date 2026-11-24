package com.coachai.android.ui.future

import com.coachai.android.data.FuturePlanningRepository
import com.coachai.android.data.remote.AuthTokenProvider
import com.coachai.android.data.remote.FutureApplyResult
import com.coachai.android.data.remote.FutureCandidate
import com.coachai.android.data.remote.FuturePlanningRemoteDataSource
import com.coachai.android.data.remote.FuturePlanningRequest
import com.coachai.android.data.remote.FuturePreview
import com.coachai.android.data.remote.TaskRemoteResult
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class FuturePlanningViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun repeatedApplyWhileRequestIsInFlightSendsOnlyOneMutation() = runTest {
        val remote = RecordingFuturePlanningRemote()
        val repository = FuturePlanningRepository(AuthTokenProvider { "token" }, remote)
        val viewModel = FuturePlanningViewModel(repository)
        val candidate = viewModel.state.value.candidates.single()

        viewModel.toggle(candidate)
        viewModel.preview()
        assertTrue(viewModel.state.value.preview != null)

        var appliedCallbacks = 0
        viewModel.apply { appliedCallbacks += 1 }
        viewModel.apply { appliedCallbacks += 1 }

        assertTrue(viewModel.state.value.applying)
        assertEquals(1, remote.applyCalls)

        remote.applyResult.complete(TaskRemoteResult.Success(FutureApplyResult("operation-1")))

        assertFalse(viewModel.state.value.applying)
        assertEquals("operation-1", viewModel.state.value.operationId)
        assertEquals(1, appliedCallbacks)
    }

    private class RecordingFuturePlanningRemote : FuturePlanningRemoteDataSource {
        var applyCalls = 0
        val applyResult = CompletableDeferred<TaskRemoteResult<FutureApplyResult>>()
        private val today = LocalDate.now()

        override suspend fun candidates(
            token: String,
            start: LocalDate,
            end: LocalDate,
        ): TaskRemoteResult<List<FutureCandidate>> = TaskRemoteResult.Success(
            listOf(
                FutureCandidate(
                    taskId = 1,
                    planDate = today,
                    title = "Study",
                    description = "",
                    remainingMinutes = 30,
                    priority = 3,
                    earliestStart = null,
                    deadline = null,
                ),
            ),
        )

        override suspend fun preview(
            token: String,
            request: FuturePlanningRequest,
        ): TaskRemoteResult<FuturePreview> = TaskRemoteResult.Success(
            FuturePreview(placedCount = 1, unplacedCount = 0, outcomes = emptyList()),
        )

        override suspend fun apply(
            token: String,
            request: FuturePlanningRequest,
            key: String,
        ): TaskRemoteResult<FutureApplyResult> {
            applyCalls += 1
            return applyResult.await()
        }

        override suspend fun undo(
            token: String,
            operationId: String,
            key: String,
        ): TaskRemoteResult<Unit> = TaskRemoteResult.Success(Unit)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
