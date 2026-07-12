package com.meteorsss.hdrphoto

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

data class PhotoItem(
    val id: Long,
    val name: String,
    val uri: Uri,
    val width: Int,
    val height: Int,
    val liveVideoUri: Uri?,
    val sizeBytes: Long = 0L,
    val dateMillis: Long = 0L,
    val bucketId: String = "",
    val bucketName: String = "",
)

data class AlbumItem(
    val id: String,
    val name: String,
    val count: Int,
    val cover: PhotoItem,
    val latestMillis: Long,
)

class MainActivity : Activity() {
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    private val metadataExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val allPhotos = mutableListOf<PhotoItem>()
    private val visiblePhotos = mutableListOf<PhotoItem>()
    private val albums = mutableListOf<AlbumItem>()
    private lateinit var statusText: TextView
    private lateinit var listView: ListView
    private lateinit var photoNav: TextView
    private lateinit var albumNav: TextView
    private lateinit var tileBinder: PhotoTileBinder
    private lateinit var photoAdapter: DatedPhotoAdapter
    private lateinit var albumAdapter: AlbumAdapter
    private var currentAlbum: AlbumItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 26) {
            window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        }
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        if (Build.VERSION.SDK_INT >= 23) {
            var flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = flags
        }
        buildLayout()
        if (hasImageAccess()) {
            loadPhotos()
        } else {
            showPermissionPrompt()
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        metadataExecutor.shutdownNow()
        if (::tileBinder.isInitialized) tileBinder.clear()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_IMAGES && hasImageAccess()) {
            loadPhotos()
        } else if (requestCode == REQUEST_IMAGES) {
            showPermissionPrompt()
        }
    }

    private fun buildLayout() {
        tileBinder = PhotoTileBinder(this, executor, metadataExecutor, mainHandler)
        photoAdapter = DatedPhotoAdapter(this, tileBinder) { item ->
            openPhoto(item)
        }
        albumAdapter = AlbumAdapter(this, albums, tileBinder) { album ->
            showPhotos(album)
        }

        val root = FrameLayout(this).apply {
            background = appBackground()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(10), dp(14))
            background = glassBackground(dp(26), Color.argb(190, 255, 255, 255))
            elevation = dp(8).toFloat()
        }

        statusText = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(16, 22, 32))
            setSingleLine(false)
            includeFontPadding = true
        }
        toolbar.addView(
            statusText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        val refreshButton = TextView(this).apply {
            text = getString(R.string.refresh)
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(38, 92, 170))
            background = glassBackground(dp(20), Color.argb(142, 255, 255, 255))
            setPadding(dp(16), 0, dp(16), 0)
            elevation = dp(2).toFloat()
            setOnClickListener {
                if (hasImageAccess()) loadPhotos() else requestImageAccess()
            }
        }
        toolbar.addView(
            refreshButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)),
        )

        listView = ListView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            divider = null
            cacheColorHint = Color.TRANSPARENT
            clipToPadding = false
            setPadding(dp(2), dp(4), dp(2), dp(104))
            adapter = photoAdapter
        }

        val bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = liquidGlassNavBackground()
            elevation = dp(18).toFloat()
            clipToOutline = true
        }
        photoNav = buildNavItem("照片") {
            showPhotos(null)
        }
        albumNav = buildNavItem("相册") {
            showAlbums()
        }
        bottomNav.addView(photoNav, LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(1), 0, dp(1), 0) })
        bottomNav.addView(albumNav, LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(1), 0, dp(1), 0) })

        content.addView(
            toolbar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(dp(12), dp(10), dp(12), dp(4))
            },
        )
        content.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            bottomNav,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(70),
                Gravity.BOTTOM,
            ).apply {
                setMargins(dp(22), 0, dp(22), dp(18))
            },
        )
        setContentView(root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, 0)
            listView.setPadding(dp(2), dp(4), dp(2), dp(104) + bars.bottom)
            (bottomNav.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = dp(14) + bars.bottom
                bottomNav.layoutParams = this
            }
            insets
        }
        root.requestApplyInsets()
        updateNavSelection(showingAlbums = false)
    }

    private fun buildNavItem(textValue: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            letterSpacing = 0.01f
            foreground = RippleDrawable(
                ColorStateList.valueOf(Color.argb(28, 20, 94, 210)),
                null,
                GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(27).toFloat()
                },
            )
            setOnClickListener {
                animate().scaleX(0.96f).scaleY(0.96f).setDuration(70L).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(210L)
                        .setInterpolator(DecelerateInterpolator()).start()
                    onClick()
                }.start()
            }
        }
    }

    private fun showPermissionPrompt() {
        allPhotos.clear()
        visiblePhotos.clear()
        albums.clear()
        photoAdapter.submit(visiblePhotos)
        albumAdapter.notifyDataSetChanged()
        statusText.text = getString(R.string.permission_needed)
        requestImageAccess()
    }

    private fun loadPhotos() {
        statusText.text = getString(R.string.loading)
        executor.execute {
            val loaded = queryImages()
            val loadedAlbums = buildAlbums(loaded)
            mainHandler.post {
                allPhotos.clear()
                allPhotos.addAll(loaded)
                albums.clear()
                albums.addAll(loadedAlbums)
                albumAdapter.notifyDataSetChanged()
                currentAlbum = null
                showPhotos(null)
            }
        }
    }

    private fun showPhotos(album: AlbumItem?) {
        currentAlbum = album
        visiblePhotos.clear()
        visiblePhotos.addAll(
            if (album == null) {
                allPhotos
            } else {
                allPhotos.filter { it.bucketId == album.id }
            },
        )
        photoAdapter.submit(visiblePhotos)
        listView.adapter = photoAdapter
        listView.setSelection(0)
        statusText.text = if (visiblePhotos.isEmpty()) {
            getString(R.string.empty_gallery)
        } else {
            val title = album?.name ?: "照片"
            "$title\n${visiblePhotos.size} 张照片"
        }
        updateNavSelection(showingAlbums = false)
    }

    private fun showAlbums() {
        listView.adapter = albumAdapter
        listView.setSelection(0)
        statusText.text = "相册\n${albums.size} 个相册"
        updateNavSelection(showingAlbums = true)
    }

    private fun updateNavSelection(showingAlbums: Boolean) {
        val selected = Color.rgb(22, 92, 214)
        val normal = Color.rgb(74, 84, 101)
        photoNav.setTextColor(if (showingAlbums) normal else selected)
        albumNav.setTextColor(if (showingAlbums) selected else normal)
        photoNav.background = if (showingAlbums) null else selectedNavBackground()
        albumNav.background = if (showingAlbums) selectedNavBackground() else null
        photoNav.animate().alpha(if (showingAlbums) 0.72f else 1f).setDuration(220L).start()
        albumNav.animate().alpha(if (showingAlbums) 1f else 0.72f).setDuration(220L).start()
    }

    private fun openPhoto(item: PhotoItem) {
        val position = visiblePhotos.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0)
        GallerySession.setPhotos(visiblePhotos, position)
        val intent = Intent(this, PhotoActivity::class.java).apply {
            data = item.uri
            item.liveVideoUri?.let {
                putExtra(PhotoActivity.EXTRA_LIVE_VIDEO_URI, it.toString())
            }
            putExtra(PhotoActivity.EXTRA_MOTION_PHOTO, tileBinder.isMotionPhoto(item))
        }
        startActivity(intent)
    }

    private fun queryImages(): List<PhotoItem> {
        val result = mutableListOf<PhotoItem>()
        val videoPairs = queryVideoPairs()
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        if (Build.VERSION.SDK_INT >= 29) {
            projection += MediaStore.Images.Media.RELATIVE_PATH
        } else {
            projection += MediaStore.Images.Media.DATA
        }
        contentResolver.query(collection, projection.toTypedArray(), null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val bucketIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val pathColumn = if (Build.VERSION.SDK_INT >= 29) {
                cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            }
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn).orEmpty()
                val path = cursor.getString(pathColumn).orEmpty()
                val dateMillis = bestPhotoTimeMillis(
                    cursor.safeLong(takenColumn),
                    cursor.safeLong(modifiedColumn),
                    cursor.safeLong(addedColumn),
                )
                result += PhotoItem(
                    id = id,
                    name = name,
                    uri = ContentUris.withAppendedId(collection, id),
                    width = cursor.getInt(widthColumn),
                    height = cursor.getInt(heightColumn),
                    liveVideoUri = videoPairs[livePairKey(name, path)],
                    sizeBytes = cursor.safeLong(sizeColumn),
                    dateMillis = dateMillis,
                    bucketId = cursor.getString(bucketIdColumn).orEmpty(),
                    bucketName = cursor.getString(bucketNameColumn).orEmpty(),
                )
            }
        }
        return result.sortedWith(
            compareByDescending<PhotoItem> { it.dateMillis }
                .thenByDescending { it.id },
        )
    }

    private fun bestPhotoTimeMillis(dateTaken: Long, dateModified: Long, dateAdded: Long): Long {
        return when {
            dateTaken > 0L -> dateTaken
            dateModified > 0L -> dateModified * 1000L
            dateAdded > 0L -> dateAdded * 1000L
            else -> 0L
        }
    }

    private fun buildAlbums(items: List<PhotoItem>): List<AlbumItem> {
        return items.groupBy { it.bucketId.ifBlank { it.bucketName.ifBlank { "unknown" } } }
            .mapNotNull { (bucketId, albumPhotos) ->
                val cover = albumPhotos.maxByOrNull { it.dateMillis } ?: return@mapNotNull null
                AlbumItem(
                    id = bucketId,
                    name = cover.bucketName.ifBlank { "其他" },
                    count = albumPhotos.size,
                    cover = cover,
                    latestMillis = cover.dateMillis,
                )
            }
            .sortedWith(compareByDescending<AlbumItem> { it.latestMillis }.thenBy { it.name })
    }

    private fun queryVideoPairs(): Map<String, Uri> {
        if (!hasVideoAccess()) return emptyMap()
        val pairs = mutableMapOf<String, Uri>()
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
        )
        if (Build.VERSION.SDK_INT >= 29) {
            projection += MediaStore.Video.Media.RELATIVE_PATH
        } else {
            projection += MediaStore.Video.Media.DATA
        }
        contentResolver.query(collection, projection.toTypedArray(), null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val pathColumn = if (Build.VERSION.SDK_INT >= 29) {
                cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            } else {
                cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            }
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn).orEmpty()
                val extension = name.substringAfterLast('.', "").lowercase()
                if (extension !in setOf("mov", "mp4", "3gp")) continue
                val id = cursor.getLong(idColumn)
                val path = cursor.getString(pathColumn).orEmpty()
                pairs[livePairKey(name, path)] = ContentUris.withAppendedId(collection, id)
            }
        }
        return pairs
    }

    private fun hasImageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT >= 34 &&
                    checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
                    PackageManager.PERMISSION_GRANTED)
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasVideoAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT >= 34 &&
                    checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
                    PackageManager.PERMISSION_GRANTED)
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestImageAccess() {
        val permissions = mutableListOf<String>()
        when {
            Build.VERSION.SDK_INT >= 34 -> {
                permissions += Manifest.permission.READ_MEDIA_IMAGES
                permissions += Manifest.permission.READ_MEDIA_VIDEO
                permissions += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            }
            Build.VERSION.SDK_INT >= 33 -> {
                permissions += Manifest.permission.READ_MEDIA_IMAGES
                permissions += Manifest.permission.READ_MEDIA_VIDEO
            }
            else -> permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            permissions += Manifest.permission.ACCESS_MEDIA_LOCATION
        }
        requestPermissions(permissions.toTypedArray(), REQUEST_IMAGES)
    }

    private fun android.database.Cursor.safeLong(index: Int): Long {
        return if (index < 0 || isNull(index)) 0L else getLong(index)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun appBackground(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.rgb(247, 251, 255),
                Color.rgb(239, 246, 251),
                Color.rgb(249, 251, 255),
            ),
        )
    }

    private fun glassBackground(radius: Int, fillColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(fillColor)
            setStroke(dp(1), Color.argb(145, 255, 255, 255))
        }
    }

    private fun selectedNavBackground(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                Color.argb(190, 255, 255, 255),
                Color.argb(118, 225, 239, 255),
                Color.argb(150, 248, 252, 255),
            ),
        ).apply {
            cornerRadius = dp(27).toFloat()
            setStroke(dp(1), Color.argb(230, 255, 255, 255))
        }
    }

    private fun liquidGlassNavBackground(): LayerDrawable {
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.argb(184, 255, 255, 255),
                Color.argb(108, 238, 247, 255),
                Color.argb(142, 214, 231, 249),
            ),
        ).apply {
            cornerRadius = dp(34).toFloat()
            setStroke(dp(1), Color.argb(235, 255, 255, 255))
        }
        val topSheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(220, 255, 255, 255), Color.argb(28, 255, 255, 255)),
        ).apply {
            cornerRadius = dp(28).toFloat()
        }
        val coldRefraction = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                Color.argb(34, 76, 137, 255),
                Color.argb(6, 255, 255, 255),
                Color.argb(38, 48, 196, 255),
            ),
        ).apply {
            cornerRadius = dp(28).toFloat()
        }
        return LayerDrawable(arrayOf(base, coldRefraction, topSheen)).apply {
            setLayerInset(1, dp(5), dp(5), dp(5), dp(5))
            setLayerInset(2, dp(8), dp(5), dp(8), dp(32))
        }
    }

    companion object {
        private const val REQUEST_IMAGES = 2001
    }
}

