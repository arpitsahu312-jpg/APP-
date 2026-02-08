package com.example.sosapp.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SosDao {
    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<SosMessage>>

    @Query("SELECT * FROM messages WHERE isUploaded = 0 ORDER BY timestamp DESC")
    fun getUnuploadedMessages(): Flow<List<SosMessage>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: SosMessage)

    @Query("UPDATE messages SET isUploaded = 1 WHERE id = :messageId")
    suspend fun markAsUploaded(messageId: String)
}

@Database(entities = [SosMessage::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sosDao(): SosDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "sos_db")
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
