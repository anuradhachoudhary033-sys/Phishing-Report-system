package com.soar.client.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.soar.client.data.repository.AuthRepository
import com.soar.client.data.repository.AuthUser
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AuthViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    val authState: StateFlow<AuthUser> = authRepository.authState

    fun isLoggedIn(): Boolean = authRepository.isLoggedIn()

    fun getBackendUrl(): String = authRepository.getBackendUrl()

    fun setBackendUrl(url: String) {
        authRepository.setBackendUrl(url)
    }

    fun login(email: String, displayName: String = "", avatarUrl: String = "") {
        viewModelScope.launch {
            authRepository.saveSession(
                email = email.trim(),
                displayName = displayName.ifBlank { email.substringBefore("@") },
                avatarUrl = avatarUrl
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.clearSession()
        }
    }
}

class AuthViewModelFactory(
    private val authRepository: AuthRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AuthViewModel(authRepository) as T
    }
}
