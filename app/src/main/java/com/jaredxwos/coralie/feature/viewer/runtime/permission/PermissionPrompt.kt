package com.jaredxwos.coralie.feature.viewer.runtime.permission

import com.jaredxwos.coralie.data.library.model.PageCapability

sealed interface SessionPermissionPrompt

data class CapabilityPermissionPrompt(
    val capability: PageCapability,
) : SessionPermissionPrompt

data class DomainPermissionPrompt(
    val domain: String,
) : SessionPermissionPrompt
