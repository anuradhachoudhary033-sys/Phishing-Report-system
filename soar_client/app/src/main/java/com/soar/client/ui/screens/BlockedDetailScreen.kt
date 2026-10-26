package com.soar.client.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soar.client.ui.components.LiquidGlassCard
import com.soar.client.ui.components.LiquidGlassTopBar
import com.soar.client.ui.theme.SoarBackground
import com.soar.client.ui.theme.SoarCyan
import com.soar.client.ui.theme.SoarDanger
import com.soar.client.ui.theme.SoarDangerDim
import com.soar.client.ui.theme.SoarSuccess
import com.soar.client.ui.theme.SoarSurfaceAlt
import com.soar.client.ui.theme.SoarWarning
import com.soar.client.ui.theme.TextMuted
import com.soar.client.ui.theme.TextPrimary
import com.soar.client.ui.theme.TextSecondary
import com.soar.client.viewmodel.BlockedViewModel

/**
 * Deep security triage screen for quarantined emails.
 * Only accessible via the "Auto Blocked" section.
 */
@Composable
fun BlockedDetailScreen(
    msgId: String,
    blockedViewModel: BlockedViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val detail by blockedViewModel.selectedDetail.collectAsState()
    val isLoading by blockedViewModel.isLoadingDetail.collectAsState()

    LaunchedEffect(msgId) {
        blockedViewModel.loadBlockedEmailDetail(msgId)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SoarBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LiquidGlassTopBar(
                title = "Security Triage",
                subtitle = "Quarantine Analysis Report",
                onBackClick = onBack
            )

            if (isLoading && detail == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = SoarDanger)
                }
            } else if (detail != null) {
                val d = detail!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    // Threat Banner
                    LiquidGlassCard(
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth(),
                        tintColor = SoarDangerDim.copy(alpha = 0.5f)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(SoarDangerDim),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Report,
                                        contentDescription = null,
                                        tint = SoarDanger,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Text(
                                        text = "AUTO-BLOCKED THREAT",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = SoarDanger
                                    )
                                    val percent = (d.confidence * 100).toInt()
                                    Text(
                                        text = "$percent% Phishing Confidence • ${d.verdict.uppercase()}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = "Subject: ${d.subject ?: "(No Subject)"}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Sender: ${d.senderEmail ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Threat Breakdown Section
                    Text(
                        text = "Identified Threat Vectors (${d.threats.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    if (d.threats.isEmpty()) {
                        Text(
                            text = "Automated heuristic trigger: elevated URL risk score.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            d.threats.forEach { threat ->
                                LiquidGlassCard(
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = threat.threatType.replace("_", " ").uppercase(),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (threat.severity.equals("high", true)) SoarDanger else SoarWarning
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(SoarSurfaceAlt)
                                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = threat.severity.uppercase(),
                                                    color = TextSecondary,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))

                                        Text(
                                            text = threat.detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextPrimary
                                        )

                                        if (!threat.indicator.isNullOrBlank()) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = "Indicator: ${threat.indicator}",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                color = SoarCyan
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Extracted URLs Section
                    if (d.urlScores.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Extracted URLs (${d.urlScores.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            d.urlScores.forEach { (url, score) ->
                                LiquidGlassCard(
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Link,
                                            contentDescription = null,
                                            tint = if (score > 0.5) SoarDanger else SoarCyan,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = url,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextPrimary,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = String.format("%.2f", score),
                                            color = if (score > 0.5) SoarDanger else SoarSuccess,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))
                    HorizontalDivider(color = SoarSurfaceAlt)
                    Spacer(modifier = Modifier.height(20.dp))

                    // Safe Quarantined Body Preview
                    Text(
                        text = "Quarantined Body Preview (Safe Text)",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SoarSurfaceAlt.copy(alpha = 0.5f))
                            .padding(14.dp)
                    ) {
                        Text(
                            text = d.bodyText?.ifBlank { "(No plain-text body available)" } ?: "(No plain-text body)",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 15
                        )
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // Action Buttons (Restore or Permanently Trash)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                blockedViewModel.restoreToInbox(msgId)
                                onBack()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SoarSuccess)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Restore to Inbox")
                        }

                        Button(
                            onClick = {
                                blockedViewModel.permanentlyDelete(msgId)
                                onBack()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SoarDanger,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Delete Threat")
                        }
                    }

                    Spacer(modifier = Modifier.height(48.dp))
                }
            }
        }
    }
}
