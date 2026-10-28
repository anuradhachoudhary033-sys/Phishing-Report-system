package com.soar.client.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.soar.client.data.local.EmailEntity
import com.soar.client.data.repository.EmailRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InboxViewModel(
    private val emailRepository: EmailRepository,
    private val userEmail: String
) : ViewModel() {

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _currentFolder = MutableStateFlow("INBOX") // "INBOX" or "SENT"
    val currentFolder: StateFlow<String> = _currentFolder.asStateFlow()

    private val _filterMode = MutableStateFlow("all") // "all", "unread", "important"
    val filterMode: StateFlow<String> = _filterMode.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private fun applyFilters(rawList: List<EmailEntity>, query: String, filter: String): List<EmailEntity> {
        val filteredByChip = when (filter) {
            "unread" -> rawList.filter { it.isUnread }
            "important" -> rawList.filter { it.hasAttachment || it.verdict != "safe" }
            else -> rawList
        }
        return if (query.isBlank()) {
            filteredByChip
        } else {
            filteredByChip.filter { email ->
                email.subject.contains(query, ignoreCase = true) ||
                        email.senderDisplay.contains(query, ignoreCase = true) ||
                        email.senderEmail.contains(query, ignoreCase = true) ||
                        email.snippet.contains(query, ignoreCase = true)
            }
        }
    }

    /**
     * Folder-pinned streams. Screens rendered inside an AnimatedContent transition must each
     * read their OWN folder's list, otherwise the outgoing screen instantly switches to the new
     * folder's data (or an empty state) while it is still sliding out.
     */
    val inboxEmails: StateFlow<List<EmailEntity>> = combine(
        emailRepository.inboxEmails, _searchQuery, _filterMode
    ) { list, query, filter -> applyFilters(list, query, filter) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val sentEmails: StateFlow<List<EmailEntity>> = combine(
        emailRepository.sentEmails, _searchQuery, _filterMode
    ) { list, query, filter -> applyFilters(list, query, filter) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Serves emails from Room DB immediately; switches between INBOX and SENT, filters with query and chips
    val emails: StateFlow<List<EmailEntity>> = combine(
        _currentFolder,
        inboxEmails,
        sentEmails
    ) { folder, inboxList, sentList ->
        if (folder == "SENT") sentList else inboxList
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    init {
        // Sync active INBOX on launch. Sent messages are cached in Room DB and sync on-demand
        // when the user navigates to the Outbox/Sent folder to stay well within Gmail API rate limits.
        refreshInbox()
    }

    fun setFolder(folder: String) {
        _currentFolder.value = folder
        if (folder == "SENT") {
            syncSent()
        } else {
            refreshInbox()
        }
    }

    fun setFilterMode(mode: String) {
        _filterMode.value = mode
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun refreshInbox() {
        if (userEmail.isBlank()) return
        viewModelScope.launch {
            _isSyncing.value = true
            _errorMessage.value = null
            val result = emailRepository.syncRecent100Emails(userEmail)
            if (result.isFailure) {
                _errorMessage.value = result.exceptionOrNull()?.message ?: "Sync failed"
            }
            _isSyncing.value = false
        }
    }

    fun syncSent() {
        if (userEmail.isBlank()) return
        viewModelScope.launch {
            _isSyncing.value = true
            _errorMessage.value = null
            val result = emailRepository.syncRecentSentEmails(userEmail)
            if (result.isFailure) {
                _errorMessage.value = result.exceptionOrNull()?.message ?: "Sent sync failed"
            }
            _isSyncing.value = false
        }
    }

    fun loadMoreEmails(folder: String = _currentFolder.value) {
        if (userEmail.isBlank() || _isSyncing.value) return
        viewModelScope.launch {
            if (folder == "SENT") {
                emailRepository.loadMore50PastSentEmails(userEmail)
            } else {
                emailRepository.loadMore50PastEmails(userEmail)
            }
        }
    }

    fun trashEmail(msgId: String) {
        viewModelScope.launch {
            emailRepository.trashEmail(msgId, userEmail)
        }
    }
}

class InboxViewModelFactory(
    private val emailRepository: EmailRepository,
    private val userEmail: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return InboxViewModel(emailRepository, userEmail) as T
    }
}
