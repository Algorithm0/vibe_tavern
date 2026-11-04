package com.vibetavern.client.data

import com.vibetavern.client.data.ServerProfileRepository.AddResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerProfileRepositoryTest {

    private class MemoryStore(var text: String? = null) : TextStore {
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    /** Reversible stand-in for the Keystore cipher; makes plaintext leaks obvious. */
    private object FakeCipher : SecretCipher {
        override fun encrypt(plain: String) = "enc(" + plain.reversed() + ")"
        override fun decrypt(encrypted: String) =
            if (encrypted.startsWith("enc(")) encrypted.removePrefix("enc(").removeSuffix(")").reversed() else null
    }

    private var counter = 0
    private fun repo(store: MemoryStore = MemoryStore()) = ServerProfileRepository(store, FakeCipher) { "id${++counter}" }

    @Test
    fun localProfileIsAlwaysAvailableAndNeedsNoToken() {
        val s = repo().state.value
        assertEquals(listOf(ServerProfile.LOCAL), s.all)
        assertTrue(s.all.first().isLocal)
        assertNull(s.all.first().token)
        assertEquals("http://127.0.0.1:8787", s.all.first().connection().baseUrl)
    }

    @Test
    fun addsRemoteFromQrLinkTakingTheTokenFromIt() {
        val r = repo()
        val result = r.addRemote("http://192.168.1.5:8787/#token=abc123") as AddResult.Added
        assertEquals("http://192.168.1.5:8787", result.profile.baseUrl)
        assertEquals("abc123", result.profile.token)
        assertEquals("192.168.1.5:8787", result.profile.name)
        assertEquals(listOf(ServerProfile.LOCAL, result.profile), r.state.value.all)
    }

    @Test
    fun typedTokenWinsOverTheOneInTheLink() {
        val result = repo().addRemote("192.168.1.5#token=fromlink", name = "Home PC", tokenOverride = "  typed ") as AddResult.Added
        assertEquals("typed", result.profile.token)
        assertEquals("Home PC", result.profile.name)
    }

    @Test
    fun invalidAddressIsRejectedAndNothingIsStored() {
        val store = MemoryStore()
        assertEquals(AddResult.InvalidAddress, repo(store).addRemote("not an address"))
        assertNull(store.text)
    }

    @Test
    fun addingTheSameServerAgainUpdatesInsteadOfDuplicating() {
        val r = repo()
        r.addRemote("http://h.local:8787#token=old")
        val again = r.addRemote("HTTP://H.LOCAL:8787", tokenOverride = "new") as AddResult.Updated
        assertEquals("new", again.profile.token)
        assertEquals(1, r.state.value.remotes.size)
        // Re-adding without a token keeps the one we have.
        val third = r.addRemote("http://h.local:8787") as AddResult.Updated
        assertEquals("new", third.profile.token)
    }

    @Test
    fun tokensAreNeverWrittenInPlaintext() {
        val store = MemoryStore()
        repo(store).addRemote("http://h:1#token=SECRET-TOKEN")
        assertFalse(store.text!!.contains("SECRET-TOKEN"))
        assertTrue(store.text!!.contains("enc("))
    }

    @Test
    fun survivesRestart() {
        val store = MemoryStore()
        val first = repo(store)
        val added = first.addRemote("http://h:1#token=tok", name = "Laptop") as AddResult.Added
        first.setActive(added.profile.id)

        val second = repo(store)
        assertEquals(added.profile, second.state.value.remotes.single())
        assertEquals(added.profile.id, second.state.value.activeId)
        assertEquals("tok", second.state.value.active!!.token)
    }

    @Test
    fun localCanBeTheActiveProfile() {
        val store = MemoryStore()
        repo(store).setActive(ServerProfile.LOCAL_ID)
        assertTrue(repo(store).state.value.active!!.isLocal)
    }

    @Test
    fun unknownActiveIdIsIgnored() {
        val r = repo()
        r.setActive("ghost")
        assertNull(r.state.value.activeId)
    }

    @Test
    fun removingTheActiveProfileClearsSelection() {
        val r = repo()
        val p = (r.addRemote("http://h:1") as AddResult.Added).profile
        r.setActive(p.id)
        r.remove(p.id)
        assertNull(r.state.value.activeId)
        assertTrue(r.state.value.remotes.isEmpty())
    }

    @Test
    fun undecryptableTokenMeansNoTokenNotACrash() {
        val store = MemoryStore("""{"remotes":[{"id":"a","name":"X","baseUrl":"http://h:1","token":"garbage"}]}""")
        val p = repo(store).state.value.remotes.single()
        assertNull(p.token)
        assertEquals("http://h:1", p.baseUrl)
    }

    @Test
    fun corruptStoreYieldsEmptyState() {
        assertTrue(repo(MemoryStore("{not json")).state.value.remotes.isEmpty())
        assertTrue(repo(MemoryStore("[]")).state.value.remotes.isEmpty())
    }

    @Test
    fun entriesMissingRequiredFieldsAreSkippedAndLocalIdCannotBeHijacked() {
        val store = MemoryStore(
            """{"remotes":[{"name":"no id"},{"id":"local","baseUrl":"http://evil:1"},{"id":"ok","baseUrl":"http://h:1"}],"activeId":"ok"}""",
        )
        val s = repo(store).state.value
        assertEquals(listOf("ok"), s.remotes.map { it.id })
        assertEquals("ok", s.activeId)
    }

    @Test
    fun staleActiveIdIsDroppedOnLoad() {
        val store = MemoryStore("""{"remotes":[],"activeId":"gone"}""")
        assertNull(repo(store).state.value.activeId)
    }

    @Test
    fun renameAndTokenUpdate() {
        val r = repo()
        val p = (r.addRemote("http://h:1") as AddResult.Added).profile
        r.rename(p.id, "  Desk  "); r.rename(p.id, "   ")
        r.updateToken(p.id, " t ")
        val now = r.state.value.remotes.single()
        assertEquals("Desk", now.name)
        assertEquals("t", now.token)
        r.updateToken(p.id, null)
        assertNull(r.state.value.remotes.single().token)
    }
}
