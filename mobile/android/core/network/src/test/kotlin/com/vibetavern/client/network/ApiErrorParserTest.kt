package com.vibetavern.client.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiErrorParserTest {

    @Test
    fun domainErrorFromRealServer() {
        // Captured: POST /api/chats/:id/messages with no provider configured.
        val e = ApiErrorParser.parse(
            400,
            """{"error":{"kind":"Validation","message":"No active provider profile. Activate one in Provider settings."}}""",
        )
        assertEquals("Validation", e.kind)
        assertEquals(400, e.status)
        assertEquals("No active provider profile. Activate one in Provider settings.", e.message)
    }

    @Test
    fun routerLevelStringError() {
        val e = ApiErrorParser.parse(404, """{"error":"Route not found: GET /api/characters/x"}""")
        assertTrue(e.isNotFound)
        assertEquals("Route not found: GET /api/characters/x", e.message)
    }

    @Test
    fun zodErrorIsHumanised() {
        val body = """{"success":false,"error":{"name":"ZodError","message":"[\n  {\n    \"expected\": \"string\",\n    \"code\": \"invalid_type\",\n    \"path\": [\n      \"content\"\n    ],\n    \"message\": \"Invalid input: expected string, received undefined\"\n  }\n]"}}"""
        val e = ApiErrorParser.parse(400, body)
        assertEquals("Validation", e.kind)
        assertEquals("content: Invalid input: expected string, received undefined", e.message)
    }

    @Test
    fun unauthorizedIsDetected() {
        val e = ApiErrorParser.parse(401, """{"error":{"kind":"Unauthorized","message":"Invalid or missing token"}}""")
        assertTrue(e.isUnauthorized)
        assertFalse(e.isNotFound)
    }

    @Test
    fun nonJsonBodyFallsBackToStatusKind() {
        val e = ApiErrorParser.parse(502, "Bad Gateway")
        assertEquals("Provider", e.kind)
        assertEquals("Bad Gateway", e.message)
    }

    @Test
    fun emptyBodyGetsDefaultMessage() {
        val e = ApiErrorParser.parse(500, "")
        assertEquals("Internal", e.kind)
        assertEquals("Request failed (HTTP 500)", e.message)
    }

    @Test
    fun sseErrorKeepsCategory() {
        val e = ApiErrorParser.fromSseData("""{"message":"Chat 'nope' was not found.","category":"unknown"}""")
        assertEquals("Chat 'nope' was not found.", e.message)
        assertEquals("unknown", e.category)
    }

    @Test
    fun sseErrorWithGarbagePayloadStillProducesAMessage() {
        assertEquals("boom", ApiErrorParser.fromSseData("boom").message)
    }

    @Test
    fun networkExceptionIsMarked() {
        assertTrue(ApiException.network(java.io.IOException("refused")).isNetwork)
    }
}
