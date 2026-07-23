package com.jaredxwos.coralie.data.database.entity

import androidx.room.Entity

@Entity(
    tableName = "domain",
    primaryKeys = ["domainUri"],
)
data class AllowedDomainEntity(
    val domainUri: String,
)
