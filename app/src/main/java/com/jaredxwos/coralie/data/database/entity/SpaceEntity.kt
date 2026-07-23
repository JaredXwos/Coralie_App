package com.jaredxwos.coralie.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "spaces")
data class SpaceEntity(
    @PrimaryKey(autoGenerate = true)
    val spaceId: Long = 0,
    val name: String,
)
