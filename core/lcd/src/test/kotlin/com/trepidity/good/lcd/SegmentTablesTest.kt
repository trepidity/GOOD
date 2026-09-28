package com.trepidity.good.lcd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SegmentTablesTest {
    @Test
    fun every_character_the_app_shows_lights_at_least_one_segment() {
        for (c in "0123456789-ABCDEFHLNOPRTUYabcdefhlnoprtuy") assertNotEquals("7-seg '$c'", 0, SevenSegment.mask(c))
        for (c in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-+/*") {
            assertNotEquals("14-seg '$c'", 0, FourteenSegment.mask(c))
        }
    }

    @Test
    fun characters_outside_the_fonts_render_blank_instead_of_throwing() {
        for (c in "?€\u0000Kw") assertEquals("7-seg '$c'", 0, SevenSegment.mask(c))
        for (c in "?€\u0000") assertEquals("14-seg '$c'", 0, FourteenSegment.mask(c))
    }

    @Test
    fun no_two_fourteen_segment_letters_look_the_same() {
        val letters = ('A'..'Z').groupBy { FourteenSegment.mask(it) }.values.filter { it.size > 1 }
        assertEquals(emptyList<List<Char>>(), letters)
    }
}
