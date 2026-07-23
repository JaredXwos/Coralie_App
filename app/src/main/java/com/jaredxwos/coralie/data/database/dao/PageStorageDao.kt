package com.jaredxwos.coralie.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.jaredxwos.coralie.data.space.StoredValue

@Dao
internal interface PageStorageDao {
    @Query(
        """
        INSERT INTO store (spaceId, name, value, tag)
        VALUES (:spaceId, :name, :value, :tag)
        """,
    )
    suspend fun createValue(
        spaceId: Long,
        name: String,
        value: String,
        tag: String?,
    )

    @Query(
        "SELECT EXISTS(" +
            "SELECT 1 FROM store " +
            "WHERE spaceId = :spaceId AND name = :name" +
            ")",
    )
    suspend fun nameExists(
        spaceId: Long,
        name: String,
    ): Boolean

    @Query(
        "SELECT name, value FROM store " +
            "WHERE spaceId = :spaceId AND tag = :tag",
    )
    suspend fun retrieveAllWithTag(
        spaceId: Long,
        tag: String,
    ): List<StoredValue>

    @Query(
        "SELECT tag FROM store " +
            "WHERE spaceId = :spaceId AND name = :name",
    )
    suspend fun retrieveTag(
        spaceId: Long,
        name: String,
    ): String?

    @Query(
        "SELECT value FROM store " +
            "WHERE spaceId = :spaceId AND name = :name",
    )
    suspend fun retrieveValue(
        spaceId: Long,
        name: String,
    ): String?

    @Query(
        "UPDATE store SET value = :value " +
            "WHERE spaceId = :spaceId AND name = :name",
    )
    suspend fun updateValueFailSilent(
        spaceId: Long,
        name: String,
        value: String,
    )

    @Transaction
    suspend fun updateValue(
        spaceId: Long,
        name: String,
        value: String,
        upsert: Boolean,
    ) {
        if (nameExists(spaceId, name)) {
            updateValueFailSilent(
                spaceId = spaceId,
                name = name,
                value = value,
            )
        } else if (upsert) {
            createValue(
                spaceId = spaceId,
                name = name,
                value = value,
                tag = null,
            )
        } else {
            throw NoSuchElementException(
                "No entry named '$name' in this scope",
            )
        }
    }

    @Query(
        "UPDATE store SET tag = :tag " +
            "WHERE spaceId = :spaceId AND name = :name",
    )
    suspend fun updateTag(
        spaceId: Long,
        name: String,
        tag: String?,
    )

    @Query(
        "DELETE FROM store " +
            "WHERE spaceId = :spaceId AND name = :name",
    )
    suspend fun deleteValue(
        spaceId: Long,
        name: String,
    )

    @Query("DELETE FROM store WHERE spaceId = :spaceId")
    suspend fun clearSpace(spaceId: Long)
}