private fun livePairKey(name: String, path: String): String {
    val folder = if (Build.VERSION.SDK_INT >= 29) {
        path
    } else {
        path.substringBeforeLast('/', "")
    }
    val stem = name.substringBeforeLast('.', name)
    return "${folder.lowercase()}|${stem.lowercase()}"
}

private sealed class PhotoListRow {
    data class Header(val label: String) : PhotoListRow()
    data class Photos(val items: List<PhotoItem>) : PhotoListRow()
}

private data class AlbumRow(val items: List<AlbumItem>)

private class DatedPhotoAdapter(
    private val activity: Activity,
    private val tileBinder: PhotoTileBinder,
    private val openPhoto: (PhotoItem) -> Unit,
) : BaseAdapter() {
    private var rows: List<PhotoListRow> = emptyList()

    fun submit(items: List<PhotoItem>) {
        rows = buildRows(items)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = rows.size
    override fun getItem(position: Int): PhotoListRow = rows[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getViewTypeCount(): Int = 2
    override fun getItemViewType(position: Int): Int = if (rows[position] is PhotoListRow.Header) 0 else 1
    override fun isEnabled(position: Int): Boolean = false

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return when (val row = rows[position]) {
            is PhotoListRow.Header -> buildHeader(row.label)
            is PhotoListRow.Photos -> buildPhotoRow(row.items)
        }
    }

    private fun buildHeader(label: String): View {
        return TextView(activity).apply {
            text = label
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(17, 24, 38))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(20), activity.dp(18), activity.dp(8), activity.dp(9))
            setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun buildPhotoRow(items: List<PhotoItem>): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(activity.dp(4), 0, activity.dp(4), 0)
            setBackgroundColor(Color.TRANSPARENT)
        }
        val size = ((activity.resources.displayMetrics.widthPixels - activity.dp(20)) / 3f).toInt()
        repeat(3) { index ->
            val item = items.getOrNull(index)
            val cell = buildPhotoCell(size, item)
            row.addView(
                cell,
                LinearLayout.LayoutParams(0, size, 1f).apply {
                    setMargins(activity.dp(2), activity.dp(2), activity.dp(2), activity.dp(2))
                },
            )
        }
        return row
    }

    private fun buildPhotoCell(size: Int, item: PhotoItem?): View {
        val frame = FrameLayout(activity).apply {
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size)
            background = mediaTileBackground(activity)
            elevation = activity.dp(1).toFloat()
            clipToOutline = true
            visibility = if (item == null) View.INVISIBLE else View.VISIBLE
        }
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.TRANSPARENT)
        }
        frame.addView(image, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        val hdrBadge = buildBadge(activity, "HDR")
        frame.addView(hdrBadge, badgeParams(activity, Gravity.TOP or Gravity.END))
        val liveBadge = buildBadge(activity, "LIVE")
        frame.addView(liveBadge, badgeParams(activity, Gravity.TOP or Gravity.START))

        if (item != null) {
            frame.setOnClickListener { openPhoto(item) }
            tileBinder.bind(image, hdrBadge, liveBadge, item, showLive = true)
        }
        return frame
    }

    private fun buildRows(items: List<PhotoItem>): List<PhotoListRow> {
        val result = mutableListOf<PhotoListRow>()
        var lastDay = Long.MIN_VALUE
        val bucket = mutableListOf<PhotoItem>()
        fun flushBucket() {
            if (bucket.isEmpty()) return
            bucket.chunked(3).forEach { result += PhotoListRow.Photos(it) }
            bucket.clear()
        }
        items.forEach { item ->
            val day = dayStart(item.dateMillis)
            if (day != lastDay) {
                flushBucket()
                result += PhotoListRow.Header(dateLabel(item.dateMillis))
                lastDay = day
            }
            bucket += item
        }
        flushBucket()
        return result
    }

    private fun dayStart(millis: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = if (millis > 0L) millis else 0L
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun dateLabel(millis: Long): String {
        if (millis <= 0L) return "未知日期"
        val target = Calendar.getInstance().apply { timeInMillis = millis }
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        return when {
            sameDay(target, today) -> "今天"
            sameDay(target, yesterday) -> "昨天"
            else -> {
                val weekday = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
                val date = SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(millis))
                "$date${weekday[target.get(Calendar.DAY_OF_WEEK) - 1]}"
            }
        }
    }

    private fun sameDay(a: Calendar, b: Calendar): Boolean {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }
}

