package com.meteorsss.hdrphoto

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.VideoView
import java.util.concurrent.Executors

class PhotoActivity : Activity() {
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var imageView: ZoomImageView
    private lateinit var videoView: VideoView
    private lateinit var loading: TextView
    private lateinit var liveButton: TextView
    private var hasPreview = false
    private var fullImageLoaded = false
    private var liveVideoUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 26) {
            window.colorMode = ActivityInfo.COLOR_MODE_HDR
        }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        buildLayout()
        val uri = intent.data ?: return finish()
        showImmediatePreview(uri)
        setupLivePhoto(uri)
        loadPhoto(uri)
    }

    override fun onDestroy() {
        videoView.stopPlayback()
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

        videoView = VideoView(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        root.addView(videoView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        loading = TextView(this).apply {
            text = getString(R.string.loading)
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(
            loading,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        liveButton = TextView(this).apply {
            text = "LIVE"
            textSize = 13f
            setTextColor(Color.rgb(58, 58, 58))
            setBackgroundResource(R.drawable.badge_hdr)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            visibility = View.GONE
            setOnClickListener { toggleLiveVideo() }
        }
        val liveParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ).apply {
            setMargins(0, dp(18), dp(18), 0)
        }
        root.addView(liveButton, liveParams)
        setContentView(root)
    }

    private fun showImmediatePreview(uri: Uri) {
        val key = uri.toString()
        val cached = PhotoPreviewCache.get(key)
        if (cached != null) {
            setPreview(cached)
        } else {
            mainHandler.postDelayed({
                if (!hasPreview && !fullImageLoaded) loading.visibility = View.VISIBLE
            }, LOADING_DELAY_MS)
            executor.execute {
                val thumb = loadPreviewThumbnail(uri)
                if (thumb != null) {
                    PhotoPreviewCache.put(key, thumb)
                    mainHandler.post { setPreview(thumb) }
                }
            }
        }
    }

    private fun setPreview(bitmap: Bitmap) {
        if (fullImageLoaded) return
        hasPreview = true
        loading.visibility = View.GONE
        imageView.setImageBitmap(bitmap)
    }

    private fun loadPhoto(uri: Uri) {
        executor.execute {
            val drawable = decodeDrawable(uri)
            mainHandler.post {
                fullImageLoaded = true
                loading.visibility = View.GONE
                if (drawable == null) {
                    if (!hasPreview) finish()
                } else {
                    imageView.setImageDrawable(drawable)
                }
            }
        }
    }

    private fun setupLivePhoto(uri: Uri) {
        val pairedVideo = intent.getStringExtra(EXTRA_LIVE_VIDEO_URI)?.let(Uri::parse)
        if (pairedVideo != null) {
            setLiveVideo(pairedVideo)
            return
        }

        val knownMotionPhoto = intent.getBooleanExtra(EXTRA_MOTION_PHOTO, false)
        executor.execute {
            if (knownMotionPhoto || MotionPhotoSupport.hasMotionPhotoMetadata(this, uri)) {
                val extracted = MotionPhotoSupport.extractEmbeddedVideo(this, uri)
                if (extracted != null) {
                    mainHandler.post { setLiveVideo(extracted) }
                }
            }
        }
    }

    private fun setLiveVideo(uri: Uri) {
        liveVideoUri = uri
        liveButton.visibility = View.VISIBLE
    }

    private fun toggleLiveVideo() {
        val uri = liveVideoUri ?: return
        if (videoView.visibility == View.VISIBLE) {
            videoView.stopPlayback()
            videoView.visibility = View.GONE
            imageView.visibility = View.VISIBLE
            liveButton.text = "LIVE"
            return
        }

        imageView.visibility = View.GONE
        videoView.visibility = View.VISIBLE
        liveButton.text = "PHOTO"
        videoView.setVideoURI(uri)
        videoView.setOnPreparedListener { player ->
            player.isLooping = false
            videoView.start()
        }
        videoView.setOnCompletionListener {
            videoView.visibility = View.GONE
            imageView.visibility = View.VISIBLE
            liveButton.text = "LIVE"
        }
    }

    private fun loadPreviewThumbnail(uri: Uri): Bitmap? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val size = Size(
                    maxOf(512, resources.displayMetrics.widthPixels / 2),
                    maxOf(512, resources.displayMetrics.heightPixels / 2),
                )
                contentResolver.loadThumbnail(uri, size, null)
            } else {
                null
            }
        }.getOrNull()
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_LIVE_VIDEO_URI = "com.meteorsss.hdrphoto.LIVE_VIDEO_URI"
        const val EXTRA_MOTION_PHOTO = "com.meteorsss.hdrphoto.MOTION_PHOTO"
        private const val LOADING_DELAY_MS = 650L
    }
}
