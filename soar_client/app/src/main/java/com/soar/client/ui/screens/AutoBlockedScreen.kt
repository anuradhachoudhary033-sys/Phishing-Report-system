package com.soar.client.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soar.client.ui.components.BlockedEmailRowItem
import com.soar.client.ui.components.LiquidGlassPill
import com.soar.client.ui.components.LiquidGlassTopBar
import com.soar.client.ui.theme.SoarBackground
import com.soar.client.ui.theme.SoarDanger
import com.soar.client.ui.theme.SoarDangerDim
import com.soar.client.ui.theme.SoarSuccess
import com.soar.client.ui.theme.TextMuted
import com.soar.client.ui.theme.TextPrimary
import com.soar.client.ui.theme.TextSecondary
import com.soar.client.viewmodel.BlockedViewModel

/**
 * Dedicated "Auto Blocked" section.
 * Contains exclusively emails quarantined due to severe phishing attacks.
 */
@Composable
fun AutoBlockedScreen(
    blockedViewModel: BlockedViewModel,
    onBlockedEmailClick: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val blockedEmails by blockedViewModel.blockedEmails.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SoarBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header Bar
            LiquidGlassTopBar(
                title = "Auto Blocked",
                subtitle = "${blockedEmails.size} threats quarantined",
                onBackClick = onBack,
                actions = {
                    if (blockedEmails.isNotEmpty()) {
                        LiquidGlassPill(
                            count = blockedEmails.size,
                            isDanger = true,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
            )

            // Threat Shield Banner Info
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(SoarDangerDim),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = SoarDanger,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = "Automated Security Quarantine",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Malicious emails isolated automatically. In-depth analysis available below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }
            }

            // List of Quarantined Threats
            if (blockedEmails.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Safe",
                            tint = SoarSuccess,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "No Blocked Emails",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "All recent incoming emails have passed security triage.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = blockedEmails,
                        key = { it.msgId }
                    ) { email ->
                        BlockedEmailRowItem(
                            email = email,
                            onClick = { onBlockedEmailClick(email.msgId) }
                        )
                    }
                }
            }
        }
    }
}
