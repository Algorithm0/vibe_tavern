package com.vibetavern.client.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RpTextTest {
    private fun p(s: String) = RpText.parse(s)

    @Test fun plainTextIsOneSegment() = assertEquals(listOf(RpSegment("Hello there")), p("Hello there"))

    @Test fun asterisksBecomeItalicWithoutMarkers() = assertEquals(
        listOf(RpSegment("She smiles. "), RpSegment("waves", italic = true), RpSegment(" Hi.")),
        p("She smiles. *waves* Hi."),
    )

    @Test fun doubleAsterisksAreBold() = assertEquals(
        listOf(RpSegment("a "), RpSegment("b", bold = true), RpSegment(" c")),
        p("a **b** c"),
    )

    @Test fun quotesAreHighlightedAndKept() = assertEquals(
        listOf(RpSegment("He said "), RpSegment("\"go\"", quoted = true), RpSegment(" and left.")),
        p("He said \"go\" and left."),
    )

    @Test fun italicActionAroundQuotedSpeech() = assertEquals(
        listOf(RpSegment("looks up", italic = true), RpSegment(" "), RpSegment("\"Hi\"", quoted = true)),
        p("*looks up* \"Hi\""),
    )

    @Test fun strayMarkersStayLiteral() {
        assertEquals(listOf(RpSegment("2 * 3 = 6")), p("2 * 3 = 6"))
        assertEquals(listOf(RpSegment("5\" tall")), p("5\" tall"))
    }

    @Test fun aMarkerDoesNotReachAcrossParagraphs() = assertEquals(
        listOf(RpSegment("one * two\n\nthree * four")),
        p("one * two\n\nthree * four"),
    )

    @Test fun curlyQuotes() = assertEquals(
        listOf(RpSegment("Say "), RpSegment("“hi”", quoted = true)),
        p("Say “hi”"),
    )

    @Test fun emptyText() = assertEquals(emptyList<RpSegment>(), p(""))

    @Test fun textIsNeverLostOrReordered() {
        val s = "*a* \"b\" **c** d * e \" f"
        val plain = p(s).joinToString("") { it.text }
        assertEquals("a \"b\" c d * e \" f", plain)
    }
}
