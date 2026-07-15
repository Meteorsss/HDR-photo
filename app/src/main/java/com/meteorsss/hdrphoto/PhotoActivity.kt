package com.meteorsss.hdrphoto

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class PhotoActivity : Activity() {
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadToken = AtomicInteger(0)
    private lateinit var root: FrameLayout
    private lateinit var galleryPreview: ImageView
    private lateinit var dismissScrim: View
    private lateinit var imageView: ZoomImageView
    private lateinit var videoView: TextureView
    private lateinit var loading: TextView
    private lateinit var liveButton: TextView
    private lateinit var detailsPanel: View
    private lateinit var detailsList: LinearLayout
    private var liveVideoUri: Uri? = null
    private var currentItem: PhotoItem? = null
    private var mediaPlayer: MediaPlayer? = null
    private var pendingVideoUri: Uri? = null
    private var videoSurface: Surface? = null
    private var lastVideoWidth = 0
    private var lastVideoHeight = 0
    private var lastVideoRotation = 0
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var singlePointerGesture = false
    private var dismissDragActive = false
    private var dismissAnimating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 26) {
            window.colorMode = ActivityInfo.COLOR_MODE_HDR
        }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(0, lightBars)
        buildLayout()

        val sessionIndex = GallerySession.index
        if (GallerySession.photos.isNotEmpty()) {
            loadAt(sessionIndex)
        } else {
            val uri = intent.data ?: return finish()
            currentItem = PhotoItem(0L, "", uri, 0, 0, intentLiveVideoUri())
            loadCurrent(0)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (dismissAnimating) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                imageView.animate().cancel()
                liveButton.animate().cancel()
                gestureStartX = event.x
                gestureStartY = event.y
                singlePointerGesture = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                singlePointerGesture = false
                if (dismissDragActive) restoreDismissDrag()
            }
        }

        val childHandled = super.dispatchTouchEvent(event)
        val dx = event.x - gestureStartX
        val dy = event.y - gestureStartY
        var navigationHandled = false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (singlePointerGesture) navigationHandled = updateDismissDrag(dx, dy)
            }
            MotionEvent.ACTION_UP -> {
                if (singlePointerGesture) {
                    navigationHandled = if (dismissDragActive) {
                        completeDismissDrag(dy)
                        true
                    } else {
                        handleGallerySwipe(dx, dy)
                    }
                }
                singlePointerGesture = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dismissDragActive) restoreDismissDrag()
                singlePointerGesture = false
            }
        }
        return childHandled || navigationHandled
    }

    private fun updateDismissDrag(dx: Float, dy: Float): Boolean {
        if (detailsPanel.visibility == View.VISIBLE || imageView.isZoomed() || mediaPlayer != null) return false
        if (!dismissDragActive &&
            (dy <= dp(DISMISS_DRAG_START_DP) || kotlin.math.abs(dy) <= kotlin.math.abs(dx) * 1.05f)
        ) return false

        dismissDragActive = true
        val translation = dy.coerceAtLeast(0f)
        val progressDistance = root.height.coerceAtLeast(1) * DISMISS_PROGRESS_HEIGHT_FRACTION
        val fraction = (translation / progressDistance).coerceIn(0f, 1f)
        val scale = 1f - fraction * DISMISS_SCALE_RANGE
        imageView.translationY = translation
        imageView.scaleX = scale
        imageView.scaleY = scale
        imageView.alpha = 1f
        dismissScrim.alpha = 1f - fraction
        liveButton.translationY = translation
        liveButton.alpha = 1f - fraction * 0.6f
        return true
    }

    private fun completeDismissDrag(distanceY: Float) {
        if (distanceY < dp(VERTICAL_SWIPE_DP)) {
            restoreDismissDrag()
            return
        }
        dismissDragActive = false
        dismissAnimating = true
        val target = dismissTarget()
        dismissScrim.animate().alpha(0f).setDuration(DISMISS_FINISH_DURATION_MS).start()
        liveButton.animate()
            .translationY(root.height.toFloat())
            .alpha(0f)
            .setDuration(DISMISS_FINISH_DURATION_MS)
            .start()
        imageView.animate()
            .translationX(target.translationX)
            .translationY(target.translationY)
            .scaleX(target.scale)
            .scaleY(target.scale)
            .alpha(0f)
            .setDuration(DISMISS_FINISH_DURATION_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                finish()
                overridePendingTransition(0, 0)
            }
            .start()
    }

    private fun dismissTarget(): DismissTarget {
        val bounds = GallerySession.launchBounds
        val samePhoto = GallerySession.launchUri == currentItem?.uri?.toString()
        if (bounds == null || !samePhoto || root.width <= 0 || root.height <= 0) {
            return DismissTarget(0f, root.height * 0.28f, DISMISS_FALLBACK_SCALE)
        }
        val rootLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        val targetCenterX = bounds.exactCenterX() - rootLocation[0]
        val targetCenterY = bounds.exactCenterY() - rootLocation[1]
        val scale = (bounds.width().toFloat() / root.width).coerceIn(DISMISS_MIN_TARGET_SCALE, DISMISS_MAX_TARGET_SCALE)
        return DismissTarget(
            translationX = targetCenterX - root.width * 0.5f,
            translationY = targetCenterY - root.height * 0.5f,
            scale = scale,
        )
    }

    private fun restoreDismissDrag() {
        dismissDragActive = false
        dismissScrim.animate()
            .alpha(1f)
            .setDuration(DISMISS_RETURN_DURATION_MS)
            .start()
        liveButton.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(DISMISS_RETURN_DURATION_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        imageView.animate()
            .translationX(0f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(DISMISS_RETURN_DURATION_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun handleGallerySwipe(dx: Float, dy: Float): Boolean {
        if (detailsPanel.visibility == View.VISIBLE || imageView.isZoomed() || mediaPlayer != null) return false
        val horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy) * SWIPE_DIRECTION_BIAS
        if (horizontal && kotlin.math.abs(dx) >= dp(HORIZONTAL_SWIPE_DP)) {
            if (dx < 0f) showAdjacent(1) else showAdjacent(-1)
            return true
        }

        val vertical = kotlin.math.abs(dy) > kotlin.math.abs(dx) * SWIPE_DIRECTION_BIAS
        if (vertical && -dy >= dp(VERTICAL_SWIPE_DP)) {
            showDetails()
            return true
        }
        return false
    }

    override fun onDestroy() {
        stopVideo()
        videoSurface?.release()
        executor.shutdownNow()
        galleryPreview.setImageBitmap(null)
        GallerySession.clearLaunchPreview()
        super.onDestroy()
    }

    private fun buildLayout() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        galleryPreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(GallerySession.galleryPreview)
            setBackgroundColor(
                if (isDarkMode()) Color.rgb(9, 13, 20) else Color.rgb(245, 248, 252),
            )
        }
        root.addView(galleryPreview, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        dismissScrim = View(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(dismissScrim, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        imageView = ZoomImageView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        root.addView(imageView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        videoView = TextureView(this).apply {
            visibility = View.VISIBLE
            alpha = 0f
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                    videoSurface?.release()
                    videoSurface = Surface(surfaceTexture)
                    pendingVideoUri?.let { startVideo(it) }
                }

                override fun onSurfaceTextureSizeChanged(
                    surfaceTexture: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    applyVideoFitTransform(lastVideoWidth, lastVideoHeight, lastVideoRotation)
                }

                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                    stopVideo()
                    videoSurface?.release()
                    videoSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
            }
        }
        root.addView(videoView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        loading = TextView(this).apply {
            text = getString(R.string.loading)
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = floatingGlassBackground(dp(22), Color.argb(138, 26, 30, 38))
            setPadding(dp(18), dp(9), dp(18), dp(9))
            elevation = dp(8).toFloat()
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
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = floatingGlassBackground(dp(18), Color.argb(132, 24, 29, 38))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            elevation = dp(10).toFloat()
            visibility = View.GONE
            setOnClickListener { toggleLiveVideo() }
        }
        root.addView(
            liveButton,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            ).apply {
                setMargins(0, 0, dp(18), dp(34))
            },
        )

        detailsPanel = buildDetailsPanel()
        root.addView(
            detailsPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.58f).toInt(),
                Gravity.BOTTOM,
            ),
        )
        setContentView(root)
    }

    private fun buildDetailsPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = detailsGlassBackground()
            elevation = dp(14).toFloat()
            setPadding(dp(20), dp(16), dp(20), dp(20))
            visibility = View.GONE
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = "图片详情"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            TextView(this).apply {
                text = "关闭"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(198, 218, 255))
                setPadding(dp(12), dp(8), dp(4), dp(8))
                setOnClickListener { detailsPanel.visibility = View.GONE }
            },
        )
        panel.addView(header)

        detailsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        panel.addView(
            ScrollView(this).apply {
                addView(detailsList)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        return panel
    }

    private fun loadAt(index: Int, direction: Int = 0) {
        if (GallerySession.photos.isEmpty()) return
        GallerySession.setIndex(index)
        currentItem = GallerySession.photos[GallerySession.index]
        loadCurrent(direction)
    }

    private fun showAdjacent(offset: Int) {
        val next = GallerySession.index + offset
        if (next !in GallerySession.photos.indices) return
        loadAt(next, offset)
    }

    private fun loadCurrent(direction: Int) {
        val item = currentItem ?: return
        val token = loadToken.incrementAndGet()
        dismissDragActive = false
        dismissAnimating = false
        imageView.animate().cancel()
        imageView.translationX = 0f
        imageView.translationY = 0f
        imageView.scaleX = 1f
        imageView.scaleY = 1f
        imageView.alpha = 1f
        dismissScrim.alpha = 1f
        stopVideo()
        detailsPanel.visibility = View.GONE
        liveButton.visibility = View.GONE
        liveButton.text = "LIVE"
        liveVideoUri = null
        pendingVideoUri = null
        scheduleLoadingIndicator(item, token)
        if (direction == 0) {
            imageView.setImageDrawable(null)
        } else {
            imageView.animate()
                .translationX(-direction * widthOrScreen() * 0.16f)
                .alpha(0f)
                .setDuration(120L)
                .withEndAction {
                    if (token == loadToken.get()) imageView.setImageDrawable(null)
                }
                .start()
        }

        setupLivePhoto(item, token)
        executor.execute {
            val drawable = decodeDrawable(item.uri)
            mainHandler.post {
                if (token != loadToken.get()) return@post
                loading.tag = null
                loading.visibility = View.GONE
                if (drawable == null) {
                    finish()
                } else {
                    if (direction != 0) {
                        imageView.translationX = direction * widthOrScreen() * 0.18f
                        imageView.alpha = 0f
                    }
                    imageView.setImageDrawable(drawable)
                    if (direction != 0) {
                        imageView.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(180L)
                            .start()
                    } else {
                        imageView.translationX = 0f
                        imageView.alpha = 1f
                    }
                }
            }
        }
    }

    private fun scheduleLoadingIndicator(item: PhotoItem, token: Int) {
        loading.tag = token
        loading.visibility = View.GONE
        val delay = if (item.sizeBytes >= LARGE_PHOTO_BYTES) 0L else LOADING_DELAY_MS
        mainHandler.postDelayed(
            {
                if (loading.tag == token && token == loadToken.get()) {
                    loading.visibility = View.VISIBLE
                }
            },
            delay,
        )
    }

    private fun setupLivePhoto(item: PhotoItem, token: Int) {
        item.liveVideoUri?.let {
            setLiveVideo(it, token)
            return
        }
        if (item.id == 0L) {
            intentLiveVideoUri()?.let {
                setLiveVideo(it, token)
                return
            }
        }
        val knownMotionPhoto = intent.getBooleanExtra(EXTRA_MOTION_PHOTO, false)
        executor.execute {
            if (knownMotionPhoto || MotionPhotoSupport.hasMotionPhotoMetadata(this, item.uri)) {
                val extracted = MotionPhotoSupport.extractEmbeddedVideo(this, item.uri)
                if (extracted != null) {
                    mainHandler.post { setLiveVideo(extracted, token) }
                }
            }
        }
    }

    private fun intentLiveVideoUri(): Uri? {
        return intent.getStringExtra(EXTRA_LIVE_VIDEO_URI)?.let(Uri::parse)
    }

    private fun setLiveVideo(uri: Uri, token: Int) {
        if (token != loadToken.get()) return
        liveVideoUri = uri
        liveButton.visibility = View.VISIBLE
    }

    private fun toggleLiveVideo() {
        val uri = liveVideoUri ?: return
        if (mediaPlayer != null) {
            stopVideo()
            return
        }

        pendingVideoUri = uri
        liveButton.text = "..."
        videoView.alpha = 0f
        videoView.visibility = View.VISIBLE
        if (videoView.isAvailable) {
            startVideo(uri)
        } else {
            pendingVideoUri = uri
        }
    }

    private fun startVideo(uri: Uri) {
        val surface = videoSurface ?: return
        pendingVideoUri = null
        mediaPlayer?.release()
        runCatching {
            val rotation = readVideoRotation(uri)
            mediaPlayer = MediaPlayer().apply {
                if (uri.scheme == "file") {
                    setDataSource(uri.path ?: throw IllegalArgumentException("Missing video path"))
                } else {
                    setDataSource(this@PhotoActivity, uri)
                }
                setSurface(surface)
                setOnPreparedListener { player ->
                    player.isLooping = false
                    lastVideoWidth = player.videoWidth
                    lastVideoHeight = player.videoHeight
                    lastVideoRotation = rotation
                    applyVideoFitTransform(lastVideoWidth, lastVideoHeight, lastVideoRotation)
                    liveButton.text = "PHOTO"
                    imageView.visibility = View.GONE
                    videoView.alpha = 1f
                    player.start()
                }
                setOnErrorListener { _, _, _ ->
                    stopVideo()
                    true
                }
                setOnCompletionListener {
                    stopVideo()
                }
                prepareAsync()
            }
        }.onFailure {
            stopVideo()
        }
    }

    private fun stopVideo() {
        mediaPlayer?.release()
        mediaPlayer = null
        pendingVideoUri = null
        lastVideoWidth = 0
        lastVideoHeight = 0
        lastVideoRotation = 0
        videoView.setTransform(Matrix())
        videoView.alpha = 0f
        videoView.visibility = View.VISIBLE
        imageView.visibility = View.VISIBLE
        if (liveVideoUri != null) liveButton.text = "LIVE"
    }

    private fun applyVideoFitTransform(videoWidth: Int, videoHeight: Int, rotation: Int) {
        val viewWidth = videoView.width.toFloat()
        val viewHeight = videoView.height.toFloat()
        if (videoWidth <= 0 || videoHeight <= 0 || viewWidth <= 0f || viewHeight <= 0f) return

        val rotated = rotation == 90 || rotation == 270
        val displayWidth = if (rotated) videoHeight.toFloat() else videoWidth.toFloat()
        val displayHeight = if (rotated) videoWidth.toFloat() else videoHeight.toFloat()
        val scale = min(viewWidth / displayWidth, viewHeight / displayHeight)
        val scaleX = displayWidth * scale / viewWidth
        val scaleY = displayHeight * scale / viewHeight

        videoView.setTransform(
            Matrix().apply {
                setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
            },
        )
    }

    private fun readVideoRotation(uri: Uri): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file") {
                retriever.setDataSource(uri.path ?: return 0)
            } else {
                retriever.setDataSource(this, uri)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
                ?: 0
        } catch (_: Exception) {
            0
        } finally {
            retriever.release()
        }
    }

    private fun showDetails() {
        val item = currentItem ?: return
        detailsList.removeAllViews()
        detailsPanel.visibility = View.VISIBLE
        addDetail("文件名", item.name.ifBlank { item.uri.lastPathSegment.orEmpty() })
        addDetail("位置", item.name.ifBlank { item.uri.lastPathSegment.orEmpty() })
        addDetail("像素", if (item.width > 0 && item.height > 0) "${item.width} x ${item.height}" else "未知")

        executor.execute {
            val details = readDetails(item.uri)
            mainHandler.post {
                if (item.uri != currentItem?.uri) return@post
                detailsList.removeAllViews()
                details.forEach { (label, value) -> addDetail(label, value) }
            }
        }
    }

    private fun addDetail(label: String, value: String?) {
        if (value.isNullOrBlank()) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(
            TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(Color.rgb(172, 180, 194))
            },
        )
        row.addView(
            TextView(this).apply {
                text = value
                textSize = 15f
                setTextColor(Color.rgb(246, 248, 252))
            },
        )
        detailsList.addView(row)
    }

    private fun readDetails(uri: Uri): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        fun putDetail(label: String, value: String?) {
            if (!value.isNullOrBlank()) rows.add(label to value)
        }
        val media = queryMediaDetails(uri)
        val exif = readExif(uri)
        val name = media["name"] ?: uri.lastPathSegment.orEmpty()
        putDetail("文件名", name)
        putDetail("位置", readableMediaPath(media, name, uri))
        putDetail("大小", formatBytes(media["size"]?.toLongOrNull()))
        putDetail("格式", media["mime"] ?: name.substringAfterLast('.', "未知").uppercase())
        putDetail("像素", listOfNotNull(media["width"], media["height"]).joinToString(" x "))
        putDetail("拍摄时间", exif[EXIF_DATETIME_ORIGINAL] ?: formatMillis(media["dateTaken"]?.toLongOrNull()))
        putDetail("添加时间", formatSeconds(media["dateAdded"]?.toLongOrNull()))
        putDetail("修改时间", formatSeconds(media["dateModified"]?.toLongOrNull()))

        putDetail("GPS", exif["GPS"])
        putDetail("设备", listOfNotNull(exif[EXIF_MAKE], exif[EXIF_MODEL]).joinToString(" "))
        putDetail("光圈", exif[EXIF_F_NUMBER]?.let { "f/$it" })
        putDetail("焦距", exif[EXIF_FOCAL_LENGTH]?.let { "$it mm" })
        putDetail("曝光时间", exif[EXIF_EXPOSURE_TIME])
        putDetail("ISO", exif[EXIF_PHOTOGRAPHIC_SENSITIVITY] ?: exif[EXIF_ISO_SPEED_RATINGS])
        putDetail("白平衡", exif[EXIF_WHITE_BALANCE]?.let { if (it == "0") "自动" else "手动" })
        putDetail("闪光灯", exif[EXIF_FLASH])
        putDetail("曝光补偿", exif[EXIF_EXPOSURE_BIAS_VALUE])
        putDetail("测光模式", exif[EXIF_METERING_MODE])
        putDetail("软件", exif[EXIF_SOFTWARE])
        return rows
    }

    private fun queryMediaDetails(uri: Uri): Map<String, String> {
        val projection = mutableListOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        if (Build.VERSION.SDK_INT >= 29) {
            projection += MediaStore.Images.Media.DATE_TAKEN
            projection += MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            projection += MediaStore.MediaColumns.DATA
        }
        val values = mutableMapOf<String, String>()
        contentResolver.query(uri, projection.toTypedArray(), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                values["name"] = cursor.stringValue(MediaStore.MediaColumns.DISPLAY_NAME)
                values["size"] = cursor.stringValue(MediaStore.MediaColumns.SIZE)
                values["mime"] = cursor.stringValue(MediaStore.MediaColumns.MIME_TYPE)
                values["width"] = cursor.stringValue(MediaStore.MediaColumns.WIDTH)
                values["height"] = cursor.stringValue(MediaStore.MediaColumns.HEIGHT)
                values["dateAdded"] = cursor.stringValue(MediaStore.MediaColumns.DATE_ADDED)
                values["dateModified"] = cursor.stringValue(MediaStore.MediaColumns.DATE_MODIFIED)
                if (Build.VERSION.SDK_INT >= 29) {
                    values["dateTaken"] = cursor.stringValue(MediaStore.Images.Media.DATE_TAKEN)
                    values["relativePath"] = cursor.stringValue(MediaStore.MediaColumns.RELATIVE_PATH)
                } else {
                    values["dataPath"] = cursor.stringValue(MediaStore.MediaColumns.DATA)
                }
            }
        }
        return values
    }

    private fun readableMediaPath(media: Map<String, String>, name: String, uri: Uri): String {
        media["dataPath"]?.takeIf { it.isNotBlank() }?.let { return it }
        media["relativePath"]?.takeIf { it.isNotBlank() }?.let { folder ->
            return folder.trimEnd('/') + "/" + name
        }
        return name.ifBlank { uri.lastPathSegment ?: "未知位置" }
    }

    private fun readExif(uri: Uri): Map<String, String> {
        return runCatching {
            openExifInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val values = mutableMapOf<String, String>()
                val tags = listOf(
                    EXIF_DATETIME_ORIGINAL,
                    EXIF_MAKE,
                    EXIF_MODEL,
                    EXIF_F_NUMBER,
                    EXIF_FOCAL_LENGTH,
                    EXIF_EXPOSURE_TIME,
                    EXIF_PHOTOGRAPHIC_SENSITIVITY,
                    EXIF_ISO_SPEED_RATINGS,
                    EXIF_WHITE_BALANCE,
                    EXIF_FLASH,
                    EXIF_EXPOSURE_BIAS_VALUE,
                    EXIF_METERING_MODE,
                    EXIF_SOFTWARE,
                )
                tags.forEach { tag ->
                    exif.getAttribute(tag)?.let { values[tag] = it }
                }
                gpsString(exif)?.let { values["GPS"] = it }
                values
            } ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    private fun openExifInputStream(uri: Uri): java.io.InputStream? {
        val exifUri = originalMediaUri(uri)
        return runCatching {
            contentResolver.openInputStream(exifUri)
        }.getOrNull() ?: runCatching {
            contentResolver.openInputStream(uri)
        }.getOrNull()
    }

    private fun originalMediaUri(uri: Uri): Uri {
        return if (Build.VERSION.SDK_INT >= 29 && hasMediaLocationAccess()) {
            runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
        } else {
            uri
        }
    }

    private fun hasMediaLocationAccess(): Boolean {
        return Build.VERSION.SDK_INT < 29 ||
            checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun gpsString(exif: ExifInterface): String? {
        val latLong = FloatArray(2)
        if (exif.getLatLong(latLong) && (latLong[0] != 0f || latLong[1] != 0f)) {
            return String.format(Locale.US, "%.6f, %.6f", latLong[0], latLong[1])
        }
        val lat = parseGpsCoordinate(exif.getAttribute("GPSLatitude"), exif.getAttribute("GPSLatitudeRef"))
        val lon = parseGpsCoordinate(exif.getAttribute("GPSLongitude"), exif.getAttribute("GPSLongitudeRef"))
        return if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) {
            String.format(Locale.US, "%.6f, %.6f", lat, lon)
        } else {
            null
        }
    }

    private fun parseGpsCoordinate(value: String?, ref: String?): Double? {
        if (value.isNullOrBlank()) return null
        val parts = value.split(",")
        if (parts.size < 3) return null
        val degrees = parseRational(parts[0]) ?: return null
        val minutes = parseRational(parts[1]) ?: return null
        val seconds = parseRational(parts[2]) ?: return null
        val sign = if (ref == "S" || ref == "W") -1.0 else 1.0
        return sign * (degrees + minutes / 60.0 + seconds / 3600.0)
    }

    private fun parseRational(value: String): Double? {
        val trimmed = value.trim()
        val numerator = trimmed.substringBefore("/", trimmed).toDoubleOrNull() ?: return null
        val denominator = trimmed.substringAfter("/", "1").toDoubleOrNull() ?: return null
        if (denominator == 0.0) return null
        return numerator / denominator
    }

    private fun android.database.Cursor.stringValue(column: String): String {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return ""
        return getString(index).orEmpty()
    }

    private fun decodeDrawable(uri: Uri): Drawable? {
        return if (Build.VERSION.SDK_INT >= 28) {
            decodeWithImageDecoder(uri)
        } else {
            decodeWithBitmapFactory(uri)
        }
    }

    private fun decodeWithImageDecoder(uri: Uri): Drawable? {
        return runCatching {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                val sample = sampleSizeFor(info.size.width, info.size.height)
                if (sample > 1) {
                    decoder.setTargetSampleSize(sample)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                } else {
                    decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
                }
            }
        }.getOrElse {
            decodeSoftwareWithImageDecoder(uri)
        }
    }

    private fun decodeSoftwareWithImageDecoder(uri: Uri): Drawable? {
        if (Build.VERSION.SDK_INT < 28) return null
        return runCatching {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                decoder.setTargetSampleSize(sampleSizeFor(info.size.width, info.size.height))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
    }

    private fun decodeWithBitmapFactory(uri: Uri): Drawable? {
        return runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
            val sample = sampleSizeFor(options.outWidth, options.outHeight)
            val bitmap = contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(
                    input,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }
            bitmap?.let { BitmapDrawable(resources, it) }
        }.getOrNull()
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (maxOf(width / sample, height / sample) > MAX_DECODE_DIMENSION ||
            (width.toLong() / sample) * (height.toLong() / sample) > MAX_DECODE_PIXELS
        ) {
            sample *= 2
        }
        return sample
    }

    private fun formatBytes(size: Long?): String {
        if (size == null || size <= 0L) return ""
        val mb = size / 1024.0 / 1024.0
        return String.format(Locale.US, "%.2f MB", mb)
    }

    private fun formatSeconds(seconds: Long?): String {
        if (seconds == null || seconds <= 0L) return ""
        return DateFormat.getDateTimeInstance().format(Date(seconds * 1000L))
    }

    private fun formatMillis(millis: Long?): String {
        if (millis == null || millis <= 0L) return ""
        return DateFormat.getDateTimeInstance().format(Date(millis))
    }

    private fun widthOrScreen(): Int = if (root.width > 0) root.width else resources.displayMetrics.widthPixels

    private fun isDarkMode(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun floatingGlassBackground(radius: Int, fillColor: Int): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                lightenAlpha(fillColor, 28),
                fillColor,
            ),
        ).apply {
            cornerRadius = radius.toFloat()
            setStroke(dp(1), Color.argb(82, 255, 255, 255))
        }
    }

    private fun detailsGlassBackground(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(236, 23, 27, 36), Color.argb(224, 7, 10, 16)),
        ).apply {
            cornerRadii = floatArrayOf(
                dp(28).toFloat(), dp(28).toFloat(),
                dp(28).toFloat(), dp(28).toFloat(),
                0f, 0f,
                0f, 0f,
            )
            setStroke(dp(1), Color.argb(62, 255, 255, 255))
        }
    }

    private fun lightenAlpha(color: Int, extraAlpha: Int): Int {
        return Color.argb(
            (Color.alpha(color) + extraAlpha).coerceAtMost(255),
            (Color.red(color) + 12).coerceAtMost(255),
            (Color.green(color) + 12).coerceAtMost(255),
            (Color.blue(color) + 16).coerceAtMost(255),
        )
    }

    private data class DismissTarget(val translationX: Float, val translationY: Float, val scale: Float)

    companion object {
        const val EXTRA_LIVE_VIDEO_URI = "com.meteorsss.hdrphoto.LIVE_VIDEO_URI"
        const val EXTRA_MOTION_PHOTO = "com.meteorsss.hdrphoto.MOTION_PHOTO"
        private const val LARGE_PHOTO_BYTES = 20L * 1024L * 1024L
        private const val LOADING_DELAY_MS = 450L
        private const val MAX_DECODE_DIMENSION = 8_192
        private const val MAX_DECODE_PIXELS = 32_000_000L
        private const val HORIZONTAL_SWIPE_DP = 24
        private const val VERTICAL_SWIPE_DP = 48
        private const val SWIPE_DIRECTION_BIAS = 1.2f
        private const val DISMISS_DRAG_START_DP = 6
        private const val DISMISS_PROGRESS_HEIGHT_FRACTION = 0.55f
        private const val DISMISS_SCALE_RANGE = 0.58f
        private const val DISMISS_FALLBACK_SCALE = 0.25f
        private const val DISMISS_MIN_TARGET_SCALE = 0.18f
        private const val DISMISS_MAX_TARGET_SCALE = 0.42f
        private const val DISMISS_FINISH_DURATION_MS = 180L
        private const val DISMISS_RETURN_DURATION_MS = 220L
        private const val EXIF_DATETIME_ORIGINAL = "DateTimeOriginal"
        private const val EXIF_MAKE = "Make"
        private const val EXIF_MODEL = "Model"
        private const val EXIF_F_NUMBER = "FNumber"
        private const val EXIF_FOCAL_LENGTH = "FocalLength"
        private const val EXIF_EXPOSURE_TIME = "ExposureTime"
        private const val EXIF_PHOTOGRAPHIC_SENSITIVITY = "PhotographicSensitivity"
        private const val EXIF_ISO_SPEED_RATINGS = "ISOSpeedRatings"
        private const val EXIF_WHITE_BALANCE = "WhiteBalance"
        private const val EXIF_FLASH = "Flash"
        private const val EXIF_EXPOSURE_BIAS_VALUE = "ExposureBiasValue"
        private const val EXIF_METERING_MODE = "MeteringMode"
        private const val EXIF_SOFTWARE = "Software"
    }
}
