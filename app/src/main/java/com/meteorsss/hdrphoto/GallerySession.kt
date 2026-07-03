package com.meteorsss.hdrphoto

object GallerySession {
    var photos: List<PhotoItem> = emptyList()
        private set
    var index: Int = 0
        private set

    fun setPhotos(items: List<PhotoItem>, selectedIndex: Int) {
        photos = items.toList()
        index = selectedIndex.coerceIn(0, items.lastIndex)
    }

    fun setIndex(newIndex: Int) {
        index = newIndex.coerceIn(0, photos.lastIndex)
    }
}
