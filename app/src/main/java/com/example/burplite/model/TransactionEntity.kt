package com.example.burplite.model

import androidx.room.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey val id: String,
    val method: String,
    val url: String,
    val requestHeaders: String, // JSON-encoded map
    val requestBody: ByteArray,
    val statusCode: Int?,
    val responseHeaders: String?, // JSON-encoded map
    val responseBody: ByteArray?,
    val isHttps: Boolean,
    val timestamp: Long
)

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TransactionEntity)

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    suspend fun getAll(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: String): TransactionEntity?

    @Query("DELETE FROM transactions")
    suspend fun clearAll()
}

/**
 * Rehydrates a persisted row into a (read-only, already-resolved)
 * HttpTransaction so the existing detail/repeater screens can display
 * it without a separate view type. Its resumeSignal is pre-completed
 * since there's nothing left to intercept/pause.
 */
fun TransactionEntity.toReadOnlyTransaction(): HttpTransaction {
    val reqHeaders: Map<String, String> = Json.decodeFromString(requestHeaders)
    val tx = HttpTransaction(
        id = id,
        request = EditableRequest(
            method = method, url = url,
            headers = reqHeaders.toMutableMap(), body = requestBody
        ),
        isHttps = isHttps,
        timestamp = timestamp
    )
    tx.forward() // no-op resume, just marks it as "already handled" for UI purposes
    if (statusCode != null) {
        val respHeaders: Map<String, String> = responseHeaders?.let { Json.decodeFromString(it) } ?: emptyMap()
        tx.response = HttpResponseSnapshot(statusCode, respHeaders, responseBody ?: ByteArray(0))
    }
    return tx
}

@Database(entities = [TransactionEntity::class], version = 1, exportSchema = false)
abstract class BurpLiteDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao

    companion object {
        @Volatile private var INSTANCE: BurpLiteDatabase? = null
        fun get(context: android.content.Context): BurpLiteDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, BurpLiteDatabase::class.java, "burplite.db"
                ).build().also { INSTANCE = it }
            }
    }
}
