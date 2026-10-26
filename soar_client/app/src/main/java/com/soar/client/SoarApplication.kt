package com.soar.client

import android.app.Application
import com.soar.client.data.local.AppDatabase
import com.soar.client.data.remote.SoarApiService
import com.soar.client.data.remote.WebSocketManager
import com.soar.client.data.repository.AuthRepository
import com.soar.client.data.repository.EmailRepository
import com.soar.client.nativecore.NativeCore

class SoarApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var authRepository: AuthRepository
        private set

    lateinit var apiService: SoarApiService
        private set

    lateinit var emailRepository: EmailRepository
        private set

    lateinit var webSocketManager: WebSocketManager
        private set

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize local persistent storage & database
        database = AppDatabase.getInstance(this)
        authRepository = AuthRepository(this)

        // 2. Initialize networking
        val backendUrl = authRepository.getBackendUrl()
        apiService = SoarApiService.create(backendUrl)
        emailRepository = EmailRepository(database.emailDao(), apiService)
        webSocketManager = WebSocketManager()

        // 3. Initialize high-speed C++ Native Core (JNI)
        NativeCore.isNativeReady()
    }

    fun updateBackendUrl(newUrl: String) {
        authRepository.setBackendUrl(newUrl)
        apiService = SoarApiService.create(newUrl)
        emailRepository.updateApiService(apiService)
    }
}
