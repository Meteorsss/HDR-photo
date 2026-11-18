package com.meteorsss.hdrphoto

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream

object MotionPhotoSupport {
    private fun <T> read(context: Context, uri: Uri, block: (FileInputStream, Long, Long) -> T): T? = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
            fd.createInputStream().use { input ->
                val total = fd.length.takeIf { it >= 0 } ?: (input.channel.size() - fd.startOffset)
                if (total < 28) null else block(input, fd.startOffset, total)
            }
        }
    }.getOrNull()

    /** Return a validated video range, never the still image's own ftyp (HEIC/AVIF). */
    private fun range(input: FileInputStream, base: Long, total: Long): Pair<Long, Long>? {
        val bytes = ByteArray(minOf(total, 512L*1024).toInt())
        val reader = java.io.DataInputStream(input)
        reader.readFully(bytes)
        val length = MotionLayout.videoLength(String(bytes, Charsets.ISO_8859_1))
        if (length != null && length < total) {
            val start = base + total - length
            input.channel.position(start)
            val box = ByteArray(12); reader.readFully(box)
            if (MotionLayout.isVideoBox(box, length)) return start to length
        }
        // Samsung SEF uses MotionPhoto_Data immediately before its embedded MP4.
        // Check the footer first; ordinary stills must not trigger a whole-file scan.
        val footer = ByteArray(4); input.channel.position(base + total - 4); reader.readFully(footer)
        if (String(footer, Charsets.US_ASCII) != "SEFT") return null
        val marker = "MotionPhoto_Data".toByteArray(Charsets.US_ASCII)
        val block = ByteArray(65536 + marker.size + 12); var pos = 0L
        while (pos < total) {
            input.channel.position(base + pos)
            val n = minOf(block.size.toLong(), total-pos).toInt(); reader.readFully(block, 0, n)
            for (i in 0..n-marker.size-12) {
                if (block[i] != marker[0] || !marker.indices.all { block[i+it] == marker[it] }) continue
                val k = i+marker.size; val start = pos+k
                if (MotionLayout.isVideoBox(block.copyOfRange(k,k+12), total-start)) return (base+start) to (total-start)
            }
            pos += 65536
        }
        return null
    }

    fun hasMotionPhotoMetadata(context: Context, uri: Uri): Boolean =
        read(context, uri) { stream, base, total -> range(stream, base, total) != null } == true

    fun extractEmbeddedVideo(context: Context, uri: Uri): Uri? = read(context, uri) { input, base, total ->
        val (start, length) = range(input, base, total) ?: return@read null
        val dir = File(context.cacheDir, "motion-photo").apply { mkdirs() }
        val out = File.createTempFile("live-", ".mp4", dir)
        var ok = false
        try {
            input.channel.position(start)
            out.outputStream().use { sink ->
                val buf = ByteArray(65536); var remaining = length
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
                    if (n <= 0 || Thread.currentThread().isInterrupted) return@read null
                    sink.write(buf, 0, n); remaining -= n
                }
            }
            ok = true; Uri.fromFile(out)
        } finally { if (!ok) out.delete() }
    }
    fun discard(uri: Uri?) { if (uri?.scheme == "file") uri.path?.let { File(it).delete() } }
}
