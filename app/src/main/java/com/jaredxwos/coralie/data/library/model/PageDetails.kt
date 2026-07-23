package com.jaredxwos.coralie.data.library.model

import android.net.Uri

data class PageDetails(
    val assetId: Long,
    val spaceId: Long,
    val name: String,
    val sourceUri: Uri,
    val capabilities: PageCapabilities,
)
