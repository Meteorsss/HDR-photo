package com.meteorsss.hdrphoto

import org.junit.Assert.*
import org.junit.Test

class RegressionTest {
    @Test fun refreshPreservesCurrentRoute() {
        listOf(GalleryRoute(), GalleryRoute(true), GalleryRoute(true,"camera")).forEach {
            assertEquals(it,it.afterRefresh(setOf("camera")))
        }
    }
    @Test fun missingBucketReturnsToAlbumList() = assertEquals(GalleryRoute(true), GalleryRoute(true,"gone").afterRefresh(emptySet()))
    @Test fun independentScrollKeys() = assertEquals(3, listOf(GalleryRoute(),GalleryRoute(true),GalleryRoute(true,"albums")).map { it.key }.toSet().size)
    @Test fun motionMetadataUsesVideoItemNotGainMap() {
        val xmp = """<Container:Item Item:Length="42" Item:Mime="image/jpeg" Item:Semantic="GainMap"/><Container:Item Item:Length="1456" Item:Semantic="MotionPhoto" Item:Mime="video/mp4"/>"""
        assertEquals(1456L, MotionLayout.videoLength(xmp))
        assertEquals(420L, MotionLayout.videoLength("""GCamera:MicroVideoOffset="420""""))
        assertNull(MotionLayout.videoLength("MotionPhoto video/mp4"))
    }
    @Test fun stillFtypIsNotVideo() {
        for (brand in listOf("heic","avif","mif1","mp42")) {
            val box = byteArrayOf(0,0,0,24) + ("ftyp"+brand).toByteArray()
            assertEquals(brand == "mp42", MotionLayout.isVideoBox(box, 28)); assertFalse(MotionLayout.isVideoBox(box, 18))
        }
    }
}
