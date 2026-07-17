package dev.branchlens

import dev.branchlens.util.TextUtil
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUtilTest {
    @Test
    fun utf8SizeCountsAsciiAndMultibyteCharactersWithoutEncodingTheWholeFile() {
        assertFalse(TextUtil.utf8SizeExceeds("abc", 3))
        assertTrue(TextUtil.utf8SizeExceeds("abc", 2))
        assertFalse(TextUtil.utf8SizeExceeds("é", 2))
        assertTrue(TextUtil.utf8SizeExceeds("é", 1))
        assertFalse(TextUtil.utf8SizeExceeds("😀", 4))
        assertTrue(TextUtil.utf8SizeExceeds("😀", 3))
    }
}
