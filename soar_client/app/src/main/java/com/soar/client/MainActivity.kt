package com.soar.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.soar.client.ui.screens.LoginScreen
import com.soar.client.ui.screens.MainScreen
import com.soar.client.ui.theme.SoarBackground
import com.soar.client.ui.theme.SoarTheme
import com.soar.client.viewmodel.AuthViewModel
import com.soar.client.viewmodel.AuthViewModelFactory
import com.soar.client.viewmodel.BlockedViewModel
import com.soar.client.viewmodel.BlockedViewModelFactory
import com.soar.client.viewmodel.InboxViewModel
import com.soar.client.viewmodel.InboxViewModelFactory
import com.soar.client.viewmodel.ThreatFeedViewModel
import com.soar.client.viewmodel.ThreatFeedViewModelFactory

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as SoarApplication

        setContent {
            SoarTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SoarBackground
                ) {
                    val authViewModel: AuthViewModel = viewModel(
                        factory = AuthViewModelFactory(app.authRepository)
                    )
                    val authState by authViewModel.authState.collectAsState()

                    if (authState.isLoggedIn && authState.email.isNotBlank()) {
                        val userEmail = authState.email

                        val inboxViewModel: InboxViewModel = viewModel(
                            key = "inbox_$userEmail",
                            factory = InboxViewModelFactory(app.emailRepository, userEmail)
                        )

                        val blockedViewModel: BlockedViewModel = viewModel(
                            key = "blocked_$userEmail",
                            factory = BlockedViewModelFactory(app.emailRepository, userEmail)
                        )

                        val backendUrl = authViewModel.getBackendUrl()
                        val wsUrl = backendUrl.replace("http://", "ws://").replace("https://", "wss://") + "/ws/triage"

                        val threatFeedViewModel: ThreatFeedViewModel = viewModel(
                            key = "threats_$wsUrl",
                            factory = ThreatFeedViewModelFactory(app.webSocketManager, wsUrl)
                        )

                        MainScreen(
                            userEmail = userEmail,
                            authViewModel = authViewModel,
                            inboxViewModel = inboxViewModel,
                            blockedViewModel = blockedViewModel,
                            threatFeedViewModel = threatFeedViewModel,
                            emailRepository = app.emailRepository,
                            onLogout = {
                                authViewModel.logout()
                            }
                        )
                    } else {
                        LoginScreen(
                            authViewModel = authViewModel,
                            onLoginSuccess = {
                                // Session is persisted in AuthRepository
                            }
                        )
                    }
                }
            }
        }
    }
}
