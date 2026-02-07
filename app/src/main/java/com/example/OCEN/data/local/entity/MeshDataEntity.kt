package com.example.OCEN.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mesh_data")
data class MeshDataEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val content: String,
    val timestamp: Long,
    val deviceId: String,
    val isSynced: Boolean = false,
    val lastModified: Long = System.currentTimeMillis()
)