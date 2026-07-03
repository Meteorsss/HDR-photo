package com.meteorsss.hdrphoto

import android.content.Context
import android.net.Uri
import java.io.File
import kotlin.math.max

object MotionPhotoSupport {
    private val markers = listOf(
        "MotionPhoto",
        "MicroVideo",
        "GCamera",
        "Camera:MotionPhoto",
        "MotionPhoto_Data",
        "Container:Directory",
    )
    private val ftyp = byteArrayOf(0x66, 0x74, 0x79, 0x70)

    fun hasMotionPhotoMetadata(context: Context, uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(512 * 1024)
                val read = input.read(buffer)
                if (read <= 0) {
                    false
                } else {
                    val header = String(buffer, 0, read, Charsets.ISO_8859_1)
                    markers.any { marker -> header.contains(marker, ignoreCase = true) }
                }
            } ?: false
        }.getOrDefault(false)
    }

    fun extractEmbeddedVideo(context: Context, uri: Uri): Uri? {
        return runCatching {
            val outputDir = File(context.cacheDir, "motion-photo").apply { mkdirs() }
            val output = File(outputDir, "${Integer.toHexString(uri.toString().hashCode())}.mp4")
            if (output.length() > 32) return Uri.fromFile(output)

            val start = findMp4Start(context, uri) ?: return null
            context.contentResolver.openInputStream(uri)?.use { input ->
                skipFully(input, start)
                output.outputStream().use { fileOut ->
                    input.copyTo(fileOut)
                }
            } ?: return null
            if (output.length() > 32) Uri.fromFile(output) else null
        }.getOrNull()
    }

    private fun findMp4Start(context: Context, uri: Uri): Long? {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(256 * 1024)
            var tail = ByteArray(0)
            var absolute = 0L

            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val combined = ByteArray(tail.size + read)
                tail.copyInto(combined)
                buffer.copyInto(combined, tail.size, 0, read)
                val base = absolute - tail.size
                val index = indexOf(combined, ftyp)
                if (index >= 0) {
                    val ftypPosition = base + index
                    val mp4Start = ftypPosition - 4
                    if (mp4Start >= 0) return mp4Start
                }

                val keep = minOf(32, combined.size)
                tail = combined.copyOfRange(combined.size - keep, combined.size)
                absolute += read
            }
        }
        return null
    }

    private fun indexOf(bytes: ByteArray, target: ByteArray): Int {
        if (target.isEmpty() || bytes.size < target.size) return -1
        for (i in 0..bytes.size - target.size) {
            var matched = true
            for (j in target.indices) {
                if (bytes[i + j] != target[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return i
        }
        return -1
    }

    private fun skipFully(input: java.io.InputStream, bytes: Long) {
        var remaining = max(0L, bytes)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) break else remaining--
            } else {
                remaining -= skipped
            }
        }
    }
}
