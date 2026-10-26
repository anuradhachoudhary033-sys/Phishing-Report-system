package com.soar.client.data.repository

import android.util.Log
import com.soar.client.data.local.EmailDao
import com.soar.client.data.local.EmailEntity
import com.soar.client.data.remote.EmailDetailDto
import com.soar.client.data.remote.InboxMessageDto
import com.soar.client.data.remote.SendEmailPayload
import com.soar.client.data.remote.SendEmailResponse
import com.soar.client.data.remote.SoarApiService
import com.soar.client.data.util.HtmlTextCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Offline-first repository for emails.
 *
 * Guarantees zero-latency startup by serving cached emails from Room DB immediately.
 * Syncs the 100 most recent emails in the background, and loads 50 older emails on demand
 * when the user scrolls past the initial 100.
 */
class EmailRepository(
    private val dao: EmailDao,
    private var apiService: SoarApiService
) {
    private var nextPageToken: String? = null
    private var sentNextPageToken: String? = null
    private var isFetchingMore = false
    private var isFetchingMoreSent = false

    fun updateApiService(newService: SoarApiService) {
        this.apiService = newService
    }

    /**
     * Clean inbox stream (isAutoBlocked = false).
     * Delivers immediate data from disk cache, auto-updates when background sync finishes.
     */
    val inboxEmails: Flow<List<EmailEntity>> = dao.getInboxEmails()

    /**
     * Outbox / Sent emails stream.
     */
    val sentEmails: Flow<List<EmailEntity>> = dao.getSentEmails()

    /**
     * Dedicated Auto Blocked stream (isAutoBlocked = true).
     * Holds only emails quarantined for severe phishing risks.
     */
    val autoBlockedEmails: Flow<List<EmailEntity>> = dao.getAutoBlockedEmails()

    fun getEmailById(msgId: String): Flow<EmailEntity?> = dao.getEmailById(msgId)

    /**
     * Background sync: Downloads the 100 most recent emails.
     * Caches them in Room DB, separating clean emails from auto-blocked phishing emails.
     */
    suspend fun syncRecent100Emails(email: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getInbox(email = email, maxResults = 100, pageToken = null, label = "INBOX")
            nextPageToken = response.nextPageToken

            var order = response.messages.size.toLong()
            val entities = response.messages.map { dto ->
                dto.toEntity(sortOrder = order--, folder = "INBOX")
            }

            dao.insertAll(entities)
            Log.i("EmailRepository", "Synced ${entities.size} recent emails into local Room cache")
            Result.success(entities.size)
        } catch (e: Exception) {
            Log.e("EmailRepository", "Error syncing 100 recent emails: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Background sync: Downloads 100 most recent Sent / Outbox messages.
     */
    suspend fun syncRecentSentEmails(email: String, maxResults: Int = 100): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getInbox(email = email, maxResults = maxResults, pageToken = null, label = "SENT")
            sentNextPageToken = response.nextPageToken
            var order = response.messages.size.toLong()
            val entities = response.messages.map { dto ->
                dto.toEntity(sortOrder = order--, folder = "SENT")
            }

            dao.insertAll(entities)
            Log.i("EmailRepository", "Synced ${entities.size} sent emails into local Room cache")
            Result.success(entities.size)
        } catch (e: Exception) {
            Log.e("EmailRepository", "Error syncing sent emails: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        dao.clearAll()
    }

    /**
     * Pagination: Loads 50 older emails when scrolled to the end of the Inbox list.
     */
    suspend fun loadMore50PastEmails(email: String): Result<Int> = withContext(Dispatchers.IO) {
        if (isFetchingMore || nextPageToken == null) {
            return@withContext Result.success(0)
        }
        isFetchingMore = true
        try {
            val response = apiService.getInbox(email = email, maxResults = 50, pageToken = nextPageToken, label = "INBOX")
            nextPageToken = response.nextPageToken

            var order = -(System.currentTimeMillis() % 100000)
            val entities = response.messages.map { dto ->
                dto.toEntity(sortOrder = order--, folder = "INBOX")
            }

            dao.insertAll(entities)
            Log.i("EmailRepository", "Loaded ${entities.size} older past emails via pagination")
            Result.success(entities.size)
        } catch (e: Exception) {
            Log.e("EmailRepository", "Pagination error: ${e.message}")
            Result.failure(e)
        } finally {
            isFetchingMore = false
        }
    }

    /**
     * Pagination: Loads 50 older sent emails when scrolled to the end of the Sent list.
     */
    suspend fun loadMore50PastSentEmails(email: String): Result<Int> = withContext(Dispatchers.IO) {
        if (isFetchingMoreSent || sentNextPageToken == null) {
            return@withContext Result.success(0)
        }
        isFetchingMoreSent = true
        try {
            val response = apiService.getInbox(email = email, maxResults = 50, pageToken = sentNextPageToken, label = "SENT")
            sentNextPageToken = response.nextPageToken

            var order = -(System.currentTimeMillis() % 100000)
            val entities = response.messages.map { dto ->
                dto.toEntity(sortOrder = order--, folder = "SENT")
            }

            dao.insertAll(entities)
            Log.i("EmailRepository", "Loaded ${entities.size} older sent emails via pagination")
            Result.success(entities.size)
        } catch (e: Exception) {
            Log.e("EmailRepository", "Sent pagination error: ${e.message}")
            Result.failure(e)
        } finally {
            isFetchingMoreSent = false
        }
    }

    /**
     * Fetch complete email detail and body (plain text / HTML).
     * Updates local cache so subsequent views load instantly offline.
     */
    suspend fun fetchEmailDetail(msgId: String, email: String): Result<EmailDetailDto> = withContext(Dispatchers.IO) {
        try {
            val detail = apiService.getEmailDetail(msgId = msgId, email = email)
            // Update cached entity with body content
            val existing = dao.getEmailDirect(msgId)
            if (existing != null) {
                dao.update(
                    existing.copy(
                        bodyText = detail.bodyText ?: "",
                        bodyHtml = detail.bodyHtml ?: "",
                        threatCount = detail.threats.size,
                        verdict = detail.verdict,
                        confidence = detail.confidence
                    )
                )
            }
            Result.success(detail)
        } catch (e: Exception) {
            Log.e("EmailRepository", "Error fetching email detail $msgId: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Move an email to Gmail Trash and remove from local database.
     */
    suspend fun trashEmail(msgId: String, email: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            apiService.trashEmail(msgId = msgId, email = email)
            dao.deleteById(msgId)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("EmailRepository", "Failed to trash email $msgId: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Restore an auto-blocked email back to the normal inbox.
     */
    suspend fun restoreToInbox(msgId: String) = withContext(Dispatchers.IO) {
        dao.updateAutoBlocked(msgId, isBlocked = false)
    }

    /**
     * Compose & send email. Also caches sent message immediately into Room DB.
     */
    suspend fun sendEmail(payload: SendEmailPayload): Result<SendEmailResponse> = withContext(Dispatchers.IO) {
        try {
            val resp = apiService.sendEmail(payload)
            if (resp.status == "sent" || resp.msgId != null) {
                val sentMsgId = resp.msgId ?: "sent_${System.currentTimeMillis()}"
                val sentEntity = EmailEntity(
                    msgId = sentMsgId,
                    threadId = resp.threadId ?: payload.threadId ?: "",
                    senderEmail = payload.email,
                    senderDisplay = "Me",
                    recipient = payload.to.joinToString(", "),
                    subject = payload.subject.ifBlank { "(No Subject)" },
                    date = "Just now",
                    snippet = payload.body.take(120),
                    isUnread = false,
                    hasAttachment = payload.attachments.isNotEmpty(),
                    attachmentCount = payload.attachments.size,
                    verdict = "safe",
                    confidence = 1.0f,
                    threatCount = 0,
                    urlsExtracted = 0,
                    isAutoBlocked = false,
                    bodyText = payload.body,
                    bodyHtml = "",
                    folder = "SENT",
                    sortOrder = System.currentTimeMillis()
                )
                dao.insert(sentEntity)
            }
            Result.success(resp)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun InboxMessageDto.toEntity(sortOrder: Long, folder: String = "INBOX"): EmailEntity {
        // High severity phishing automatically moves to the "Auto Blocked" section
        val isBlocked = folder == "INBOX" && (
                verdict.equals("phishing", ignoreCase = true) && confidence >= 0.80f
        )

        return EmailEntity(
            msgId = msgId,
            threadId = threadId ?: "",
            senderEmail = senderEmail ?: "",
            senderDisplay = senderDisplay ?: "",
            recipient = recipient ?: "",
            subject = subject ?: "(No Subject)",
            date = date ?: "",
            snippet = HtmlTextCleaner.clean(snippet),
            isUnread = isUnread,
            hasAttachment = hasAttachment,
            attachmentCount = attachmentCount,
            verdict = verdict,
            confidence = confidence,
            threatCount = threatCount,
            urlsExtracted = urlsExtracted,
            isAutoBlocked = isBlocked,
            folder = folder,
            sortOrder = sortOrder
        )
    }
}
