package com.example.sosapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class SosMessage(
    @PrimaryKey val id: String,
    val senderId: String,
    val content: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val isMyMessage: Boolean,
    val hopCount: Int = 0,
    val equipment: String? = null, // e.g., "First Aid Kit", "Water", etc.
    val isUploaded: Boolean = false
)