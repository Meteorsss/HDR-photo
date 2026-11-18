package com.meteorsss.hdrphoto

internal data class GalleryRoute(val albums: Boolean = false, val bucket: String? = null) {
    val key get() = if (!albums) "photos" else if (bucket == null) "albums" else "bucket:$bucket"
    fun afterRefresh(ids: Set<String>) = if (bucket != null && bucket !in ids) GalleryRoute(true) else this
}
