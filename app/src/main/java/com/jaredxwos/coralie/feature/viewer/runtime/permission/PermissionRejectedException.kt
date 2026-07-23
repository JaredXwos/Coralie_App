package com.jaredxwos.coralie.feature.viewer.runtime.permission

enum class PermissionScope {
    CAPABILITY,
    DOMAIN,
}

class PermissionRejectedException(
    val scope: PermissionScope,
    val target: String,
    val operation: String,
) : SecurityException(
    "Permission rejected: scope=${scope.name.lowercase()} " +
        "target=$target operation=$operation",
)
