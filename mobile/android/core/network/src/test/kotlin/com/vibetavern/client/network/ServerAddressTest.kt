package com.vibetavern.client.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerAddressTest {

    private fun ok(input: String) = ServerAddress.parse(input)!!

    @Test
    fun bareHostGetsHttpAndDefaultPort() {
        assertEquals(ParsedServerAddress("http://192.168.1.5:8787", null), ok("192.168.1.5"))
    }

    @Test
    fun hostWithPortKeepsPort() {
        assertEquals("http://192.168.1.5:9000", ok("192.168.1.5:9000").baseUrl)
        assertEquals("http://my-pc.local:8787", ok("my-pc.local:8787").baseUrl)
    }

    @Test
    fun explicitSchemeWithoutPortKeepsSchemeDefaultPort() {
        assertEquals("https://tavern.example.com", ok("https://tavern.example.com").baseUrl)
        assertEquals("http://tavern.example.com", ok("http://tavern.example.com").baseUrl)
    }

    @Test
    fun qrLinkWithFragmentToken() {
        val parsed = ok("http://192.168.1.5:8787/#token=abc123")
        assertEquals("http://192.168.1.5:8787", parsed.baseUrl)
        assertEquals("abc123", parsed.token)
    }

    @Test
    fun queryTokenAndPercentDecoding() {
        val parsed = ok("https://h.example.com:8443/?token=a%2Bb%3D")
        assertEquals("a+b=", parsed.token)
        assertEquals("https://h.example.com:8443", parsed.baseUrl)
    }

    @Test
    fun trailingSlashesAndWhitespaceAreTrimmed() {
        assertEquals("http://10.0.0.2:8787", ok("  http://10.0.0.2:8787/  ").baseUrl)
    }

    @Test
    fun keepsReverseProxyPathPrefix() {
        assertEquals("https://example.com/tavern", ok("https://example.com/tavern/").baseUrl)
    }

    @Test
    fun ipv6Literal() {
        assertEquals("http://[fd00::5]:8787", ok("[fd00::5]").baseUrl)
        assertEquals("http://[fd00::5]:9000", ok("[fd00::5]:9000").baseUrl)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(ServerAddress.parse(""))
        assertNull(ServerAddress.parse("   "))
        assertNull(ServerAddress.parse("two words"))
        assertNull(ServerAddress.parse("ftp://host"))
        assertNull(ServerAddress.parse("http://"))
    }

    @Test
    fun emptyTokenIsNoToken() {
        assertNull(ok("http://h:1/#token=").token)
    }
}
