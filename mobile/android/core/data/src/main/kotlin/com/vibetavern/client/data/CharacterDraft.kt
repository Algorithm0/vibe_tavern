package com.vibetavern.client.data

import com.vibetavern.client.model.CharacterRecord
import com.vibetavern.client.network.patchOf
import kotlinx.serialization.json.JsonObject

/**
 * The editable part of a character card, as plain strings the form can bind to.
 *
 * [toCreateFields] builds a create request; [diff] builds a PATCH containing only what
 * changed, so saving never overwrites fields another client edited in the meantime and
 * never touches the ones this screen does not show (avatar, extensions, ...).
 */
data class CharacterDraft(
    val name: String = "",
    val description: String = "",
    val personalitySummary: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val mesExample: String = "",
    /** `always` | `once` | `depth` | `disabled`. */
    val mesExampleMode: String = "always",
    val mesExampleDepth: Int = 4,
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val creatorNotes: String = "",
    val depthPrompt: String = "",
    val depthPromptDepth: Int = 4,
    val depthPromptRole: String = "system",
    val tags: List<String> = emptyList(),
) {
    val isValid: Boolean get() = name.isNotBlank()

    /** Tags as the comma separated text the form edits. */
    val tagsText: String get() = tags.joinToString(", ")

    fun withTagsText(text: String): CharacterDraft = copy(tags = parseTags(text))

    fun toCreateFields(): JsonObject {
        require(isValid) { "A character needs a name" }
        val fields = mutableListOf<Pair<String, Any?>>(
            "name" to name.trim(),
            "description" to description,
            "firstMessage" to firstMessage,
            "scenario" to scenario,
            "mesExample" to mesExample,
            "mesExampleMode" to mesExampleMode,
            "mesExampleDepth" to mesExampleDepth,
            "alternateGreetings" to alternateGreetings.filter(String::isNotBlank),
            "postHistoryInstructions" to postHistoryInstructions,
            "creatorNotes" to creatorNotes,
            "systemPrompt" to systemPrompt,
            "tags" to tags,
        )
        // The create schema takes a nullable summary but non-null depth prompt strings.
        fields += "personalitySummary" to personalitySummary.ifBlank { null }
        if (depthPrompt.isNotBlank()) {
            fields += "depthPrompt" to depthPrompt
            fields += "depthPromptDepth" to depthPromptDepth
            fields += "depthPromptRole" to depthPromptRole
        }
        return patchOf(*fields.toTypedArray())
    }

    /** Only the fields that differ from [original]. Empty when nothing changed. */
    fun diff(original: CharacterRecord): JsonObject {
        val base = fromRecord(original)
        val changes = mutableListOf<Pair<String, Any?>>()

        fun text(key: String, new: String, old: String, nullable: Boolean) {
            if (new != old) changes += key to (if (nullable && new.isBlank()) null else new)
        }
        // `name` is never cleared: the server restores an empty name and that is a data-loss trap.
        if (name.isNotBlank() && name.trim() != base.name) changes += "name" to name.trim()
        text("description", description, base.description, nullable = false)
        text("personalitySummary", personalitySummary, base.personalitySummary, nullable = true)
        text("scenario", scenario, base.scenario, nullable = false)
        text("systemPrompt", systemPrompt, base.systemPrompt, nullable = false)
        text("firstMessage", firstMessage, base.firstMessage, nullable = true)
        text("mesExample", mesExample, base.mesExample, nullable = true)
        text("postHistoryInstructions", postHistoryInstructions, base.postHistoryInstructions, nullable = true)
        text("creatorNotes", creatorNotes, base.creatorNotes, nullable = true)
        text("depthPrompt", depthPrompt, base.depthPrompt, nullable = true)
        if (mesExampleMode != base.mesExampleMode) changes += "mesExampleMode" to mesExampleMode
        if (mesExampleDepth != base.mesExampleDepth) changes += "mesExampleDepth" to mesExampleDepth
        if (depthPromptDepth != base.depthPromptDepth && depthPrompt.isNotBlank()) changes += "depthPromptDepth" to depthPromptDepth
        if (depthPromptRole != base.depthPromptRole && depthPrompt.isNotBlank()) changes += "depthPromptRole" to depthPromptRole
        val greetings = alternateGreetings.filter(String::isNotBlank)
        if (greetings != base.alternateGreetings) changes += "alternateGreetings" to greetings
        if (tags != base.tags) changes += "tags" to tags
        return patchOf(*changes.toTypedArray())
    }

    fun hasChangesComparedTo(original: CharacterRecord): Boolean = diff(original).isNotEmpty()

    companion object {
        fun fromRecord(c: CharacterRecord) = CharacterDraft(
            name = c.name,
            description = c.description,
            personalitySummary = c.personalitySummary.orEmpty(),
            scenario = c.scenario,
            firstMessage = c.firstMessage.orEmpty(),
            alternateGreetings = c.alternateGreetings,
            mesExample = c.mesExample.orEmpty(),
            mesExampleMode = c.mesExampleMode,
            mesExampleDepth = c.mesExampleDepth,
            systemPrompt = c.systemPrompt,
            postHistoryInstructions = c.postHistoryInstructions.orEmpty(),
            creatorNotes = c.creatorNotes.orEmpty(),
            depthPrompt = c.depthPrompt.orEmpty(),
            depthPromptDepth = c.depthPromptDepth ?: 4,
            depthPromptRole = c.depthPromptRole ?: "system",
            tags = c.tags,
        )

        fun parseTags(text: String): List<String> =
            text.split(',', '\n').map(String::trim).filter(String::isNotEmpty).distinct()
    }
}
