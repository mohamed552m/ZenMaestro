package com.coachai.android.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.coachai.android.data.CoachFailureReason
import com.coachai.android.data.CoachRepository
import com.coachai.android.data.CoachRepositoryException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AskCoachUiState(
    val messages: List<AskMessage> = emptyList(),
    val isSending: Boolean = false,
    val isConnected: Boolean = true,
)

class AskCoachViewModel(private val repository: CoachRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(AskCoachUiState())
    val uiState: StateFlow<AskCoachUiState> = _uiState.asStateFlow()

    fun send(message: String) {
        val clean = message.trim()
        if (clean.isEmpty() || _uiState.value.isSending) return
        val user = AskMessage(UUID.randomUUID().toString(), clean, true)
        _uiState.value = _uiState.value.copy(messages = _uiState.value.messages + user, isSending = true)
        viewModelScope.launch {
            repository.chat(clean)
                .onSuccess { reply ->
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages + AskMessage(UUID.randomUUID().toString(), reply, false),
                        isSending = false,
                        isConnected = true,
                    )
                }
                .onFailure { error ->
                    val reason = (error as? CoachRepositoryException)?.reason
                    val text = when (reason) {
                        CoachFailureReason.AUTHENTICATION_REQUIRED -> "Your session expired. Log in again to continue with the coach."
                        CoachFailureReason.NETWORK_ERROR -> "I couldn't reach the coach. Check the connection and try again."
                        CoachFailureReason.CONFIGURATION_ERROR -> "The AI coach isn't configured on the server yet."
                        CoachFailureReason.AI_UNAVAILABLE -> "The coach couldn't answer right now. Try again in a moment."
                        CoachFailureReason.TIMEOUT -> "The coach took too long to answer. Try a shorter question."
                        else -> "The coach returned an unexpected response. Please try again."
                    }
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages + AskMessage(UUID.randomUUID().toString(), text, false),
                        isSending = false,
                        isConnected = reason != CoachFailureReason.AUTHENTICATION_REQUIRED,
                    )
                }
        }
    }

    class Factory(private val repository: CoachRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AskCoachViewModel(repository) as T
    }
}
