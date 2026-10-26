package com.soar.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.soar.client.data.util.HtmlTextCleaner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soar.client.data.local.EmailEntity
import com.soar.client.ui.theme.SoarCyan
import com.soar.client.ui.theme.SoarSurfaceAlt
import com.soar.client.ui.theme.TextMuted
import com.soar.client.ui.theme.TextPrimary
import com.soar.client.ui.theme.TextSecondary

/**
 * CLEAN email row item for the regular inbox.
 *
 * Notice: Phishing audit metrics, confidence ratings, and threat counts are completely
 * omitted here. The email inbox remains completely clean and natural, exactly as requested.
 */
@Composable
fun EmailRowItem(
    email: EmailEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    LiquidGlassCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Sender Avatar with monogram
            val cleanSender = remember(email.senderDisplay, email.senderEmail, email.recipient, email.folder) {
                if (email.folder == "SENT") {
                    val to = email.recipient.trim()
                    if (to.isNotBlank()) "To: $to" else "To: " + email.senderDisplay.ifBlank { email.senderEmail }.ifBlank { "Recipient" }
                } else {
                    val s = email.senderDisplay.replace("(failed to parse)", "").trim()
                    if (s.isNotBlank()) s else email.senderEmail.replace("(failed to parse)", "").trim().ifBlank { "Unknown" }
                }
            }
            val initial = cleanSender.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "E"
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(SoarSurfaceAlt),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initial,
                    color = SoarCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Email Metadata & Content
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Sender name + Date row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = cleanSender,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (email.isUnread) FontWeight.Bold else FontWeight.Medium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = email.date,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (email.isUnread) SoarCyan else TextMuted
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Subject line
                val cleanSubject = remember(email.subject) {
                    val s = email.subject.replace("(failed to parse)", "").trim()
                    s.ifBlank { "No Subject" }
                }
                Text(
                    text = cleanSubject,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (email.isUnread) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (email.isUnread) TextPrimary else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(3.dp))

                // Snippet preview (cleaned of HTML tags, DOCTYPE headers, and entities)
                val cleanSnippet = remember(email.snippet) { HtmlTextCleaner.clean(email.snippet) }
                Text(
                    text = cleanSnippet.ifBlank { "No preview available" },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // Attachment indicator
                if (email.hasAttachment) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Attachment",
                            tint = TextMuted,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "${email.attachmentCount} attachment${if (email.attachmentCount > 1) "s" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                }
            }

            // Unread indicator dot
            if (email.isUnread) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(SoarCyan)
                        .align(Alignment.CenterVertically)
                )
            }
        }
    }
}
