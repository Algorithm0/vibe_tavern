package com.vibetavern.client.data

import com.vibetavern.client.model.CharacterRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterDraftTest {

    // Shaped like a real card returned by the server (null for never-filled optional text).
    private val card = CharacterRecord(
        id = "c1", name = "Aria", description = "A fire mage.", scenario = "{{user}} enters the tower.",
        personalitySummary = "Bold, curious", firstMessage = "*looks up*", mesExample = null,
        mesExampleMode = "always", mesExampleDepth = 4, alternateGreetings = listOf("*glares*"),
        postHistoryInstructions = null, creatorNotes = null, tags = listOf("fantasy", "mage"),
    )

    @Test
    fun untouchedDraftProducesNoPatch() {
        val draft = CharacterDraft.fromRecord(card)
        assertTrue(draft.diff(card).isEmpty())
        assertFalse(draft.hasChangesComparedTo(card))
    }

    @Test
    fun onlyChangedFieldsAreSent() {
        val patch = CharacterDraft.fromRecord(card).copy(description = "An ancient fire mage.", mesExampleDepth = 6).diff(card)
        assertEquals(setOf("description", "mesExampleDepth"), patch.keys)
        assertEquals(JsonPrimitive("An ancient fire mage."), patch["description"])
        assertEquals(JsonPrimitive(6), patch["mesExampleDepth"])
    }

    @Test
    fun clearingANullableFieldSendsExplicitNull() {
        val patch = CharacterDraft.fromRecord(card).copy(firstMessage = "", personalitySummary = "  ").diff(card)
        assertEquals(JsonNull, patch["firstMessage"])
        assertEquals(JsonNull, patch["personalitySummary"])
    }

    @Test
    fun fillingAFieldThatWasNullSendsTheText() {
        val patch = CharacterDraft.fromRecord(card).copy(creatorNotes = "For fantasy RP.").diff(card)
        assertEquals(JsonPrimitive("For fantasy RP."), patch["creatorNotes"])
    }

    @Test
    fun nonNullableFieldsAreSentAsEmptyStringNotNull() {
        val patch = CharacterDraft.fromRecord(card).copy(description = "", scenario = "").diff(card)
        assertEquals(JsonPrimitive(""), patch["description"])
        assertEquals(JsonPrimitive(""), patch["scenario"])
    }

    @Test
    fun aBlankNameIsNeverSent() {
        assertTrue(CharacterDraft.fromRecord(card).copy(name = "   ").diff(card).isEmpty())
        assertEquals(JsonPrimitive("Aria the Wise"), CharacterDraft.fromRecord(card).copy(name = " Aria the Wise ").diff(card)["name"])
    }

    @Test
    fun listsAreComparedByContentAndBlankGreetingsDropped() {
        val same = CharacterDraft.fromRecord(card).copy(alternateGreetings = listOf("*glares*", "  "))
        assertTrue(same.diff(card).isEmpty())
        val changed = CharacterDraft.fromRecord(card).copy(alternateGreetings = listOf("*glares*", "*waves*"), tags = listOf("mage"))
        val patch = changed.diff(card)
        assertEquals(JsonArray(listOf(JsonPrimitive("*glares*"), JsonPrimitive("*waves*"))), patch["alternateGreetings"])
        assertEquals(JsonArray(listOf(JsonPrimitive("mage"))), patch["tags"])
    }

    @Test
    fun depthPromptDepthAndRoleOnlyMatterWithADepthPrompt() {
        val d = CharacterDraft.fromRecord(card)
        assertTrue(d.copy(depthPromptDepth = 9, depthPromptRole = "user").diff(card).isEmpty())
        val patch = d.copy(depthPrompt = "Stay in character.", depthPromptDepth = 9).diff(card)
        assertEquals(setOf("depthPrompt", "depthPromptDepth"), patch.keys)
    }

    @Test
    fun tagParsing() {
        assertEquals(listOf("a", "b", "c"), CharacterDraft.parseTags(" a, b ,,c,\nb"))
        assertEquals("x, y", CharacterDraft().withTagsText("x,y").tagsText)
    }

    @Test
    fun createFieldsCarryTheCardAndOmitEmptyDepthPrompt() {
        val fields = CharacterDraft.fromRecord(card).toCreateFields()
        assertEquals(JsonPrimitive("Aria"), fields["name"])
        assertEquals(JsonPrimitive("Bold, curious"), fields["personalitySummary"])
        assertFalse(fields.containsKey("depthPrompt"))
        val withDepth = CharacterDraft.fromRecord(card).copy(depthPrompt = "x").toCreateFields()
        assertTrue(withDepth.containsKey("depthPromptDepth") && withDepth.containsKey("depthPromptRole"))
    }

    @Test
    fun createFieldsRejectABlankName() {
        try { CharacterDraft(name = " ").toCreateFields(); error("expected failure") } catch (_: IllegalArgumentException) {}
        assertFalse(CharacterDraft(name = " ").isValid)
    }

    @Test
    fun blankPersonalitySummaryIsSentAsNullOnCreate() {
        val fields = CharacterDraft(name = "X").toCreateFields()
        assertEquals(JsonNull, fields["personalitySummary"])
    }
}
