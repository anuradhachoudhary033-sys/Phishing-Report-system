package com.soar.client.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.soar.client.data.local.EmailEntity
import com.soar.client.data.remote.EmailDetailDto
import com.soar.client.data.repository.EmailRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BlockedViewModel(
    private val emailRepository: EmailRepository,
    private val userEmail: String
) : ViewModel() {

    val blockedEmails: StateFlow<List<EmailEntity>> = emailRepository.autoBlockedEmails
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _selectedDetail = MutableStateFlow<EmailDetailDto?>(null)
    val selectedDetail: StateFlow<EmailDetailDto?> = _selectedDetail.asStateFlow()

    private val _isLoadingDetail = MutableStateFlow(false)
    val isLoadingDetail: StateFlow<Boolean> = _isLoadingDetail.asStateFlow()

    fun loadBlockedEmailDetail(msgId: String) {
        viewModelScope.launch {
            _isLoadingDetail.value = true
            val result = emailRepository.fetchEmailDetail(msgId, userEmail)
            if (result.isSuccess) {
                _selectedDetail.value = result.getOrNull()
            }
            _isLoadingDetail.value = false
        }
    }

    fun restoreToInbox(msgId: String) {
        viewModelScope.launch {
            emailRepository.restoreToInbox(msgId)
        }
    }

    fun permanentlyDelete(msgId: String) {
        viewModelScope.launch {
            emailRepository.trashEmail(msgId, userEmail)
            _selectedDetail.value = null
        }
    }
}

class BlockedViewModelFactory(
    private val emailRepository: EmailRepository,
    private val userEmail: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return BlockedViewModel(emailRepository, userEmail) as T
    }
}