private class AlbumAdapter(
    private val activity: Activity,
    private val albums: List<AlbumItem>,
    private val tileBinder: PhotoTileBinder,
    private val openAlbum: (AlbumItem) -> Unit,
) : BaseAdapter() {
    override fun getCount(): Int = (albums.size + 1) / 2
    override fun getItem(position: Int): AlbumRow = AlbumRow(albums.drop(position * 2).take(2))
    override fun getItemId(position: Int): Long = position.toLong()
    override fun isEnabled(position: Int): Boolean = false

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val rowItems = albums.drop(position * 2).take(2)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(activity.dp(10), activity.dp(7), activity.dp(10), activity.dp(7))
            setBackgroundColor(Color.TRANSPARENT)
        }
        repeat(2) { index ->
            val album = rowItems.getOrNull(index)
            row.addView(
                buildAlbumCell(album),
                LinearLayout.LayoutParams(0, activity.dp(190), 1f).apply {
                    setMargins(activity.dp(8), 0, activity.dp(8), activity.dp(8))
                },
            )
        }
        return row
    }

    private fun buildAlbumCell(album: AlbumItem?): View {
        val frame = FrameLayout(activity).apply {
            background = mediaTileBackground(activity)
            elevation = activity.dp(4).toFloat()
            clipToOutline = true
            visibility = if (album == null) View.INVISIBLE else View.VISIBLE
        }
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.TRANSPARENT)
        }
        frame.addView(image, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        val hdrBadge = buildBadge(activity, "HDR")
        frame.addView(hdrBadge, badgeParams(activity, Gravity.TOP or Gravity.END))
        val liveBadge = buildBadge(activity, "LIVE")
        frame.addView(liveBadge, badgeParams(activity, Gravity.TOP or Gravity.START))
        val label = TextView(activity).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = darkGlassBackground(activity, activity.dp(16))
            setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8))
        }
        frame.addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        if (album != null) {
            label.text = "${album.name}\n${album.count} 张"
            frame.setOnClickListener { openAlbum(album) }
            tileBinder.bind(image, hdrBadge, liveBadge, album.cover, showLive = false)
        }
        return frame
    }
}

