package com.jaredxwos.coralie.data.library.model

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
