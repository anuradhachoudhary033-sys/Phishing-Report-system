package com.soar.client.ui.screens

import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.soar.client.data.local.EmailEntity
import com.soar.client.data.remote.EmailDetailDto
import com.soar.client.data.repository.EmailRepository
import com.soar.client.ui.components.LiquidGlassCard
import com.soar.client.ui.components.LiquidGlassTopBar
import com.soar.client.ui.theme.SoarBackground
import com.soar.client.ui.theme.SoarCyan
import com.soar.client.ui.theme.SoarDanger
import com.soar.client.ui.theme.SoarSurfaceAlt
import com.soar.client.ui.theme.TextMuted
import com.soar.client.ui.theme.TextPrimary
import com.soar.client.ui.theme.TextSecondary

/**
 * CLEAN email reader.
 *
 * Dedicated strictly to reading email content without any security audit or phishing banners.
 * All threat reports are isolated to the "Auto Blocked" section.
 */
import androidx.activity.compose.BackHandler

@Composable
fun EmailReaderScreen(
    msgId: String,
    userEmail: String,
    emailRepository: EmailRepository,
    onBack: () -> Unit,
    onReply: (to: String, subject: String, threadId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)
    val cachedEmail by emailRepository.getEmailById(msgId).collectAsState(initial = null)
    var detailDto by remember { mutableStateOf<EmailDetailDto?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(msgId) {
        isLoading = true
        val result = emailRepository.fetchEmailDetail(msgId, userEmail)
        if (result.isSuccess) {
            detailDto = result.getOrNull()
        }
        isLoading = false
    }

    val senderDisplay = detailDto?.senderDisplay ?: cachedEmail?.senderDisplay ?: ""
    val senderEmail = detailDto?.senderEmail ?: cachedEmail?.senderEmail ?: ""
    val subject = detailDto?.subject ?: cachedEmail?.subject ?: "(No Subject)"
    val date = detailDto?.date ?: cachedEmail?.date ?: ""
    val threadId = detailDto?.threadId ?: cachedEmail?.threadId ?: ""
    val bodyText = detailDto?.bodyText?.ifBlank { cachedEmail?.bodyText } ?: cachedEmail?.bodyText ?: cachedEmail?.snippet ?: ""
    val bodyHtml = detailDto?.bodyHtml?.ifBlank { cachedEmail?.bodyHtml } ?: cachedEmail?.bodyHtml ?: ""
    val attachments = detailDto?.attachmentNames ?: emptyList()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SoarBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Clean Top Bar
            LiquidGlassTopBar(
                title = "Email",
                onBackClick = onBack,
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            emailRepository.trashEmail(msgId, userEmail)
                            onBack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Trash",
                            tint = TextMuted
                        )
                    }
                    IconButton(onClick = { onReply(senderEmail, subject, threadId) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Reply,
                            contentDescription = "Reply",
                            tint = SoarCyan
                        )
                    }
                }
            )

            if (isLoading && detailDto == null && cachedEmail == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = SoarCyan)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    // Subject Heading
                    Text(
                        text = subject,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Sender Header Card
                    LiquidGlassCard(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val initial = (senderDisplay.ifBlank { senderEmail }).firstOrNull()?.uppercase() ?: "M"
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(SoarSurfaceAlt),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = initial,
                                    color = SoarCyan,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = senderDisplay.ifBlank { senderEmail },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = senderEmail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                                Text(
                                    text = "to me • $date",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Attachment Chips (if any)
                    if (attachments.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            attachments.forEach { filename ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(SoarSurfaceAlt)
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.AttachFile,
                                            contentDescription = null,
                                            tint = SoarCyan,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = filename,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    HorizontalDivider(color = SoarSurfaceAlt, thickness = 1.dp)

                    Spacer(modifier = Modifier.height(16.dp))

                    // Email Body (Clean reader - plain text or formatted HTML)
                    if (bodyHtml.isNotBlank() && bodyHtml.contains("<")) {
                        AndroidView(
                            factory = { context ->
                                WebView(context).apply {
                                    webViewClient = WebViewClient()
                                    setBackgroundColor(0x00000000)
                                    settings.defaultFontSize = 14
                                    val darkHtml = """
                                        <html>
                                        <head>
                                            <style>
                                                body { color: #F4F4F5; background: transparent; font-family: sans-serif; font-size: 15px; line-height: 1.5; padding: 4px; }
                                                a { color: #D4D4D8; text-decoration: underline; }
                                                img { max-width: 100%; height: auto; border-radius: 8px; }
                                            </style>
                                        </head>
                                        <body>$bodyHtml</body>
                                        </html>
                                    """.trimIndent()
                                    loadDataWithBaseURL(null, darkHtml, "text/html", "utf-8", null)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(400.dp)
                        )
                    } else {
                        Text(
                            text = bodyText.ifBlank { "(No content in email body)" },
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 24.sp,
                            color = TextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // Bottom Action Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { onReply(senderEmail, subject, threadId) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SoarCyan,
                                contentColor = SoarBackground
                            )
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Reply,
                                contentDescription = null,
                                tint = SoarBackground,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Reply",
                                fontWeight = FontWeight.SemiBold,
                                color = SoarBackground
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    emailRepository.trashEmail(msgId, userEmail)
                                    onBack()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = SoarDanger
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Move to Trash")
                        }
                    }

                    Spacer(modifier = Modifier.height(48.dp))
                }
            }
        }
    }
}
