package com.deivid22srk.qwenbridge.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// --- ENTITIES ---

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val email: String,
    val cookies: String,       // Ex: "cookie_name=cookie_value; ..."
    val userAgent: String,
    val isActive: Boolean = true,
    val lastLoggedIn: Long = System.currentTimeMillis()
)

@Entity(tableName = "logs")
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val level: String,         // INFO, WARN, ERROR, DEBUG
    val message: String
)

@Entity(tableName = "session_mappings")
data class SessionMappingEntity(
    @PrimaryKey val conversationKey: String,
    val chatSessionId: String,
    val parentMessageId: String?,
    val updatedAt: Long = System.currentTimeMillis()
)

// --- DAOs ---

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY lastLoggedIn DESC")
    fun getAllAccountsFlow(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY lastLoggedIn DESC")
    suspend fun getAllAccounts(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE email = :email LIMIT 1")
    suspend fun getAccountByEmail(email: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE email = :email")
    suspend fun deleteAccount(email: String)
}

@Dao
interface LogDao {
    @Query("SELECT * FROM logs ORDER BY timestamp DESC LIMIT 200")
    fun getRecentLogsFlow(): Flow<List<LogEntity>>

    @Insert
    suspend fun insertLog(log: LogEntity)

    @Query("DELETE FROM logs")
    suspend fun clearLogs()
}

@Dao
interface SessionMappingDao {
    @Query("SELECT * FROM session_mappings WHERE conversationKey = :conversationKey LIMIT 1")
    suspend fun getMapping(conversationKey: String): SessionMappingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMapping(mapping: SessionMappingEntity)

    @Query("DELETE FROM session_mappings WHERE conversationKey = :conversationKey")
    suspend fun deleteMapping(conversationKey: String)

    @Query("DELETE FROM session_mappings")
    suspend fun clearMappings()
}

// --- DATABASE ---

@Database(
    entities = [AccountEntity::class, LogEntity::class, SessionMappingEntity::class],
    version = 1,
    exportSchema = false
)
abstract class QwenBridgeDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun logDao(): LogDao
    abstract fun sessionMappingDao(): SessionMappingDao

    companion object {
        @Volatile
        private var INSTANCE: QwenBridgeDatabase? = null

        fun getDatabase(context: Context): QwenBridgeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    QwenBridgeDatabase::class.java,
                    "qwenbridge_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
