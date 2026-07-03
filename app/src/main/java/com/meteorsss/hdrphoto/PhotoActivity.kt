package com.meteorsss.hdrphoto

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.util.concurrent.Executors

class PhotoActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var imageView: ZoomImageView
    private lateinit var loading: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 26) {
            window.colorMode = ActivityInfo.COLOR_MODE_HDR
        }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        buildLayout()
        intent.data?.let(::loadPhoto) ?: finish()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildLayout() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        imageView = ZoomImageView(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        root.addView(imageView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        loading = TextView(this).apply {
            text = getString(R.string.loading)
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(
            loading,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        setContentView(root)
    }

    private fun loadPhoto(uri: Uri) {
        executor.execute {
            val drawable = decodeDrawable(uri)
            mainHandler.post {
                loading.visibility = View.GONE
                if (drawable == null) {
                    finish()
                } else {
                    imageView.setImageDrawable(drawable)
                }
            }
        }
    }

    private fun decodeDrawable(uri: Uri): Drawable? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= 28) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
                }
            } else {
                contentResolver.openInputStream(uri)?.use { Drawable.createFromStream(it, uri.toString()) }
            }
        }.getOrNull()
    }
}