private class PhotoTileBinder(
    private val activity: Activity,
    private val executor: ExecutorService,
    private val metadataExecutor: ExecutorService,
    private val mainHandler: Handler,
) {
    private val thumbCache = object : LruCache<String, Bitmap>(thumbnailCacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
    }
    private val hdrCache = ConcurrentHashMap<String, Boolean>()
    private val motionPhotoCache = ConcurrentHashMap<String, Boolean>()
    private val thumbLoading = ConcurrentHashMap.newKeySet<String>()
    private val hdrLoading = ConcurrentHashMap.newKeySet<String>()
    private val motionPhotoLoading = ConcurrentHashMap.newKeySet<String>()

    fun bind(
        image: ImageView,
        hdrBadge: TextView,
        liveBadge: TextView,
        item: PhotoItem,
        showLive: Boolean,
    ) {
        val key = item.uri.toString()
        image.tag = key
        hdrBadge.tag = key
        liveBadge.tag = key
        image.setImageBitmap(thumbCache.get(key))
        hdrBadge.visibility = if (hdrCache[key] == true) View.VISIBLE else View.GONE
        liveBadge.visibility = if (showLive && isLivePhoto(item)) View.VISIBLE else View.GONE
        loadThumbnailIfNeeded(item, image)
        detectHdrIfNeeded(item, hdrBadge)
        if (showLive) detectMotionPhotoIfNeeded(item, liveBadge)
    }

    fun isMotionPhoto(item: PhotoItem): Boolean = motionPhotoCache[item.uri.toString()] == true

    private fun loadThumbnailIfNeeded(item: PhotoItem, image: ImageView) {
        val key = item.uri.toString()
        if (thumbCache.get(key) != null || !thumbLoading.add(key)) return

        executor.execute {
            val thumb = runCatching {
                activity.contentResolver.loadThumbnail(item.uri, Size(512, 512), null)
            }.getOrNull()

            if (thumb != null) {
                thumbCache.put(key, thumb)
                mainHandler.post {
                    if (image.tag == key) {
                        image.setImageBitmap(thumb)
                    }
                }
            }
            thumbLoading.remove(key)
        }
    }

    private fun detectHdrIfNeeded(item: PhotoItem, hdrBadge: TextView) {
        val key = item.uri.toString()
        if (hdrCache.containsKey(key) || !hdrLoading.add(key)) return

        metadataExecutor.execute {
            val isHdr = isUltraHdr(item.uri)
            hdrCache[key] = isHdr
            mainHandler.post {
                if (hdrBadge.tag == key) {
                    hdrBadge.visibility = if (isHdr) View.VISIBLE else View.GONE
                }
            }
            hdrLoading.remove(key)
        }
    }

    private fun detectMotionPhotoIfNeeded(item: PhotoItem, liveBadge: TextView) {
        if (item.liveVideoUri != null) return
        val key = item.uri.toString()
        if (motionPhotoCache.containsKey(key) || !motionPhotoLoading.add(key)) return

        metadataExecutor.execute {
            val isMotionPhoto = MotionPhotoSupport.hasMotionPhotoMetadata(activity, item.uri)
            motionPhotoCache[key] = isMotionPhoto
            mainHandler.post {
                if (liveBadge.tag == key) {
                    liveBadge.visibility = if (isMotionPhoto) View.VISIBLE else View.GONE
                }
            }
            motionPhotoLoading.remove(key)
        }
    }

    private fun isUltraHdr(uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        return runCatching {
            val source = ImageDecoder.createSource(activity.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longestSide = max(info.size.width, info.size.height)
                val sample = max(1, longestSide / 768)
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val result = bitmap.gainmap != null
            bitmap.recycle()
            result
        }.getOrDefault(false)
    }

    fun clear() {
        thumbCache.evictAll()
        hdrCache.clear()
        motionPhotoCache.clear()
    }

    private fun thumbnailCacheSizeKb(): Int {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return (maxMemoryKb / 8).coerceIn(16 * 1024, 96 * 1024)
    }

    private fun isLivePhoto(item: PhotoItem): Boolean {
        return item.liveVideoUri != null || motionPhotoCache[item.uri.toString()] == true
    }
}

private fun buildBadge(activity: Activity, textValue: String): TextView {
    return TextView(activity).apply {
        text = textValue
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(52, 58, 66))
        background = badgeGlassBackground(activity)
        setPadding(activity.dp(7), activity.dp(3), activity.dp(7), activity.dp(3))
        visibility = View.GONE
    }
}

private fun mediaTileBackground(activity: Activity): GradientDrawable {
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = activity.dp(8).toFloat()
        setColor(Color.argb(126, 255, 255, 255))
        setStroke(activity.dp(1), Color.argb(138, 255, 255, 255))
    }
}

private fun darkGlassBackground(activity: Activity, radius: Int): GradientDrawable {
    return GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.argb(164, 34, 39, 48), Color.argb(132, 9, 13, 20)),
    ).apply {
        cornerRadius = radius.toFloat()
        setStroke(activity.dp(1), Color.argb(55, 255, 255, 255))
    }
}

private fun badgeGlassBackground(activity: Activity): GradientDrawable {
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = activity.dp(5).toFloat()
        setColor(Color.argb(190, 236, 240, 244))
        setStroke(activity.dp(1), Color.argb(150, 255, 255, 255))
    }
}

private fun badgeParams(activity: Activity, gravityValue: Int): FrameLayout.LayoutParams {
    return FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        gravityValue,
    ).apply {
        setMargins(activity.dp(6), activity.dp(6), activity.dp(6), 0)
    }
}

private fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
