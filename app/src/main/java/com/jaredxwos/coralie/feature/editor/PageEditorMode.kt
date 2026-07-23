package com.jaredxwos.coralie.feature.editor

sealed interface PageEditorMode {
    data object Create : PageEditorMode

    data class Edit(
        val assetId: Long,
    ) : PageEditorMode
}
