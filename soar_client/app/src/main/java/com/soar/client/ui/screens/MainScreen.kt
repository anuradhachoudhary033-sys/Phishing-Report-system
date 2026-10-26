package com.soar.client.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soar.client.data.repository.EmailRepository
import com.soar.client.ui.components.LiquidGlassPill
import com.soar.client.ui.components.SoarLiquidBottomBar
import com.soar.client.ui.theme.SoarBackground
import com.soar.client.ui.theme.SoarCyan
import com.soar.client.ui.theme.SoarDanger
import com.soar.client.ui.theme.SoarSuccess
import com.soar.client.ui.theme.TextMuted
import com.soar.client.ui.theme.TextPrimary
import com.soar.client.ui.theme.TextSecondary
import com.soar.client.viewmodel.AuthViewModel
import com.soar.client.viewmodel.BlockedViewModel
import androidx.activity.compose.BackHandler
import com.soar.client.viewmodel.InboxViewModel
import com.soar.client.viewmodel.ThreatFeedViewModel
import kotlinx.coroutines.launch

sealed class ScreenNav {
    object Main : ScreenNav()
    data class Reader(val msgId: String) : ScreenNav()
    data class BlockedDetail(val msgId: String) : ScreenNav()
    data class Compose(val to: String = "", val subject: String = "", val threadId: String? = null) : ScreenNav()
}

@Composable
fun rememberGraphicsLayer(): GraphicsLayer {
    val graphicsContext = LocalGraphicsContext.current
    val layer = remember(graphicsContext) { graphicsContext.createGraphicsLayer() }
    DisposableEffect(layer, graphicsContext) {
        onDispose {
            graphicsContext.releaseGraphicsLayer(layer)
        }
    }
    return layer
}

