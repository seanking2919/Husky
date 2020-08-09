package com.keylesspalace.tusky.db

import androidx.room.*
import java.util.*

@Entity
data class ChatEntity (
        @field:PrimaryKey val localId: Long, /* our user account id */
        @field:PrimaryKey val chatId: String,
        val accountId: String,
        val unread: Long,
        val updatedAt: Long,
        val lastMessageId: String?,
)

data class ChatEntityWithAccount (
        @Embedded val chat: ChatEntity,
        @Embedded val account: TimelineAccountEntity,
        @Embedded val lastMessage: ChatMessageEntity
)