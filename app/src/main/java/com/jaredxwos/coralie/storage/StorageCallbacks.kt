package com.jaredxwos.coralie.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// Resolves the open space's storage, or fails in a way dispatch turns into ERROR.
private fun requireStorage(): HtmlStorage =
    AppStorage.current ?: throw IllegalStateException("No space is currently open")

// ---- DTOs: one per callback's params shape ----
@Serializable private data class NameParam(val name: String)
@Serializable private data class CreateValueParams(val name: String, val value: String, val tag: String? = null)
@Serializable private data class UpdateValueParams(val name: String, val value: String, val upsert: Boolean = true)
@Serializable private data class SetTagParams(val name: String, val tag: String? = null)
@Serializable private data class TagParam(val tag: String)

// ---- Callbacks: decode params -> resolve storage -> call -> getOrThrow -> encode ----
suspend fun storageCreateValue(params: JsonElement): JsonElement {
    val p = Json.decodeFromJsonElement< CreateValueParams>(params)
    val value = requireStorage().createValue(p.name, p.value, p.tag).getOrThrow()
    return JsonNull
}
suspend fun storageRetrieveValue(params: JsonElement): JsonElement {
    val p = Json.decodeFromJsonElement<NameParam>(params)
    val value = requireStorage().retrieveValue(p.name).getOrThrow()
    return JsonPrimitive(value)
}

suspend fun storageUpdateValue(params: JsonElement): JsonElement {
    val p = Json.decodeFromJsonElement<UpdateValueParams>(params)
    requireStorage().updateValue(p.name, p.value, p.upsert).getOrThrow()
    return JsonNull
}

suspend fun storageDeleteValue(params: JsonElement): JsonElement {
    val p = Json.decodeFromJsonElement<NameParam>(params)
    requireStorage().deleteItem(p.name).getOrThrow()
    return JsonNull
}

suspend fun storageGetTag(params: JsonElement): JsonElement {
    val p = Json.decodeFromJsonElement<NameParam>(params)
    val tag = requireStorage().getTag(p.name).getOrThrow()
    return if (tag == null) JsonNull else JsonPrimitive(tag)
}

suspend fun storageSetTag(params: JsonElement): JsonElement {
    val p = Json.decodeFromJsonElement<SetTagParams>(params)
    requireStorage().setTag(p.name, p.tag).getOrThrow()
    return JsonNull
}

suspend fun storageGetAllWithTag(params: JsonElement): JsonElement {
    val p = Json.decodeFromJsonElement<TagParam>(params)
    val entries = requireStorage().getAllWithTag(p.tag).getOrThrow()
    return Json.encodeToJsonElement(entries)
}

suspend fun storageClear(params: JsonElement): JsonElement {
    requireStorage().clear().getOrThrow()
    return JsonNull
}