@Composable
fun MainScreen(
    userEmail: String,
    authViewModel: AuthViewModel,
    inboxViewModel: InboxViewModel,
    blockedViewModel: BlockedViewModel,
    threatFeedViewModel: ThreatFeedViewModel,
    emailRepository: EmailRepository,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentTab by remember { mutableStateOf("inbox") }
    var currentSubScreen by remember { mutableStateOf<ScreenNav>(ScreenNav.Main) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backdropLayer = rememberGraphicsLayer()

    val currentFolder by inboxViewModel.currentFolder.collectAsState()
    val blockedEmails by blockedViewModel.blockedEmails.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(if (currentFolder == "SENT") 1 else 0) }
    var previousMailboxTab by remember { mutableIntStateOf(if (currentFolder == "SENT") 1 else 0) }
    var showComposeSheet by remember { mutableStateOf(false) }
    var composeInitialTo by remember { mutableStateOf("") }
    var composeInitialSubject by remember { mutableStateOf("") }
    var composeThreadId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentFolder) {
        if (!showComposeSheet && selectedTabIndex != 2) {
            val target = if (currentFolder == "SENT") 1 else 0
            selectedTabIndex = target
            previousMailboxTab = target
        }
    }

    val triggerCompose: (String, String, String?) -> Unit = { to, subject, threadId ->
        composeInitialTo = to
        composeInitialSubject = subject
        composeThreadId = threadId
        selectedTabIndex = 2
        scope.launch {
            delay(160)
            showComposeSheet = true
        }
    }

    val closeCompose: () -> Unit = {
        showComposeSheet = false
        selectedTabIndex = previousMailboxTab
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    BackHandler(enabled = !drawerState.isOpen && showComposeSheet) {
        closeCompose()
    }
    BackHandler(enabled = !drawerState.isOpen && !showComposeSheet && currentSubScreen is ScreenNav.Main && currentTab != "inbox") {
        currentTab = "inbox"
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = currentSubScreen is ScreenNav.Main && currentTab == "inbox",
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = SoarBackground,
                drawerContentColor = TextPrimary,
                modifier = Modifier.width(310.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 28.dp, horizontal = 16.dp)
                ) {
                    // User Profile Header
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 20.dp, start = 4.dp)
                    ) {
                        val initial = userEmail.firstOrNull()?.uppercase() ?: "U"
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(SoarCyan.copy(alpha = 0.2f))
                                .border(1.dp, SoarCyan, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = initial,
                                color = SoarCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = userEmail,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(SoarSuccess)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "SOAR Shield Active",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SoarSuccess,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // Section 1: Mail Folders
                    Text(
                        text = "MAILBOX",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )

                    NavigationDrawerItem(
                        label = { Text("Inbox", fontWeight = FontWeight.Medium) },
                        icon = { Icon(Icons.Default.Inbox, contentDescription = null) },
                        selected = currentTab == "inbox" && currentFolder == "INBOX",
                        onClick = {
                            selectedTabIndex = 0
                            previousMailboxTab = 0
                            inboxViewModel.setFolder("INBOX")
                            currentTab = "inbox"
                            scope.launch { drawerState.close() }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = SoarCyan.copy(alpha = 0.15f),
                            selectedIconColor = SoarCyan,
                            selectedTextColor = SoarCyan,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )

                    NavigationDrawerItem(
                        label = { Text("Outbox / Sent", fontWeight = FontWeight.Medium) },
                        icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                        selected = currentTab == "inbox" && currentFolder == "SENT",
                        onClick = {
                            selectedTabIndex = 1
                            previousMailboxTab = 1
                            inboxViewModel.setFolder("SENT")
                            currentTab = "inbox"
                            scope.launch { drawerState.close() }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = SoarCyan.copy(alpha = 0.15f),
                            selectedIconColor = SoarCyan,
                            selectedTextColor = SoarCyan,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Section 2: Security & Threat Detection (Strictly isolated to sidebar)
                    Text(
                        text = "SECURITY & THREATS",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )

                    NavigationDrawerItem(
                        label = { Text("Auto Blocked", fontWeight = FontWeight.Medium) },
                        icon = { Icon(Icons.Default.Shield, contentDescription = null) },
                        badge = {
                            if (blockedEmails.isNotEmpty()) {
                                LiquidGlassPill(count = blockedEmails.size, isDanger = true)
                            }
                        },
                        selected = currentTab == "blocked",
                        onClick = {
                            currentTab = "blocked"
                            scope.launch { drawerState.close() }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = SoarDanger.copy(alpha = 0.15f),
                            selectedIconColor = SoarDanger,
                            selectedTextColor = SoarDanger,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )

                    NavigationDrawerItem(
                        label = { Text("Threat Feed", fontWeight = FontWeight.Medium) },
                        icon = { Icon(Icons.Default.Sensors, contentDescription = null) },
                        selected = currentTab == "triage",
                        onClick = {
                            currentTab = "triage"
                            scope.launch { drawerState.close() }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = SoarCyan.copy(alpha = 0.15f),
                            selectedIconColor = SoarCyan,
                            selectedTextColor = SoarCyan,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier.padding(vertical = 12.dp)
                    )

                    // Section 3: Preferences & Sign Out
                    NavigationDrawerItem(
                        label = { Text("Settings", fontWeight = FontWeight.Medium) },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        selected = currentTab == "settings",
                        onClick = {
                            currentTab = "settings"
                            scope.launch { drawerState.close() }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = SoarCyan.copy(alpha = 0.15f),
                            selectedIconColor = SoarCyan,
                            selectedTextColor = SoarCyan,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )

                    NavigationDrawerItem(
                        label = { Text("Sign Out", fontWeight = FontWeight.Medium, color = SoarDanger) },
                        icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = SoarDanger) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onLogout()
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(SoarBackground)
        ) {
            when (val screen = currentSubScreen) {
                is ScreenNav.Main -> {
                    val currentDestination = when {
                        currentTab == "inbox" && currentFolder == "SENT" -> "SENT"
                        currentTab == "inbox" -> "INBOX"
                        else -> currentTab
                    }

                    fun getDestinationIndex(dest: String): Int = when (dest) {
                        "INBOX" -> 0
                        "SENT" -> 1
                        "blocked" -> 2
                        "triage" -> 3
                        "settings" -> 4
                        else -> 0
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .drawWithContent {
                                    backdropLayer.record {
                                        this@drawWithContent.drawContent()
                                    }
                                    drawLayer(backdropLayer)
                                }
                        ) {
                            AnimatedContent(
                                targetState = currentDestination,
                                transitionSpec = {
                                    val forward = getDestinationIndex(targetState) > getDestinationIndex(initialState)
                                    if (forward) {
                                        (slideInHorizontally(animationSpec = tween(320, easing = FastOutSlowInEasing)) { fullWidth -> fullWidth / 4 } +
                                                fadeIn(animationSpec = tween(260)))
                                            .togetherWith(
                                                slideOutHorizontally(animationSpec = tween(320, easing = FastOutSlowInEasing)) { fullWidth -> -fullWidth / 4 } +
                                                        fadeOut(animationSpec = tween(200))
                                            ).using(SizeTransform(clip = false))
                                    } else {
                                        (slideInHorizontally(animationSpec = tween(320, easing = FastOutSlowInEasing)) { fullWidth -> -fullWidth / 4 } +
                                                fadeIn(animationSpec = tween(260)))
                                            .togetherWith(
                                                slideOutHorizontally(animationSpec = tween(320, easing = FastOutSlowInEasing)) { fullWidth -> fullWidth / 4 } +
                                                        fadeOut(animationSpec = tween(200))
                                            ).using(SizeTransform(clip = false))
                                    }
                                },
                                label = "screenTransition",
                                modifier = Modifier.fillMaxSize()
                            ) { targetDest ->
                                when (targetDest) {
                                    "INBOX", "SENT" -> InboxScreen(
                                        inboxViewModel = inboxViewModel,
                                        folderOverride = targetDest,
                                        onOpenDrawer = {
                                            scope.launch { drawerState.open() }
                                        },
                                        onEmailClick = { msgId ->
                                            currentSubScreen = ScreenNav.Reader(msgId)
                                        },
                                        onComposeClick = {
                                            triggerCompose("", "", null)
                                        }
                                    )
                                    "blocked" -> AutoBlockedScreen(
                                        blockedViewModel = blockedViewModel,
                                        onBlockedEmailClick = { msgId ->
                                            currentSubScreen = ScreenNav.BlockedDetail(msgId)
                                        },
                                        onBack = { currentTab = "inbox" }
                                    )
                                    "triage" -> TriageLiveScreen(
                                        threatFeedViewModel = threatFeedViewModel,
                                        onBack = { currentTab = "inbox" }
                                    )
                                    "settings" -> SettingsScreen(
                                        authViewModel = authViewModel,
                                        onLogout = onLogout,
                                        onBack = { currentTab = "inbox" }
                                    )
                                }
                            }
                        }

                        // Floating Liquid Glass Navigation Bar (Strictly on Main Inbox / Outbox view)
                        if (currentTab == "inbox") {
                            SoarLiquidBottomBar(
                                selectedIndex = selectedTabIndex,
                                onInboxClick = {
                                    selectedTabIndex = 0
                                    previousMailboxTab = 0
                                    inboxViewModel.setFolder("INBOX")
                                    currentTab = "inbox"
                                },
                                onSentClick = {
                                    selectedTabIndex = 1
                                    previousMailboxTab = 1
                                    inboxViewModel.setFolder("SENT")
                                    currentTab = "inbox"
                                },
                                onComposeClick = {
                                    triggerCompose("", "", null)
                                },
                                onProfileClick = {
                                    scope.launch { drawerState.open() }
                                },
                                backdropLayer = backdropLayer,
                                modifier = Modifier.align(Alignment.BottomCenter)
                            )
                        }
                    }
                }

                is ScreenNav.Reader -> {
                    EmailReaderScreen(
                        msgId = screen.msgId,
                        userEmail = userEmail,
                        emailRepository = emailRepository,
                        onBack = { currentSubScreen = ScreenNav.Main },
                        onReply = { to, subject, threadId ->
                            triggerCompose(to, subject, threadId)
                        }
                    )
                }

                is ScreenNav.BlockedDetail -> {
                    BlockedDetailScreen(
                        msgId = screen.msgId,
                        blockedViewModel = blockedViewModel,
                        onBack = { currentSubScreen = ScreenNav.Main }
                    )
                }

                is ScreenNav.Compose -> {
                    ComposeEmailScreen(
                        userEmail = userEmail,
                        initialTo = screen.to,
                        initialSubject = screen.subject,
                        threadId = screen.threadId,
                        emailRepository = emailRepository,
                        onClose = { currentSubScreen = ScreenNav.Main }
                    )
                }
            }

            AnimatedVisibility(
                visible = showComposeSheet,
                enter = slideInVertically(
                    initialOffsetY = { fullHeight -> fullHeight },
                    animationSpec = tween(360, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(260)),
                exit = slideOutVertically(
                    targetOffsetY = { fullHeight -> fullHeight },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(200)),
                modifier = Modifier.fillMaxSize()
            ) {
                ComposeEmailScreen(
                    userEmail = userEmail,
                    initialTo = composeInitialTo,
                    initialSubject = composeInitialSubject,
                    threadId = composeThreadId,
                    emailRepository = emailRepository,
                    onClose = {
                        closeCompose()
                    },
                    onEmailSent = {
                        closeCompose()
                        inboxViewModel.syncSent()
                    }
                )
            }
        }
    }
}
