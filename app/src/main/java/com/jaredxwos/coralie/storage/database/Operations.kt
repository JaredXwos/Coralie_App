package com.jaredxwos.coralie.storage.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.serialization.Serializable

@Serializable
data class DataEntry(
    val name: String,
    val value: String,
)

@Dao
interface AppDao {
    @Query("INSERT INTO spaces (name) VALUES (:name)")
    suspend fun insertSpace(name: String): Long

    @Query("SELECT * FROM spaces")
    suspend fun retrieveAllSpaces(): List<Space>

    @Query("DELETE FROM spaces WHERE spaceId = :spaceId")
    suspend fun deleteSpace(spaceId: Long)

    @Query(
        """
        INSERT INTO html (spaceId, name, sourceUri, capabilityMask)
        VALUES (:spaceId, :name, :sourceUri, :capabilityMask)
        """,
    )
    suspend fun createHtml(
        spaceId: Long,
        name: String,
        sourceUri: String,
        capabilityMask: Long,
    ): Long

    @Query("SELECT * FROM html")
    suspend fun retrieveAllHtml(): List<Html>

    @Query("SELECT * FROM html WHERE assetId = :assetId")
    suspend fun retrieveHtml(assetId: Long): Html?

    @Query("SELECT sourceUri FROM html WHERE assetId = :assetId")
    suspend fun retrieveUri(assetId: Long): String

    @Query("SELECT EXISTS(SELECT 1 FROM html WHERE sourceUri = :sourceUri)")
    suspend fun uriExists(sourceUri: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM html WHERE spaceId = :spaceId)")
    suspend fun isSpaceLinked(spaceId: Long): Boolean

    @Query(
        """
        UPDATE html
        SET spaceId = :spaceId,
            name = :name,
            sourceUri = :sourceUri,
            capabilityMask = :capabilityMask
        WHERE assetId = :assetId
        """,
    )
    suspend fun updateHtml(
        assetId: Long,
        spaceId: Long,
        name: String,
        sourceUri: String,
        capabilityMask: Long,
    )

    @Query("DELETE FROM html WHERE assetId = :assetId")
    suspend fun deleteHtml(assetId: Long)

    @Query("INSERT INTO store (spaceId, name, value, tag) VALUES (:spaceId, :name, :value, :tag)")
    suspend fun createValue(spaceId: Long, name: String, value: String, tag: String?)

    @Query("SELECT EXISTS(SELECT 1 FROM store WHERE spaceId = :spaceId AND name = :name)")
    suspend fun nameExists(spaceId: Long, name: String): Boolean

    @Query("SELECT name, value FROM store WHERE spaceId = :spaceId AND tag = :tag")
    suspend fun retrieveAllWithTag(spaceId: Long, tag: String): List<DataEntry>

    @Query("SELECT tag FROM store WHERE spaceId = :spaceId AND name = :name")
    suspend fun retrieveTag(spaceId: Long, name: String): String?

    @Query("SELECT value FROM store WHERE spaceId = :spaceId AND name = :name")
    suspend fun retrieveValue(spaceId: Long, name: String): String?

    @Query("UPDATE store SET value = :value WHERE spaceId = :spaceId AND name = :name")
    suspend fun updateValueFailSilent(spaceId: Long, name: String, value: String)

    @Transaction
    suspend fun updateValue(spaceId: Long, name: String, value: String, upsert: Boolean) {
        if (nameExists(spaceId, name)) {
            updateValueFailSilent(spaceId, name, value)
        } else if (upsert) {
            createValue(spaceId, name, value, tag = null)
        } else {
            throw NoSuchElementException("No entry named '$name' in this scope")
        }
    }

    @Query("UPDATE store SET tag = :tag WHERE spaceId = :spaceId AND name = :name")
    suspend fun updateTag(spaceId: Long, name: String, tag: String?)

    @Query("DELETE FROM store WHERE spaceId = :spaceId AND name = :name")
    suspend fun deleteData(spaceId: Long, name: String)

    @Query("DELETE FROM store WHERE spaceId = :spaceId")
    suspend fun clearSpace(spaceId: Long)

    @Query("SELECT domainUri FROM domain")
    suspend fun retrieveAllDomain(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM domain WHERE domainUri = :domain)")
    suspend fun domainAllowed(domain: String): Boolean

    @Query("INSERT INTO domain (domainUri) VALUES (:domain)")
    suspend fun allowDomain(domain: String)

    @Query("DELETE FROM domain WHERE domainUri = :domain")
    suspend fun disallowDomain(domain: String)
}
