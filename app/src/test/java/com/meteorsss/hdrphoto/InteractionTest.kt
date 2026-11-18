package com.meteorsss.hdrphoto

import org.junit.Assert.*
import org.junit.Test

class InteractionTest {
    @Test fun unchangedListsReportCompletionIncludingEmptyLists() {
        val rows = listOf(1,2,3)
        assertEquals("照片列表 已刷新，无变化", RefreshChanges.compare(rows, rows.reversed()) { it }.describe("照片列表"))
        assertEquals("相册列表 已刷新，无变化", RefreshChanges.compare(emptyList<Int>(),emptyList()) { it }.describe("相册列表"))
    }
    @Test fun detectChangesEvenWhenCountStaysTheSame() {
        val previous = listOf(1 to "A",2 to "B"); val next = listOf(1 to "AA",3 to "C")
        assertEquals(RefreshChanges(1,1,1), RefreshChanges.compare(previous,next) { it.first })
    }
    @Test fun pagesShareAnEdgeThroughoutBothDirections() {
        val width=900f
        for(step in listOf(-1,1)) {
            val end=PageSlide.targetOffset(step,width)
            for (i in 0..10) {
                val x=end*i/10; assertEquals(step*width,PageSlide.neighborOffset(step,width,x)-x,0.01f)
            }
            assertEquals(0f,PageSlide.neighborOffset(step,width,end),0.01f)
            assertTrue(PageSlide.duration(end*.2f,end,width)>PageSlide.duration(end*.9f,end,width))
        }
    }
}
