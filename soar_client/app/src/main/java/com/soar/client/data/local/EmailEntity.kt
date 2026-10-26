package com.soar.client.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room Database entity representing an email message cached locally on device.
 * Enables zero-latency, offline-first inbox browsing without server roundtrips.
 */
@Entity(tableName = "emails")
data class EmailEntity(
    @PrimaryKey
    val msgId: String,
    val threadId: String = "",
    val senderEmail: String = "",
    val senderDisplay: String = "",
    val recipient: String = "",
    val subject: String = "",
    val date: String = "",
    val snippet: String = "",
    val isUnread: Boolean = false,
    val hasAttachment: Boolean = false,
    val attachmentCount: Int = 0,

    // Phishing analysis (only used to route to "Auto Blocked" section)
    val verdict: String = "safe",       // "safe", "suspicious", "phishing", "unknown"
    val confidence: Float = 0f,
    val threatCount: Int = 0,
    val urlsExtracted: Int = 0,
    val isAutoBlocked: Boolean = false, // True if verdict is "phishing" or threats detected

    // Detailed body content (cached on demand or during sync)
    val bodyText: String = "",
    val bodyHtml: String = "",

    // Folder: "INBOX" or "SENT"
    val folder: String = "INBOX",

    // Ordering & Cache metadata
    val cachedAt: Long = System.currentTimeMillis(),
    val sortOrder: Long = 0L
)
