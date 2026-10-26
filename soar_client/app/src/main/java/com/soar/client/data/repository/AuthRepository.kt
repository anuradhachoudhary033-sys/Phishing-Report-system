package com.soar.client.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AuthUser(
    val email: String,
    val displayName: String = "",
    val avatarUrl: String = "",
    val isLoggedIn: Boolean = false
)

/**
 * Manages persistent user authentication session.
 * Eliminates repeated logins by storing credentials in local preferences and restoring
 * session state immediately upon application launch.
 */
class AuthRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _authState = MutableStateFlow(loadSession())
    val authState: StateFlow<AuthUser> = _authState.asStateFlow()

    fun isLoggedIn(): Boolean = _authState.value.isLoggedIn

    fun getCurrentEmail(): String = _authState.value.email

    fun getBackendUrl(): String {
        return prefs.getString(KEY_BACKEND_URL, "https://phishing-report-system.onrender.com") ?: "https://phishing-report-system.onrender.com"
    }

    fun setBackendUrl(url: String) {
        prefs.edit().putString(KEY_BACKEND_URL, url).apply()
    }

    fun saveSession(email: String, displayName: String = "", avatarUrl: String = "") {
        prefs.edit()
            .putString(KEY_EMAIL, email)
            .putString(KEY_NAME, displayName)
            .putString(KEY_AVATAR, avatarUrl)
            .putBoolean(KEY_LOGGED_IN, true)
            .apply()

        _authState.value = AuthUser(
            email = email,
            displayName = displayName,
            avatarUrl = avatarUrl,
            isLoggedIn = true
        )
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_EMAIL)
            .remove(KEY_NAME)
            .remove(KEY_AVATAR)
            .putBoolean(KEY_LOGGED_IN, false)
            .apply()

        _authState.value = AuthUser(
            email = "",
            displayName = "",
            avatarUrl = "",
            isLoggedIn = false
        )
    }

    private fun loadSession(): AuthUser {
        val loggedIn = prefs.getBoolean(KEY_LOGGED_IN, false)
        val email = prefs.getString(KEY_EMAIL, "") ?: ""
        val name = prefs.getString(KEY_NAME, "") ?: ""
        val avatar = prefs.getString(KEY_AVATAR, "") ?: ""

        return if (loggedIn && email.isNotBlank()) {
            AuthUser(
                email = email,
                displayName = name.ifBlank { email.substringBefore("@") },
                avatarUrl = avatar,
                isLoggedIn = true
            )
        } else {
            AuthUser(email = "", isLoggedIn = false)
        }
    }

    companion object {
        private const val PREFS_NAME = "soar_auth_session"
        private const val KEY_LOGGED_IN = "key_is_logged_in"
        private const val KEY_EMAIL = "key_user_email"
        private const val KEY_NAME = "key_user_name"
        private const val KEY_AVATAR = "key_user_avatar"
        private const val KEY_BACKEND_URL = "key_backend_url"
    }
}
