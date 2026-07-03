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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
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
        tileBinder = PhotoTileBinder(this, executor, mainHandler)
        photoAdapter = DatedPhotoAdapter(this, tileBinder) { item ->
            openPhoto(item)
        }
        albumAdapter = AlbumAdapter(this, albums, tileBinder) { album ->
            showPhotos(album)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(14), dp(12))
            setBackgroundColor(Color.WHITE)
        }

        statusText = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            setTextColor(Color.rgb(20, 20, 20))
            setSingleLine(false)
            includeFontPadding = true
        }
        toolbar.addView(
            statusText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        val refreshButton = Button(this).apply {
            text = getString(R.string.refresh)
            setOnClickListener {
                if (hasImageAccess()) loadPhotos() else requestImageAccess()
            }
        }
        toolbar.addView(
            refreshButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)),
        )

        listView = ListView(this).apply {
            setBackgroundColor(Color.WHITE)
            divider = null
            cacheColorHint = Color.TRANSPARENT
            clipToPadding = false
            setPadding(dp(2), dp(2), dp(2), dp(8))
            adapter = photoAdapter
        }

        val bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        photoNav = buildNavItem("照片") {
            showPhotos(null)
        }
        albumNav = buildNavItem("相册") {
            showAlbums()
        }
        bottomNav.addView(photoNav, LinearLayout.LayoutParams(0, dp(58), 1f))
        bottomNav.addView(albumNav, LinearLayout.LayoutParams(0, dp(58), 1f))

        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(
            bottomNav,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        setContentView(root)
        updateNavSelection(showingAlbums = false)
    }

    private fun buildNavItem(textValue: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 18f
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
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
        val selected = Color.rgb(67, 133, 245)
        val normal = Color.rgb(120, 120, 120)
        photoNav.setTextColor(if (showingAlbums) normal else selected)
        albumNav.setTextColor(if (showingAlbums) selected else normal)
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
            textSize = 22f
            setTextColor(Color.rgb(20, 20, 20))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(20), activity.dp(18), activity.dp(8), activity.dp(10))
            setBackgroundColor(Color.WHITE)
        }
    }

    private fun buildPhotoRow(items: List<PhotoItem>): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(activity.dp(2), 0, activity.dp(2), 0)
            setBackgroundColor(Color.WHITE)
        }
        val size = ((activity.resources.displayMetrics.widthPixels - activity.dp(8)) / 3f).toInt()
        repeat(3) { index ->
            val item = items.getOrNull(index)
            val cell = buildPhotoCell(size, item)
            row.addView(
                cell,
                LinearLayout.LayoutParams(0, size, 1f).apply {
                    setMargins(activity.dp(1), activity.dp(1), activity.dp(1), activity.dp(1))
                },
            )
        }
        return row
    }

    private fun buildPhotoCell(size: Int, item: PhotoItem?): View {
        val frame = FrameLayout(activity).apply {
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size)
            setBackgroundColor(Color.rgb(240, 240, 240))
            visibility = if (item == null) View.INVISIBLE else View.VISIBLE
        }
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(235, 235, 235))
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
            setPadding(activity.dp(10), activity.dp(6), activity.dp(10), activity.dp(6))
            setBackgroundColor(Color.WHITE)
        }
        repeat(2) { index ->
            val album = rowItems.getOrNull(index)
            row.addView(
                buildAlbumCell(album),
                LinearLayout.LayoutParams(0, activity.dp(190), 1f).apply {
                    setMargins(activity.dp(6), 0, activity.dp(6), 0)
                },
            )
        }
        return row
    }

    private fun buildAlbumCell(album: AlbumItem?): View {
        val frame = FrameLayout(activity).apply {
            setBackgroundColor(Color.rgb(235, 235, 235))
            visibility = if (album == null) View.INVISIBLE else View.VISIBLE
        }
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(230, 230, 230))
        }
        frame.addView(image, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        val hdrBadge = buildBadge(activity, "HDR")
        frame.addView(hdrBadge, badgeParams(activity, Gravity.TOP or Gravity.END))
        val liveBadge = buildBadge(activity, "LIVE")
        frame.addView(liveBadge, badgeParams(activity, Gravity.TOP or Gravity.START))
        val label = TextView(activity).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding(activity.dp(10), activity.dp(7), activity.dp(10), activity.dp(7))
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
    private val mainHandler: Handler,
) {
    private val thumbCache = ConcurrentHashMap<String, Bitmap>()
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
        image.setImageBitmap(thumbCache[key])
        hdrBadge.visibility = if (hdrCache[key] == true) View.VISIBLE else View.GONE
        liveBadge.visibility = if (showLive && isLivePhoto(item)) View.VISIBLE else View.GONE
        loadThumbnailIfNeeded(item, image)
        detectHdrIfNeeded(item, hdrBadge)
        if (showLive) detectMotionPhotoIfNeeded(item, liveBadge)
    }

    fun isMotionPhoto(item: PhotoItem): Boolean = motionPhotoCache[item.uri.toString()] == true

    private fun loadThumbnailIfNeeded(item: PhotoItem, image: ImageView) {
        val key = item.uri.toString()
        if (thumbCache.containsKey(key) || !thumbLoading.add(key)) return

        executor.execute {
            val thumb = runCatching {
                activity.contentResolver.loadThumbnail(item.uri, Size(512, 512), null)
            }.getOrNull()

            if (thumb != null) {
                thumbCache[key] = thumb
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

        executor.execute {
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

        executor.execute {
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
            bitmap.gainmap != null
        }.getOrDefault(false)
    }

    private fun isLivePhoto(item: PhotoItem): Boolean {
        return item.liveVideoUri != null || motionPhotoCache[item.uri.toString()] == true
    }
}

private fun buildBadge(activity: Activity, textValue: String): TextView {
    return TextView(activity).apply {
        text = textValue
        textSize = 12f
        typeface = android.graphics.Typeface.DEFAULT
        setTextColor(Color.rgb(58, 58, 58))
        setBackgroundResource(R.drawable.badge_hdr)
        setPadding(activity.dp(7), activity.dp(3), activity.dp(7), activity.dp(3))
        visibility = View.GONE
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
