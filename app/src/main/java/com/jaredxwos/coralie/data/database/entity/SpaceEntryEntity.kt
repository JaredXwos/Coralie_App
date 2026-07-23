package com.jaredxwos.coralie.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "store",
    primaryKeys = ["spaceId", "name"],
    foreignKeys = [
        ForeignKey(
            entity = SpaceEntity::class,
            parentColumns = ["spaceId"],
            childColumns = ["spaceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("spaceId")],
)
data class SpaceEntryEntity(
    val spaceId: Long,
    val name: String,
    val value: String,
    val tag: String?,
)
