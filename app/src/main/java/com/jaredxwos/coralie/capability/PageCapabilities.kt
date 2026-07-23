package com.jaredxwos.coralie.capability

/** Native features that an individual imported HTML page may use. */
enum class PageCapability(
    val bit: Long,
    val wireName: String,
) {
    MESH(1L shl 0, "mesh"),
    STORAGE(1L shl 1, "storage"),
    HTTP(1L shl 2, "http"),
    TIMERS(1L shl 3, "timers"),
}

/**
 * Immutable bit-mask wrapper for the capabilities stored on an HTML asset.
 * Unknown future bits are preserved in [mask].
 */
data class PageCapabilities(
    val mask: Long,
) {
    fun allows(capability: PageCapability): Boolean =
        mask and capability.bit != 0L

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
