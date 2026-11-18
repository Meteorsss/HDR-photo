package com.meteorsss.hdrphoto

/** Motion Photo 1.0 requires the video to be the last media item. Legacy MicroVideoOffset is also EOF-relative. */
internal object MotionLayout {
    private val itemTag = Regex("""<[^<>]{0,4096}\b(?:\w+:)?Mime\s*=\s*["']video/(?:mp4|quicktime)["'][^<>]{0,4096}>""", RegexOption.IGNORE_CASE)
    private val length = Regex("""\b(?:\w+:)?Length\s*=\s*["'](\d+)["']""")
    private val semantic = Regex("""\b(?:\w+:)?Semantic\s*=\s*["']MotionPhoto["']""")
    private val legacy = Regex("""\b(?:\w+:)?MicroVideoOffset\s*=\s*["'](\d+)["']""")
    fun videoLength(header: String): Long? = (itemTag.findAll(header).map { it.value }
        .firstOrNull { semantic.containsMatchIn(it) }?.let { length.find(it)?.groupValues?.get(1) }
        ?: legacy.find(header)?.groupValues?.get(1))?.toLongOrNull()?.takeIf { it >= 16 }
    fun isVideoBox(bytes: ByteArray, available: Long): Boolean {
        if (bytes.size < 12 || String(bytes,4,4,Charsets.US_ASCII) != "ftyp") return false
        val size = (0..3).fold(0L) { n,i -> n*256 + (bytes[i].toInt() and 255) }
        val brand = String(bytes,8,4,Charsets.US_ASCII)
        return size in 16..available && brand in setOf("isom","iso2","iso4","iso5","iso6","mp41","mp42","avc1","hvc1","qt  ","3gp4","3gp5","MSNV","M4V ")
    }
}
