package com.soar.client.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soar.client.data.remote.AttachmentPayload
import com.soar.client.data.remote.SendEmailPayload
import com.soar.client.data.repository.EmailRepository
import com.soar.client.ui.components.LiquidGlassTopBar
import com.soar.client.ui.theme.SoarBackground
import com.soar.client.ui.theme.SoarCyan
import com.soar.client.ui.theme.SoarSurfaceAlt
import com.soar.client.ui.theme.TextMuted
import com.soar.client.ui.theme.TextPrimary
import com.soar.client.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AttachmentDraft(
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val base64Data: String
)

@Composable
fun ComposeEmailScreen(
    userEmail: String,
    initialTo: String = "",
    initialSubject: String = "",
    threadId: String? = null,
    emailRepository: EmailRepository,
    onClose: () -> Unit,
    onEmailSent: () -> Unit = onClose,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var toAddress by remember { mutableStateOf(initialTo) }
    var subject by remember {
        mutableStateOf(
            if (initialSubject.isNotBlank() && !initialSubject.startsWith("Re:", true)) "Re: $initialSubject" else initialSubject
        )
    }
    var body by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<AttachmentDraft>>(emptyList()) }
    var isSending by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Android Document Picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val loadedDrafts = mutableListOf<AttachmentDraft>()
            for (uri in uris) {
                try {
                    var fileName = "attachment"
                    var sizeBytes = 0L

                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: "attachment"
                            if (sizeIdx >= 0) sizeBytes = cursor.getLong(sizeIdx)
                        }
                    }

                    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

                    // Read bytes, capped at 10MB per file for mobile memory safety
                    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes()
                    }

                    if (bytes != null && bytes.size <= 10 * 1024 * 1024) {
                        val base64Str = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        loadedDrafts.add(
                            AttachmentDraft(
                                uri = uri,
                                fileName = fileName,
                                mimeType = mimeType,
                                sizeBytes = if (sizeBytes > 0) sizeBytes else bytes.size.toLong(),
                                base64Data = base64Str
                            )
                        )
                    }
                } catch (e: Exception) {
                    // Ignore failing file gracefully
                }
            }

            withContext(Dispatchers.Main) {
                attachments = attachments + loadedDrafts
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SoarBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LiquidGlassTopBar(
                title = if (initialTo.isNotBlank()) "Reply" else "New Message",
                subtitle = "From: $userEmail",
                onBackClick = onClose,
                actions = {
                    // Attach file icon button in header bar
                    IconButton(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        enabled = !isSending
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Attach File",
                            tint = if (attachments.isNotEmpty()) SoarCyan else TextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    if (isSending) {
                        CircularProgressIndicator(
                            color = SoarCyan,
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .size(22.dp)
                                .padding(end = 8.dp)
                        )
                    } else {
                        IconButton(
                            onClick = {
                                if (toAddress.isNotBlank()) {
                                    scope.launch {
                                        isSending = true
                                        errorMessage = null
                                        val payload = SendEmailPayload(
                                            email = userEmail,
                                            to = toAddress.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                                            subject = subject,
                                            body = body,
                                            threadId = threadId,
                                            attachments = attachments.map {
                                                AttachmentPayload(
                                                    filename = it.fileName,
                                                    mimeType = it.mimeType,
                                                    contentBase64 = it.base64Data
                                                )
                                            }
                                        )
                                        val res = emailRepository.sendEmail(payload)
                                        isSending = false
                                        if (res.isSuccess) {
                                            onEmailSent()
                                        } else {
                                            errorMessage = res.exceptionOrNull()?.message ?: "Send failed"
                                        }
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = SoarCyan
                            )
                        }
                    }
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (errorMessage != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF2D1216))
                            .border(1.dp, Color(0xFFF43F5E).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = errorMessage!!,
                            color = Color(0xFFFFB4BA),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                OutlinedTextField(
                    value = toAddress,
                    onValueChange = { toAddress = it },
                    label = { Text("To") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SoarCyan,
                        unfocusedBorderColor = SoarSurfaceAlt,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text("Subject") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SoarCyan,
                        unfocusedBorderColor = SoarSurfaceAlt,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    placeholder = { Text("Compose email...", color = TextMuted) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SoarCyan,
                        unfocusedBorderColor = SoarSurfaceAlt,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                // Sleek attachment chips row when files are selected
                if (attachments.isNotEmpty()) {
                    Text(
                        text = "ATTACHMENTS (${attachments.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontWeight = FontWeight.Bold
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(attachments) { draft ->
                            AttachmentChip(
                                draft = draft,
                                onRemove = {
                                    attachments = attachments.filter { it.uri != draft.uri }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Bottom Action Row: Attach button + Send button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(50.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextPrimary
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SoarSurfaceAlt)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = null,
                            tint = SoarCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (attachments.isEmpty()) "Attach" else "${attachments.size}",
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Button(
                        onClick = {
                            if (toAddress.isNotBlank() && !isSending) {
                                scope.launch {
                                    isSending = true
                                    errorMessage = null
                                    val payload = SendEmailPayload(
                                        email = userEmail,
                                        to = toAddress.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                                        subject = subject,
                                        body = body,
                                        threadId = threadId,
                                        attachments = attachments.map {
                                            AttachmentPayload(
                                                filename = it.fileName,
                                                mimeType = it.mimeType,
                                                contentBase64 = it.base64Data
                                            )
                                        }
                                    )
                                    val res = emailRepository.sendEmail(payload)
                                    isSending = false
                                    if (res.isSuccess) {
                                        onEmailSent()
                                    } else {
                                        errorMessage = res.exceptionOrNull()?.message ?: "Send failed"
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SoarCyan,
                            contentColor = SoarBackground
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            tint = SoarBackground,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Send Message",
                            fontWeight = FontWeight.Bold,
                            color = SoarBackground
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dark-glass attachment chip with file type icon, name, formatted size, and remove button.
 */
@Composable
private fun AttachmentChip(
    draft: AttachmentDraft,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fileIcon = when {
        draft.mimeType.startsWith("image/", true) -> Icons.Default.Image
        draft.mimeType.contains("pdf", true) -> Icons.Default.PictureAsPdf
        else -> Icons.Default.Description
    }

    val formattedSize = formatFileSize(draft.sizeBytes)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SoarSurfaceAlt.copy(alpha = 0.7f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = fileIcon,
            contentDescription = null,
            tint = SoarCyan,
            modifier = Modifier.size(18.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.width(130.dp)) {
            Text(
                text = draft.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formattedSize,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                fontSize = 10.sp
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove",
                tint = TextSecondary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(java.util.Locale.US, "%.0f KB", kb)
        else -> "$bytes B"
    }
}
