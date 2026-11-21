package com.qgming.zlib.core.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * EAPI is loosely typed: the same field may be a JSON string, number, boolean or null.
 * These helpers normalise access without strict DTOs.
 */
internal fun JsonObject.stringOrNull(key: String): String? {
    val element = this[key] ?: return null
    return element.stringOrNull()
}

internal fun JsonObject.stringOrEmpty(key: String): String = stringOrNull(key).orEmpty()

internal fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNullValue()

internal fun JsonObject.longOrNull(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNullValue()

internal fun JsonObject.doubleOrNull(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNullValue()

internal fun JsonObject.boolOrNull(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNullValue()

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonElement.stringOrNull(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    val content = primitive.content
    return content.ifBlank { null }
}

internal fun JsonPrimitive.intOrNullValue(): Int? =
    intOrNull ?: content.trim().toIntOrNull()

internal fun JsonPrimitive.longOrNullValue(): Long? =
    longOrNull ?: content.trim().toLongOrNull() ?: content.trim().toDoubleOrNull()?.toLong()

internal fun JsonPrimitive.doubleOrNullValue(): Double? =
    doubleOrNull ?: content.trim().toDoubleOrNull()

internal fun JsonPrimitive.booleanOrNullValue(): Boolean? =
    when (content.trim().lowercase()) {
        "1", "true", "yes" -> true
        "0", "false", "no" -> false
        else -> null
    }

internal fun JsonElement.asStringList(): List<String> = when (this) {
    is JsonArray -> flatMap { element ->
        when (element) {
            is JsonPrimitive -> listOfNotNull(element.stringOrNull())
            is JsonObject -> listOfNotNull(
                element.stringOrNull("name")
                    ?: element.stringOrNull("title")
                    ?: element.stringOrNull("path"),
            )
            else -> emptyList()
        }
    }
    is JsonPrimitive -> listOfNotNull(stringOrNull())
    else -> emptyList()
}
