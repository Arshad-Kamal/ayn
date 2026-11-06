package com.arshad.ayn.core.access

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The depth guard every outside JSON passes before decoding. */
class JsonBoundsTest {

    @Test
    fun bracketsInsideStringsDontCountAsNesting() {
        assertTrue(nestsWithin("""{"title":"[[[{{{ \"]]]"}""", maxDepth = 1))
        assertFalse(nestsWithin("""{"a":[1]}""", maxDepth = 1))
        assertFalse(nestsWithin("]][[", maxDepth = 4))
    }
}
