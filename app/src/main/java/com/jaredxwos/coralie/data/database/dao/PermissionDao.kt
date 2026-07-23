package com.jaredxwos.coralie.data.database.dao

import androidx.room.Dao
import androidx.room.Query

@Dao
internal interface PermissionDao {
    @Query("SELECT domainUri FROM domain ORDER BY domainUri COLLATE NOCASE")
    suspend fun retrieveAllDomains(): List<String>

    @Query(
        "SELECT EXISTS(" +
            "SELECT 1 FROM domain WHERE domainUri = :domain" +
            ")",
    )
    suspend fun isDomainAllowed(
        domain: String,
    ): Boolean

    @Query(
        "INSERT OR IGNORE INTO domain (domainUri) VALUES (:domain)",
    )
    suspend fun allowDomain(domain: String)

    @Query("DELETE FROM domain WHERE domainUri = :domain")
    suspend fun disallowDomain(domain: String)
}
