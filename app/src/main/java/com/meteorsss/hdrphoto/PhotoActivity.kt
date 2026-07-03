package com.meteorsss.hdrphoto

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class PhotoActivity : Activity() {
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadToken = AtomicInteger(0)
    private lateinit var root: FrameLayout
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

    private val gestureDetector by lazy {
        GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    val start = e1 ?: return false
                    val dx = e2.x - start.x
                    val dy = e2.y - start.y
                    if (detailsPanel.visibility == View.VISIBLE) return false

                    if (kotlin.math.abs(dx) > kotlin.math.abs(dy) && kotlin.math.abs(dx) > dp(90)) {
                        if (!imageView.isZoomed() && mediaPlayer == null) {
                            if (dx < 0) showAdjacent(1) else showAdjacent(-1)
                            return true
                        }
                    }

                    if (-dy > dp(90) && kotlin.math.abs(velocityY) > kotlin.math.abs(velocityX)) {
                        showDetails()
                        return true
                    }
                    return false
                }
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 26) {
            window.colorMode = ActivityInfo.COLOR_MODE_HDR
        }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
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
        gestureDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        stopVideo()
        videoSurface?.release()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildLayout() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        imageView = ZoomImageView(this).apply {
            setBackgroundColor(Color.BLACK)
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
            setBackgroundColor(Color.rgb(18, 18, 18))
            setPadding(dp(18), dp(14), dp(18), dp(18))
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
                setTextColor(Color.WHITE)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            TextView(this).apply {
                text = "关闭"
                textSize = 14f
                setTextColor(Color.LTGRAY)
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
        stopVideo()
        detailsPanel.visibility = View.GONE
        liveButton.visibility = View.GONE
        liveButton.text = "LIVE"
        liveVideoUri = null
        pendingVideoUri = null
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
        loading.visibility = View.VISIBLE

        setupLivePhoto(item, token)
        executor.execute {
            val drawable = decodeDrawable(item.uri)
            mainHandler.post {
                if (token != loadToken.get()) return@post
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
        addDetail("位置", item.uri.toString())
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
                setTextColor(Color.rgb(170, 170, 170))
            },
        )
        row.addView(
            TextView(this).apply {
                text = value
                textSize = 15f
                setTextColor(Color.WHITE)
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
        putDetail("位置", uri.toString())
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
                }
            }
        }
        return values
    }

    private fun readExif(uri: Uri): Map<String, String> {
        return runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
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
                val latLong = FloatArray(2)
                if (exif.getLatLong(latLong)) {
                    values["GPS"] = "${latLong[0]}, ${latLong[1]}"
                }
                values
            } ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    private fun android.database.Cursor.stringValue(column: String): String {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return ""
        return getString(index).orEmpty()
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

    private fun formatBytes(size: Long?): String {
        if (size == null || size <= 0L) return ""
        val mb = size / 1024.0 / 1024.0
        return String.format("%.2f MB", mb)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_LIVE_VIDEO_URI = "com.meteorsss.hdrphoto.LIVE_VIDEO_URI"
        const val EXTRA_MOTION_PHOTO = "com.meteorsss.hdrphoto.MOTION_PHOTO"
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
