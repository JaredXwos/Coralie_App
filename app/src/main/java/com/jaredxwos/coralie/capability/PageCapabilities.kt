package com.jaredxwos.coralie.capability

/** Native features that an individual imported HTML page may use. */
enum class PageCapability(
    val bit: Long,
    val wireName: String,
) {
    MESH(1L, "mesh"),
    STORAGE(2L, "storage"),
    HTTP(4L, "http"),
    TIMERS(8L, "timers"),
}

/**
 * Immutable capability policy attached to one HTML asset.
 *
 * Unknown future bits are preserved in [mask] but ignored by the current app.
 * This makes database rows forwards-compatible while keeping enforcement
 * limited to capabilities understood by this build.
 */
data class PageCapabilities(
    val mask: Long,
) {
    fun allows(capability: PageCapability): Boolean =
        mask and capability.bit != 0L

    fun require(
        capability: PageCapability,
        operation: String,
    ) {
        if (!allows(capability)) {
            throw SecurityException(
                "Coralie capability '${capability.wireName}' is not granted " +
                    "for operation '$operation'",
            )
        }
    }

    fun asSet(): Set<PageCapability> =
        PageCapability.entries
            .filterTo(linkedSetOf(), ::allows)

    fun toJson(): String =
        PageCapability.entries
            .filter(::allows)
            .joinToString(
                prefix = "[",
                postfix = "]",
                separator = ",",
            ) { capability ->
                "\"${capability.wireName}\""
            }

    companion object {
        const val NONE_MASK: Long = 0L

        val NONE = PageCapabilities(NONE_MASK)

        fun from(capabilities: Iterable<PageCapability>): PageCapabilities =
            PageCapabilities(
                capabilities.fold(0L) { mask, capability ->
                    mask or capability.bit
                },
            )
    }
}
