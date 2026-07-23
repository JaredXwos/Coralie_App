package com.jaredxwos.coralie.data.library.model

data class PageSummary(
    val assetId: Long,
    val spaceId: Long,
    val name: String,
    val spaceName: String,
    val capabilities: PageCapabilities,
)
