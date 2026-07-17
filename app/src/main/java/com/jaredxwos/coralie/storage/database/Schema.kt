package com.jaredxwos.coralie.storage.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// SCHEMA
// ──────────────────────────────────────────────────────
// Spaces   ( space_id PK AI NN, name NN )
// Html     ( asset_id PK AI NN, name NN, space_id FK → Spaces.space_id NN, sourceUri NN, )
// Data     ( space_id PK FK → Spaces.space_id NN, name PK NN, value NN, tag )
// Uri      ( uri PK NN )
// ──────────────────────────────────────────────────────
// FK cascade: deleting a Space deletes its Html and Data rows
// AI: Auto increment

@Entity(tableName = "spaces")
data class Space(
    @PrimaryKey(autoGenerate = true) val spaceId: Long = 0,
    val name: String
)

@Entity(
    tableName = "html",
    foreignKeys = [ForeignKey(
        entity = Space::class,
        parentColumns = ["spaceId"],
        childColumns = ["spaceId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("spaceId")]
)
data class Html(
    @PrimaryKey(autoGenerate = true) val assetId: Long = 0,
    val name: String,
    val spaceId: Long,
    val sourceUri: String
)

@Entity(
    tableName = "store",
    primaryKeys = ["spaceId", "name"],
    foreignKeys = [ForeignKey(
        entity = Space::class,
        parentColumns = ["spaceId"],
        childColumns = ["spaceId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("spaceId")]
)
data class Entry(
    val spaceId: Long,
    val name: String,
    val value: String,
    val tag: String?
)

@Entity(
    tableName = "domain",
    primaryKeys = ["domainUri"]
)
data class UriEntry(val domainUri: String)