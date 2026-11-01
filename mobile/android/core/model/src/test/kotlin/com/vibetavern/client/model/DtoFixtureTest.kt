package com.vibetavern.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decodes real responses captured from a running Vibe Tavern server (mobile/android/fixtures). */
class DtoFixtureTest {

    private fun fixture(name: String): String =
        DtoFixtureTest::class.java.classLoader.getResourceAsStream(name)!!.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun patch(name: String): SessionPatch = VtJson.decodeFromString(SessionPatch.serializer(), fixture(name))

    @Test
    fun firstRunBootstrapHasNoChat() {
        val b = VtJson.decodeFromString(BootstrapState.serializer(), fixture("bootstrap_initial.json"))
        assertTrue(b.isFirstRun)
        assertNull(b.initialChatId)
        assertNull(b.snapshot)
        assertTrue(b.allCharacters.isEmpty())
        assertEquals("Default", b.promptPresets.single().name)
    }

    @Test
    fun populatedBootstrapCarriesSnapshot() {
        val b = VtJson.decodeFromString(BootstrapState.serializer(), fixture("bootstrap_full.json"))
        assertNotNull(b.initialChatId)
        assertEquals(b.initialChatId, b.snapshot!!.activeChat!!.id)
    }

    @Test
    fun chatSnapshotDecodesMessagesAndVariants() {
        val s = patch("chat_after_send.json")
        val greeting = s.messages!![0]
        assertEquals("assistant", greeting.role)
        assertEquals(2, greeting.variants.size)
        assertEquals(0, greeting.selectedVariantIndex)
        assertEquals("*looks up from her spellbook*", greeting.selectedVariant!!.content)

        val reply = s.messages!!.last()
        assertEquals("*smiles* Hello, traveler. Welcome to the tower.", reply.content)
        assertEquals("thinking about a greeting", reply.reasoning)
        assertEquals(149L, reply.selectedVariant!!.reasoningDurationMs)
        assertEquals("mock-1", reply.modelId)
        assertTrue(s.messages!![1].isUser)
        assertNull(s.messages!![1].reasoning)
    }

    @Test
    fun characterRecordNullablesAndDefaults() {
        val c = patch("character_create.json").let {
            VtJson.decodeFromString(ImportResult.serializer(), fixture("character_create.json")).snapshot!!.character!!
        }
        assertEquals("Aria", c.name)
        assertEquals(listOf("*glares at you*"), c.alternateGreetings)
        assertEquals(listOf("fantasy", "mage"), c.tags)
        assertNull(c.mesExample)          // server sends null
        assertEquals("always", c.mesExampleMode)
        assertEquals(4, c.mesExampleDepth)
        assertFalse(c.includeAvatarInPrompt)
    }

    @Test
    fun importResultFromCharacterImportKeepsWarnings() {
        val r = VtJson.decodeFromString(ImportResult.serializer(), fixture("import_json.json"))
        assertEquals("character", r.imported!!.kind)
        assertEquals(1, r.imported!!.warningCount)
        assertEquals("Character card has no scenario.", r.imported!!.warnings.single())
        assertNotNull(r.characterId)
    }

    @Test
    fun narrowResponsesDecodeAsPartialPatches() {
        val rename = patch("chat_rename.json")
        assertNotNull(rename.chats)
        assertNull(rename.messages)
        assertNull(rename.character)

        val variant = patch("variant_select.json")
        assertNotNull(variant.messages)
        assertNull(variant.chats)

        val edit = patch("message_edit.json")           // also carries promptTrace, which we ignore
        assertTrue(edit.messages!!.any { it.content.endsWith("(edited)") })

        val patchResponse = patch("character_patch.json")
        assertEquals("Aria the Wise", patchResponse.character!!.name)
    }

    @Test
    fun branchResponsesCarryBranches() {
        val fork = patch("chat_fork.json")
        assertTrue(fork.branches!!.size >= 2)
        assertNotNull(fork.activeBranch)
    }

    @Test
    fun providerProfileDropsSecrets() {
        val p = VtJson.decodeFromString(ProviderProfile.serializer(), fixture("provider_create.json"))
        assertTrue(p.hasStoredApiKey)
        assertEquals("openai-compatible", p.providerPreset)
        assertEquals("mock-1", p.defaultModel)
    }

    @Test
    fun avatarUploadResult() {
        val r = VtJson.decodeFromString(AvatarUploadResult.serializer(), fixture("avatar_upload.json"))
        assertEquals("webp", r.avatarExt)
        assertEquals("png", r.avatarFullExt)
    }

    @Test
    fun avatarsAreDetectedByExtensionNotAssetId() {
        // Verified against a live server: uploads set avatarExt and leave avatarAssetId null.
        val entry = CharacterListEntry(id = "c", avatarExt = "webp", avatarAssetId = null)
        assertTrue(entry.hasAvatar)
        assertFalse(CharacterListEntry(id = "c").hasAvatar)
    }

    @Test
    fun unknownKeysAndNullsForDefaultedFieldsDoNotBreakDecoding() {
        val json = """{"id":"m1","role":null,"content":null,"somethingNew":{"x":1},"variants":null,"position":3}"""
        val m = VtJson.decodeFromString(MessageDto.serializer(), json)
        assertEquals("assistant", m.role)     // coerceInputValues → default
        assertEquals("", m.content)
        assertTrue(m.variants.isEmpty())
        assertEquals(3, m.position)
    }

    @Test
    fun mergeKeepsUntouchedRegionsAndReplacesTouchedOnes() {
        val base = SessionState().merge(patch("chat_after_send.json"))
        assertEquals(3, base.messages.size)
        val renamed = base.merge(patch("chat_rename.json"))
        assertEquals(3, renamed.messages.size)                       // untouched
        assertEquals("Tower talk", renamed.chats.first { it.id == base.activeChat!!.id }.title)
        assertEquals(base.character, renamed.character)               // untouched
        val afterDelete = renamed.merge(patch("message_delete.json"))
        assertTrue(afterDelete.messages.size < 3)                     // replaced
        assertEquals(renamed.chats, afterDelete.chats)                // absent → preserved
    }
}
