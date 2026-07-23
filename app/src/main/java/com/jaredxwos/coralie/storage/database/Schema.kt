package com.jaredxwos.coralie.storage.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jaredxwos.coralie.capability.PageCapabilities

@Entity(tableName = "spaces")
data class Space(
    @PrimaryKey(autoGenerate = true) val spaceId: Long = 0,
    val name: String,
)

@Entity(
    tableName = "html",
    foreignKeys = [ForeignKey(
        entity = Space::class,
        parentColumns = ["spaceId"],
        childColumns = ["spaceId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("spaceId")],
)
data class Html(
    @PrimaryKey(autoGenerate = true) val assetId: Long = 0,
    val name: String,
    val spaceId: Long,
    val sourceUri: String,
    /** Bit mask defined by [PageCapabilities]. */
    @ColumnInfo(defaultValue = "0")
    val capabilityMask: Long = PageCapabilities.NONE_MASK,
)

@Entity(
    tableName = "store",
    primaryKeys = ["spaceId", "name"],
    foreignKeys = [ForeignKey(
        entity = Space::class,
        parentColumns = ["spaceId"],
        childColumns = ["spaceId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("spaceId")],
)
data class Entry(
    val spaceId: Long,
    val name: String,
    val value: String,
    val tag: String?,
)

@Entity(
    tableName = "domain",
    primaryKeys = ["domainUri"],
)
data class UriEntry(
    val domainUri: String,
)
