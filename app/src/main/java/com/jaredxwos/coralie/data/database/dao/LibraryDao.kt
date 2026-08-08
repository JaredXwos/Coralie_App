package com.jaredxwos.coralie.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.jaredxwos.coralie.data.database.entity.HtmlPageEntity
import com.jaredxwos.coralie.data.database.entity.SpaceEntity

@Dao
internal interface LibraryDao {
    @Query("INSERT INTO spaces (name) VALUES (:name)")
    suspend fun insertSpace(name: String): Long

    @Query("SELECT * FROM spaces ORDER BY name COLLATE NOCASE")
    suspend fun retrieveAllSpaces(): List<SpaceEntity>

    @Query("UPDATE spaces SET name = :name WHERE spaceId = :spaceId")
    suspend fun updateSpaceName(
        spaceId: Long,
        name: String,
    )

    @Query("DELETE FROM spaces WHERE spaceId = :spaceId")
    suspend fun deleteSpace(spaceId: Long)

    @Query(
        """
        INSERT INTO html (spaceId, name, sourceUri, capabilityMask)
        VALUES (:spaceId, :name, :sourceUri, :capabilityMask)
        """,
    )
    suspend fun createPage(
        spaceId: Long,
        name: String,
        sourceUri: String,
        capabilityMask: Long,
    ): Long

    @Query("SELECT * FROM html ORDER BY name COLLATE NOCASE")
    suspend fun retrieveAllPages(): List<HtmlPageEntity>

    @Query(
        "SELECT * FROM html WHERE spaceId = :spaceId " +
            "ORDER BY name COLLATE NOCASE",
    )
    suspend fun retrievePagesInSpace(
        spaceId: Long,
    ): List<HtmlPageEntity>

    @Query("SELECT * FROM html WHERE assetId = :assetId")
    suspend fun retrievePage(
        assetId: Long,
    ): HtmlPageEntity?

    @Query(
        "SELECT EXISTS(" +
            "SELECT 1 FROM html WHERE sourceUri = :sourceUri" +
            ")",
    )
    suspend fun sourceUriExists(
        sourceUri: String,
    ): Boolean

    @Query(
        "SELECT EXISTS(" +
            "SELECT 1 FROM html WHERE spaceId = :spaceId" +
            ")",
    )
    suspend fun spaceContainsPages(
        spaceId: Long,
    ): Boolean

    @Transaction
    suspend fun replacePage(
        assetId: Long,
        spaceId: Long,
        name: String,
        sourceUri: String,
        capabilityMask: Long,
    ): Long {
        if (retrievePage(assetId) != null) {
            deletePage(assetId)
        }

        return createPage(
            spaceId = spaceId,
            name = name,
            sourceUri = sourceUri,
            capabilityMask = capabilityMask,
        )
    }

    @Query(
        "UPDATE html SET capabilityMask = :capabilityMask " +
            "WHERE assetId = :assetId",
    )
    suspend fun updatePageCapabilities(
        assetId: Long,
        capabilityMask: Long,
    )

    @Query("UPDATE html SET sourceUri = :sourceUri WHERE assetId = :assetId")
    suspend fun updatePageSourceUri(
        assetId: Long,
        sourceUri: String,
    ): Int

    @Query("DELETE FROM html WHERE assetId = :assetId")
    suspend fun deletePage(assetId: Long)

    @Query("DELETE FROM html WHERE spaceId = :spaceId")
    suspend fun deletePagesInSpace(spaceId: Long)
}
