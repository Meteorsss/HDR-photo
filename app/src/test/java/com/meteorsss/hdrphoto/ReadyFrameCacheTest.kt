package com.meteorsss.hdrphoto

import org.junit.Assert.*
import org.junit.Test

class ReadyFrameCacheTest {
    private data class Frame(val bytes: Long)
    @Test fun handoffUsesSameFinalFrame() {
        val cache = ReadyFrameCache<String, Frame>(100) { it.bytes }
        val final = Frame(40)
        cache.put("next", final)
        assertSame(final, cache["next"])
        cache.retain(setOf("next"))
        assertSame(final, cache["next"])
    }
    @Test fun budgetDoesNotDestroyVisibleFramesOrLoopOnOversize() {
        val cache = ReadyFrameCache<String, Frame>(100) { it.bytes }
        val displayed = Frame(70)
        cache.put("old", displayed); cache.put("next", Frame(70))
        assertNull(cache["old"]); assertEquals(70L, displayed.bytes)
        val huge = Frame(120)
        cache.put("huge", huge); assertSame(huge, cache["huge"])
        assertNull(cache["next"])
        cache.retain(emptySet()); assertNull(cache["huge"])
    }
}
