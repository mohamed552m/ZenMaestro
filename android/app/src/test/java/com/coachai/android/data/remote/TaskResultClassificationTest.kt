package com.coachai.android.data.remote

import com.coachai.android.data.TaskFailureReason
import com.coachai.android.data.TaskRepositoryException
import com.coachai.android.data.toTaskResult
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TaskResultClassificationTest {
    @Test
    fun httpFailuresRemainDistinctFromAuthenticationFailures() {
        assertSame(TaskRemoteResult.Unauthorized, taskResultForStatus<String>(401))
        assertSame(TaskRemoteResult.Forbidden, taskResultForStatus<String>(403))
        assertSame(TaskRemoteResult.Conflict, taskResultForStatus<String>(409))
        assertSame(TaskRemoteResult.RateLimited, taskResultForStatus<String>(429))
        assertSame(TaskRemoteResult.ServiceUnavailable, taskResultForStatus<String>(502))
        assertSame(TaskRemoteResult.ServiceUnavailable, taskResultForStatus<String>(503))
        assertSame(TaskRemoteResult.ServiceUnavailable, taskResultForStatus<String>(504))
        assertSame(TaskRemoteResult.ServerError, taskResultForStatus<String>(500))
    }

    @Test
    fun timeoutIsNotReportedAsOffline() {
        assertSame(
            TaskRemoteResult.Timeout,
            taskResultForException<String>(SocketTimeoutException()),
        )
        assertSame(
            TaskRemoteResult.NetworkError,
            taskResultForException<String>(IOException()),
        )
    }

    @Test
    fun repositoryMappingPreservesServiceFailureReason() {
        val error = TaskRemoteResult.ServiceUnavailable
            .toTaskResult<String>()
            .exceptionOrNull() as TaskRepositoryException
        assertEquals(TaskFailureReason.SERVICE_UNAVAILABLE, error.reason)
    }
}
