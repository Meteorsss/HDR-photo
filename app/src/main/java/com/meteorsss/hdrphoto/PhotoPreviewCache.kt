package com.meteorsss.hdrphoto

import android.graphics.Bitmap
import android.util.LruCache

object PhotoPreviewCache {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    @Synchronized
    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }

    @Synchronized
    fun get(key: String): Bitmap? = cache.get(key)
}
