package com.soar.client.data.remote

import com.google.gson.annotations.SerializedName

/**
 * Data Transfer Objects mirroring FastAPI backend schemas.
 */

data class InboxResponse(
    @SerializedName("email") val email: String,
    @SerializedName("messages") val messages: List<InboxMessageDto>,
    @SerializedName("next_page_token") val nextPageToken: String?,
    @SerializedName("count") val count: Int
)

data class InboxMessageDto(
    @SerializedName("msg_id") val msgId: String,
    @SerializedName("thread_id") val threadId: String?,
    @SerializedName("sender_email") val senderEmail: String?,
    @SerializedName("sender_display") val senderDisplay: String?,
    @SerializedName("recipient") val recipient: String? = null,
    @SerializedName("subject") val subject: String?,
    @SerializedName("date") val date: String?,
    @SerializedName("snippet") val snippet: String?,
    @SerializedName("is_unread") val isUnread: Boolean = false,
    @SerializedName("has_attachment") val hasAttachment: Boolean = false,
    @SerializedName("attachment_count") val attachmentCount: Int = 0,
    @SerializedName("verdict") val verdict: String = "safe",
    @SerializedName("confidence") val confidence: Float = 0f,
    @SerializedName("threat_count") val threatCount: Int = 0,
    @SerializedName("urls_extracted") val urlsExtracted: Int = 0
)

data class EmailDetailDto(
    @SerializedName("msg_id") val msgId: String,
    @SerializedName("thread_id") val threadId: String?,
    @SerializedName("sender_email") val senderEmail: String?,
    @SerializedName("sender_display") val senderDisplay: String?,
    @SerializedName("subject") val subject: String?,
    @SerializedName("date") val date: String?,
    @SerializedName("message_id_header") val messageIdHeader: String?,
    @SerializedName("is_unread") val isUnread: Boolean = false,
    @SerializedName("labels") val labels: List<String> = emptyList(),
    @SerializedName("attachment_names") val attachmentNames: List<String> = emptyList(),
    @SerializedName("body_text") val bodyText: String?,
    @SerializedName("body_html") val bodyHtml: String?,
    @SerializedName("verdict") val verdict: String = "safe",
    @SerializedName("confidence") val confidence: Float = 0f,
    @SerializedName("threats") val threats: List<ThreatDto> = emptyList(),
    @SerializedName("url_scores") val urlScores: Map<String, Double> = emptyMap(),
    @SerializedName("urls_extracted") val urlsExtracted: Int = 0,
    @SerializedName("scanned_at") val scannedAt: String?
)

data class ThreatDto(
    @SerializedName("threat_type") val threatType: String,
    @SerializedName("severity") val severity: String,
    @SerializedName("detail") val detail: String,
    @SerializedName("indicator") val indicator: String?
)

data class TrashResponse(
    @SerializedName("status") val status: String,
    @SerializedName("msg_id") val msgId: String,
    @SerializedName("email") val email: String,
    @SerializedName("message") val message: String
)

data class AttachmentPayload(
    @SerializedName("filename") val filename: String,
    @SerializedName("mime_type") val mimeType: String,
    @SerializedName("content_base64") val contentBase64: String
)

data class SendEmailPayload(
    @SerializedName("email") val email: String,
    @SerializedName("to") val to: List<String>,
    @SerializedName("cc") val cc: List<String> = emptyList(),
    @SerializedName("bcc") val bcc: List<String> = emptyList(),
    @SerializedName("subject") val subject: String = "",
    @SerializedName("body") val body: String = "",
    @SerializedName("thread_id") val threadId: String? = null,
    @SerializedName("in_reply_to") val inReplyTo: String? = null,
    @SerializedName("attachments") val attachments: List<AttachmentPayload> = emptyList()
)

data class SendEmailResponse(
    @SerializedName("status") val status: String,
    @SerializedName("msg_id") val msgId: String?,
    @SerializedName("thread_id") val threadId: String?,
    @SerializedName("labels") val labels: List<String> = emptyList()
)

data class PhishingEventDto(
    @SerializedName("id") val id: String,
    @SerializedName("url") val url: String,
    @SerializedName("risk_score") val riskScore: Double,
    @SerializedName("is_phishing") val isPhishing: Boolean,
    @SerializedName("source_ip") val sourceIp: String?,
    @SerializedName("peer_node") val peerNode: String?,
    @SerializedName("timestamp") val timestamp: String?
)
