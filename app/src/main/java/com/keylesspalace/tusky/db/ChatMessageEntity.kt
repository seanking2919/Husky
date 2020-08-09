package com.keylesspalace.tusky.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.*

/*
 * ChatMessage model
 */

@Entity
data class ChatMessageEntity(
        @field:PrimaryKey val localId: Long,
        @field:PrimaryKey val messageId: String,
        val content: String,
        val chatId: String,
        val accountId: String,
        val createdAt: Long,
        val attachment: String?,
        val emojis: String
)