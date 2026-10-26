package com.soar.client.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for local cached emails.
 * Uses reactive Kotlin Coroutine Flow to instantly notify UI of database changes.
 */
@Dao
interface EmailDao {

    /**
     * Clean inbox feed: returns only non-blocked emails in INBOX.
     * ZERO phishing analysis is displayed in the regular inbox.
     */
    @Query("SELECT * FROM emails WHERE isAutoBlocked = 0 AND folder = 'INBOX' ORDER BY sortOrder DESC, cachedAt DESC")
    fun getInboxEmails(): Flow<List<EmailEntity>>

    @Query("SELECT * FROM emails WHERE isAutoBlocked = 0 AND folder = 'INBOX' ORDER BY sortOrder DESC, cachedAt DESC")
    fun observeInboxEmails(): Flow<List<EmailEntity>>

    /**
     * Outbox / Sent emails feed.
     */
    @Query("SELECT * FROM emails WHERE folder = 'SENT' ORDER BY sortOrder DESC, cachedAt DESC")
    fun getSentEmails(): Flow<List<EmailEntity>>

    @Query("SELECT * FROM emails WHERE folder = 'SENT' ORDER BY sortOrder DESC, cachedAt DESC")
    fun observeSentEmails(): Flow<List<EmailEntity>>

    /**
     * Dedicated Auto Blocked feed: returns ONLY emails quarantined due to severe phishing risks.
     */
    @Query("SELECT * FROM emails WHERE isAutoBlocked = 1 ORDER BY cachedAt DESC")
    fun getAutoBlockedEmails(): Flow<List<EmailEntity>>

    @Query("SELECT * FROM emails WHERE msgId = :msgId LIMIT 1")
    fun getEmailById(msgId: String): Flow<EmailEntity?>

    @Query("SELECT * FROM emails WHERE msgId = :msgId LIMIT 1")
    suspend fun getEmailDirect(msgId: String): EmailEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(emails: List<EmailEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(email: EmailEntity)

    @Update
    suspend fun update(email: EmailEntity)

    @Query("DELETE FROM emails WHERE msgId = :msgId")
    suspend fun deleteById(msgId: String)

    @Query("UPDATE emails SET isAutoBlocked = :isBlocked WHERE msgId = :msgId")
    suspend fun updateAutoBlocked(msgId: String, isBlocked: Boolean)

    @Query("SELECT COUNT(*) FROM emails WHERE isAutoBlocked = 0")
    suspend fun getInboxCount(): Int

    @Query("SELECT COUNT(*) FROM emails WHERE isAutoBlocked = 1")
    suspend fun getBlockedCount(): Int

    @Query("DELETE FROM emails")
    suspend fun clearAll()
}
