package com.jaredxwos.coralie.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jaredxwos.coralie.data.library.model.PageCapabilities

@Entity(
    tableName = "html",
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
data class HtmlPageEntity(
    @PrimaryKey(autoGenerate = true)
    val assetId: Long = 0,
    val name: String,
    val spaceId: Long,
    val sourceUri: String,
    @ColumnInfo(defaultValue = "0")
    val capabilityMask: Long = PageCapabilities.NONE_MASK,
)
