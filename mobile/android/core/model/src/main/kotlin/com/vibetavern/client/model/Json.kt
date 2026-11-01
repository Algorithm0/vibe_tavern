package com.vibetavern.client.model

import kotlinx.serialization.json.Json

/**
 * The one [Json] configuration used for every wire payload.
 *
 * The Vibe Tavern server changes quickly (see docs/architecture), so the client is
 * deliberately forgiving: unknown keys are ignored, and a `null` for a non-null
 * property that has a default falls back to that default instead of failing the
 * whole response.
 */
val VtJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
}
