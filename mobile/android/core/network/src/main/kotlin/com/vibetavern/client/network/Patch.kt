package com.vibetavern.client.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Builds a JSON object from `name to value` pairs where `null` becomes an explicit JSON null (clears the field). */
fun patchOf(vararg fields: Pair<String, Any?>): JsonObject = JsonObject(
    fields.associate { (key, value) ->
        key to when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is List<*> -> JsonArray(value.map { JsonPrimitive(it.toString()) })
            else -> error("Unsupported patch value for '$key': ${value::class}")
        }
    },
)
