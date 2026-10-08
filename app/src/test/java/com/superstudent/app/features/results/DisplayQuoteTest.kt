package com.superstudent.app.features.results

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayQuoteTest {

    @Test
    fun `unescapes the five predefined XML entities`() {
        assertEquals(
            "epsilon>0 且 |x_n - a| < epsilon 与 'q' 和 \"q\"",
            displayQuote("epsilon&gt;0 且 |x_n - a| &lt; epsilon 与 &apos;q&apos; 和 &quot;q&quot;"),
        )
    }

    @Test
    fun `does not double-unescape an already escaped entity`() {
        assertEquals("&lt;", displayQuote("&amp;lt;"))
    }

    @Test
    fun `leaves plain text untouched`() {
        assertEquals("第 1 页 引用片段", displayQuote("第 1 页 引用片段"))
    }
}
