package com.meteorsss.hdrphoto

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri

object GallerySession {
    var photos: List<PhotoItem> = emptyList()
        private set
    var index: Int = 0
        private set
    var galleryPreview: Bitmap? = null
        private set
    var launchBounds: Rect? = null
        private set
    var launchUri: String? = null
        private set

    fun setPhotos(items: List<PhotoItem>, selectedIndex: Int) {
        photos = items.toList()
        index = selectedIndex.coerceIn(0, items.lastIndex)
    }

    fun setIndex(newIndex: Int) {
        index = newIndex.coerceIn(0, photos.lastIndex)
    }

    fun setLaunchPreview(bitmap: Bitmap?, bounds: Rect, uri: Uri) {
        galleryPreview?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
        galleryPreview = bitmap
        launchBounds = Rect(bounds)
        launchUri = uri.toString()
    }

    fun clearLaunchPreview() {
        galleryPreview?.takeIf { !it.isRecycled }?.recycle()
        galleryPreview = null
        launchBounds = null
        launchUri = null
    }
}
