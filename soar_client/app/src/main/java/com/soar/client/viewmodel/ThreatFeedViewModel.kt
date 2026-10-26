package com.soar.client.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.soar.client.data.remote.ConnectionStatus
import com.soar.client.data.remote.PhishingEventDto
import com.soar.client.data.remote.WebSocketManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ThreatFeedViewModel(
    private val webSocketManager: WebSocketManager,
    private val wsUrl: String
) : ViewModel() {

    val connectionStatus: StateFlow<ConnectionStatus> = webSocketManager.connectionStatus

    private val _events = MutableStateFlow<List<PhishingEventDto>>(emptyList())
    val events: StateFlow<List<PhishingEventDto>> = _events.asStateFlow()

    init {
        webSocketManager.connect(wsUrl)
        viewModelScope.launch {
            webSocketManager.events.collect { newEvent ->
                _events.value = listOf(newEvent) + _events.value.take(49)
            }
        }
    }

    fun reconnect() {
        webSocketManager.connect(wsUrl)
    }

    override fun onCleared() {
        super.onCleared()
        webSocketManager.disconnect()
    }
}

class ThreatFeedViewModelFactory(
    private val webSocketManager: WebSocketManager,
    private val wsUrl: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ThreatFeedViewModel(webSocketManager, wsUrl) as T
    }
}
