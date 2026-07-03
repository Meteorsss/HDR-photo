package com.meteorsss.hdrphoto

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class PhotoActivity : Activity() {
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadToken = AtomicInteger(0)
    private lateinit var root: FrameLayout
    private lateinit var imageView: ZoomImageView
    private lateinit var videoView: VideoView
    private lateinit var loading: TextView
    private lateinit var liveButton: TextView
    private lateinit var detailsPanel: View
    private lateinit var detailsList: LinearLayout
    private var liveVideoUri: Uri? = null
    private var currentItem: PhotoItem? = null

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
                        if (!imageView.isZoomed() && videoView.visibility != View.VISIBLE) {
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
            loadCurrent()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        videoView.stopPlayback()
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
                Gravity.TOP or Gravity.END,
            ).apply {
                setMargins(0, dp(18), dp(18), 0)
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

    private fun loadAt(index: Int) {
        if (GallerySession.photos.isEmpty()) return
        GallerySession.setIndex(index)
        currentItem = GallerySession.photos[GallerySession.index]
        loadCurrent()
    }

    private fun showAdjacent(offset: Int) {
        val next = GallerySession.index + offset
        if (next !in GallerySession.photos.indices) return
        loadAt(next)
    }

    private fun loadCurrent() {
        val item = currentItem ?: return
        val token = loadToken.incrementAndGet()
        stopVideo()
        detailsPanel.visibility = View.GONE
        liveButton.visibility = View.GONE
        liveButton.text = "LIVE"
        liveVideoUri = null
        imageView.setImageDrawable(null)
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
                    imageView.setImageDrawable(drawable)
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
        if (videoView.visibility == View.VISIBLE) {
            stopVideo()
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
        videoView.setOnErrorListener { _, _, _ ->
            stopVideo()
            true
        }
        videoView.setOnCompletionListener {
            stopVideo()
        }
    }

    private fun stopVideo() {
        videoView.stopPlayback()
        videoView.visibility = View.GONE
        imageView.visibility = View.VISIBLE
        if (liveVideoUri != null) liveButton.text = "LIVE"
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
        val media = queryMediaDetails(uri)
        val exif = readExif(uri)
        val name = media["name"] ?: uri.lastPathSegment.orEmpty()
        rows += "文件名" to name
        rows += "位置" to uri.toString()
        rows += "大小" to formatBytes(media["size"]?.toLongOrNull())
        rows += "格式" to (media["mime"] ?: name.substringAfterLast('.', "未知").uppercase())
        rows += "像素" to listOfNotNull(media["width"], media["height"]).joinToString(" x ")
        rows += "拍摄时间" to (exif[EXIF_DATETIME_ORIGINAL] ?: formatMillis(media["dateTaken"]?.toLongOrNull()))
        rows += "添加时间" to formatSeconds(media["dateAdded"]?.toLongOrNull())
        rows += "修改时间" to formatSeconds(media["dateModified"]?.toLongOrNull())

        exif["GPS"]?.let { rows += "GPS" to it }
        rows += "设备" to listOfNotNull(exif[EXIF_MAKE], exif[EXIF_MODEL]).joinToString(" ")
        rows += "光圈" to exif[EXIF_F_NUMBER]?.let { "f/$it" }
        rows += "焦距" to exif[EXIF_FOCAL_LENGTH]?.let { "$it mm" }
        rows += "曝光时间" to exif[EXIF_EXPOSURE_TIME]
        rows += "ISO" to (exif[EXIF_PHOTOGRAPHIC_SENSITIVITY] ?: exif[EXIF_ISO_SPEED_RATINGS])
        rows += "白平衡" to exif[EXIF_WHITE_BALANCE]?.let { if (it == "0") "自动" else "手动" }
        rows += "闪光灯" to exif[EXIF_FLASH]
        rows += "曝光补偿" to exif[EXIF_EXPOSURE_BIAS_VALUE]
        rows += "测光模式" to exif[EXIF_METERING_MODE]
        rows += "软件" to exif[EXIF_SOFTWARE]
        return rows.filter { it.second.isNotBlank() }
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
