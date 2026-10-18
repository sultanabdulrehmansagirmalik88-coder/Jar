package com.jarvis.one

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room

@Entity(tableName = "messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: String,
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "notes")
data class UserNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class ChatSummary(
    val chatId: String,
    val preview: String?,
    val updatedAt: Long
)

@Dao
interface ChatDao {
    @Insert
    suspend fun insert(message: ChatMessage)

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id")
    suspend fun messages(chatId: String): List<ChatMessage>

    @Query("SELECT chatId, (SELECT content FROM messages AS firstMessage WHERE firstMessage.chatId = messages.chatId AND firstMessage.role = 'user' ORDER BY id LIMIT 1) AS preview, MAX(createdAt) AS updatedAt FROM messages GROUP BY chatId ORDER BY updatedAt DESC")
    suspend fun chatSummaries(): List<ChatSummary>

    @Query("SELECT chatId, (SELECT content FROM messages AS firstMessage WHERE firstMessage.chatId = messages.chatId AND firstMessage.role = 'user' ORDER BY id LIMIT 1) AS preview, MAX(createdAt) AS updatedAt FROM messages WHERE chatId IN (SELECT chatId FROM messages WHERE content LIKE '%' || :query || '%') GROUP BY chatId ORDER BY updatedAt DESC")
    suspend fun searchChats(query: String): List<ChatSummary>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id DESC LIMIT :limit")
    suspend fun latest(chatId: String, limit: Int): List<ChatMessage>

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND role = 'user' ORDER BY id DESC LIMIT 1")
    suspend fun latestUserMessage(chatId: String): ChatMessage?

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    @Query("DELETE FROM messages WHERE id = (SELECT id FROM messages WHERE chatId = :chatId AND role = 'assistant' ORDER BY id DESC LIMIT 1)")
    suspend fun deleteLatestAssistantMessage(chatId: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun clearChat(chatId: String)

    @Query("DELETE FROM messages")
    suspend fun clearAll()

    @Insert
    suspend fun insertNote(note: UserNote)

    @Query("SELECT * FROM notes ORDER BY id DESC")
    suspend fun notes(): List<UserNote>

    @Query("SELECT * FROM notes WHERE text LIKE '%' || :query || '%' ORDER BY id DESC")
    suspend fun searchNotes(query: String): List<UserNote>

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: Long)
}

@Database(entities = [ChatMessage::class, UserNote::class], version = 1, exportSchema = false)
abstract class ChatDatabase : androidx.room.RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile private var instance: ChatDatabase? = null
        fun get(context: Context): ChatDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ChatDatabase::class.java,
                "jarvis1.db"
            ).build().also { instance = it }
        }
    }
}
