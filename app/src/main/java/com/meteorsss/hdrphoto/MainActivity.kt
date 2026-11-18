package com.meteorsss.hdrphoto

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
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
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
    val modifiedSeconds: Long = 0,
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
    private val hdrQueue = LinkedBlockingDeque<Runnable>()
    private val hdrExecutor = ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        hdrQueue,
    ).apply { prestartAllCoreThreads() }
    private val motionExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var allPhotos: List<PhotoItem> = emptyList()
    private var hasSnapshot = false
    private var manualRefresh = false
    private lateinit var refreshButton: TextView
    private lateinit var refreshResult: TextView
    private val clearResult = Runnable { refreshResult.visibility = View.GONE }
    private val visiblePhotos = mutableListOf<PhotoItem>()
    private val albums = mutableListOf<AlbumItem>()
    private lateinit var rootView: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var listView: ListView
    private lateinit var photoNav: TextView
    private lateinit var albumNav: TextView
    private lateinit var tileBinder: PhotoTileBinder
    private lateinit var photoAdapter: DatedPhotoAdapter
    private lateinit var albumAdapter: AlbumAdapter
    private var route = GalleryRoute()
    private val scrollPositions = mutableMapOf<String, Pair<Int, Int>>()
    private var displayedRoute: GalleryRoute? = null
    private lateinit var bottomNav: GlassTabs
    private lateinit var accessButton: TextView
    private var generation = 0
    private var awaitingPermission = false
    private var wasPaused = false
    private val albumBack = android.window.OnBackInvokedCallback { navigate(GalleryRoute(albums = true)) }
    private var openingPhoto = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 26) {
            window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        }
        val darkMode = isDarkMode()
        val systemBarColor = if (darkMode) Color.rgb(9, 13, 20) else Color.WHITE
        window.statusBarColor = systemBarColor
        window.navigationBarColor = systemBarColor
        window.decorView.systemUiVisibility = if (darkMode) {
            0
        } else {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        route = GalleryRoute(savedInstanceState?.getBoolean("albums") ?: false, savedInstanceState?.getString("bucket"))
        savedInstanceState?.let { scrollPositions[route.key] = it.getInt("row") to it.getInt("top") }
        buildLayout()
        if (hasImageAccess()) loadPhotos() else {
            showPermissionPrompt()
            if (!getPreferences(MODE_PRIVATE).getBoolean("asked", false)) requestImageAccess()
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        rememberScroll()
        out.putBoolean("albums", route.albums)
        out.putString("bucket", route.bucket)
        scrollPositions[route.key]?.let { out.putInt("row", it.first); out.putInt("top", it.second) }
    }

    override fun onPause() { wasPaused = true; super.onPause() }
    override fun onResume() {
        super.onResume()
        if (wasPaused && !awaitingPermission) {
            if (hasImageAccess()) loadPhotos() else showPermissionPrompt()
        }
        wasPaused = false
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(clearResult)
        executor.shutdownNow()
        hdrExecutor.shutdownNow()
        motionExecutor.shutdownNow()
        if (::tileBinder.isInitialized) tileBinder.clear()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_IMAGES) awaitingPermission = false
        if (requestCode == REQUEST_IMAGES && hasImageAccess()) {
            loadPhotos()
        } else if (requestCode == REQUEST_IMAGES) {
            showPermissionPrompt()
        }
    }

    private fun buildLayout() {
        tileBinder = PhotoTileBinder(this, executor, hdrQueue, motionExecutor, mainHandler)
        photoAdapter = DatedPhotoAdapter(this, tileBinder) { item, sourceView ->
            openPhoto(item, sourceView)
        }
        albumAdapter = AlbumAdapter(this, albums, tileBinder) { album ->
            navigate(GalleryRoute(true, album.id))
        }

        val root = FrameLayout(this).apply {
            background = appBackground()
        }
        rootView = root
        val content = GlassSource(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(10), dp(14))
            background = glassBackground(
                dp(26),
                if (isDarkMode()) Color.argb(210, 26, 32, 42) else Color.argb(190, 255, 255, 255),
            )
            elevation = dp(8).toFloat()
        }

        statusText = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isDarkMode()) Color.rgb(239, 244, 252) else Color.rgb(16, 22, 32))
            setSingleLine(false)
            includeFontPadding = true
        }
        toolbar.addView(
            statusText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        accessButton = TextView(this).apply {
            text = "管理照片"
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            setTextColor(if (this@MainActivity.isDarkMode()) Color.WHITE else Color.rgb(22, 92, 190))
            setOnClickListener { requestImageAccess() }
        }
        toolbar.addView(accessButton, LinearLayout.LayoutParams(-2, dp(48)))
        refreshButton = TextView(this).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            text = getString(R.string.refresh)
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isDarkMode()) Color.rgb(126, 177, 255) else Color.rgb(38, 92, 170))
            background = glassBackground(
                dp(20),
                if (isDarkMode()) Color.argb(170, 43, 52, 66) else Color.argb(142, 255, 255, 255),
            )
            setPadding(dp(16), 0, dp(16), 0)
            elevation = dp(2).toFloat()
            setOnClickListener {
                if (hasImageAccess()) loadPhotos(manual = true) else {
                    android.widget.Toast.makeText(this@MainActivity, "需要照片访问权限才能刷新", android.widget.Toast.LENGTH_SHORT).show()
                    requestImageAccess()
                }
            }
        }
        toolbar.addView(
            refreshButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)),
        )

        listView = TabSwipeList(this) { albums -> navigate(GalleryRoute(albums)) }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            divider = null
            cacheColorHint = Color.TRANSPARENT
            clipToPadding = false
            setPadding(dp(2), dp(4), dp(2), dp(104))
            adapter = photoAdapter
        }

        bottomNav = GlassTabs(this, content, isDarkMode()) { albums -> navigate(GalleryRoute(albums)) }
        photoNav = buildNavItem("照片") { navigate(GalleryRoute()) }
        albumNav = buildNavItem("相册") { navigate(GalleryRoute(true)) }
        bottomNav.installLabels(photoNav, albumNav)

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
        refreshResult = TextView(this).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = glassBackground(dp(18), Color.argb(238, 35, 46, 65))
        }
        root.addView(refreshResult, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(dp(12), 0, dp(12), dp(94))
        })
        setContentView(root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, 0)
            listView.setPadding(dp(2), dp(4), dp(2), dp(104) + bars.bottom)
            (refreshResult.layoutParams as FrameLayout.LayoutParams).let { params ->
                params.bottomMargin = bars.bottom + dp(94); refreshResult.layoutParams = params
            }
            (bottomNav.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = dp(14) + bars.bottom
                bottomNav.layoutParams = this
            }
            insets
        }
        root.requestApplyInsets()
        updateNavSelection(route.albums)
        updateAccessButton()
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
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun updateAccessButton() {
        val full = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        accessButton.visibility = if (full) View.GONE else View.VISIBLE
        accessButton.text = if (hasImageAccess()) "管理可见照片" else "授权"
    }

    private fun showPermissionPrompt() {
        generation++
        manualRefresh = false; hasSnapshot = false
        refreshButton.isEnabled = true; refreshButton.text = getString(R.string.refresh)
        allPhotos = emptyList(); albums.clear(); visiblePhotos.clear()
        photoAdapter.submit(emptyList()); albumAdapter.notifyDataSetChanged()
        updateAccessButton()
        statusText.text = getString(R.string.permission_needed)
    }

    private fun loadPhotos(manual: Boolean = false) {
        if (manualRefresh) return // A resume callback must not silence an explicit refresh.
        val request = ++generation
        val previous = allPhotos // Immutable snapshot from the preceding successful query.
        val scope = route
        val hadBaseline = hasSnapshot
        val started = android.os.SystemClock.uptimeMillis()
        manualRefresh = manual
        updateAccessButton()
        if (manual) {
            mainHandler.removeCallbacks(clearResult); clearResult.run()
            refreshButton.text = "刷新中…"
            refreshButton.isEnabled = false
        } else if (displayedRoute == null) statusText.text = getString(R.string.loading)
        executor.execute {
            val result = runCatching { queryImages().let { it to buildAlbums(it) } }
            val summary = if (manual) result.getOrNull()?.let { (items, _) ->
                val title = if (scope.bucket != null) "此相册" else if (scope.albums) "相册列表" else "照片列表"
                fun scoped(list: List<PhotoItem>) = if (scope.bucket == null) list else list.filter { it.albumKey() == scope.bucket }
                if (hadBaseline) RefreshChanges.compare(scoped(previous), scoped(items)) { it.uri }.describe(title)
                else "$title 已刷新，共 ${scoped(items).size} 张照片"
            } else null
            // Keep the acknowledgement legible on a fast query without blocking the UI thread.
            mainHandler.postAtTime({
                if (!isDestroyed && !isFinishing && request == generation) {
                    manualRefresh = false
                    refreshButton.isEnabled = true
                    refreshButton.text = getString(R.string.refresh)
                    result.onSuccess { (loaded, loadedAlbums) ->
                        rememberScroll()
                        allPhotos = loaded; hasSnapshot = true
                        albums.clear(); albums.addAll(loadedAlbums)
                        render(route.afterRefresh(albums.map { it.id }.toSet()))
                        if (summary != null) refreshNotice(summary)
                    }.onFailure { error ->
                        if (error is SecurityException) showPermissionPrompt()
                        val message = if (error is SecurityException) "刷新失败，请检查照片访问权限" else "刷新失败，请重试"
                        if (manual) refreshNotice(message) else if (!hasSnapshot) statusText.text = message
                    }
                }
            }, started + if (manual) 550L else 0L)
        }
    }

    private fun refreshNotice(message: String) {
        mainHandler.removeCallbacks(clearResult)
        refreshResult.text = message; refreshResult.visibility = View.VISIBLE
        mainHandler.postDelayed(clearResult, 3500L)
    }

    private fun rememberScroll() {
        displayedRoute?.let { if (listView.childCount > 0) scrollPositions[it.key] = listView.firstVisiblePosition to listView.getChildAt(0).top }
    }

    private fun navigate(next: GalleryRoute) {
        if (next == displayedRoute) { bottomNav.select(next.albums); return }
        rememberScroll()
        render(next)
    }

    private fun render(next: GalleryRoute) {
        val wasNested = displayedRoute?.bucket != null
        route = next
        displayedRoute = next
        if (wasNested != (next.bucket != null)) {
            if (next.bucket != null) onBackInvokedDispatcher.registerOnBackInvokedCallback(0, albumBack)
            else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(albumBack)
        }
        if (next.albums && next.bucket == null) {
            albumAdapter.notifyDataSetChanged()
            if (listView.adapter !== albumAdapter) listView.adapter = albumAdapter
            statusText.text = "相册\n${albums.size} 个相册"
        } else {
            val album = albums.firstOrNull { it.id == next.bucket }
            visiblePhotos.clear()
            visiblePhotos.addAll(if (album == null) allPhotos else allPhotos.filter { it.albumKey() == album.id })
            photoAdapter.submit(visiblePhotos)
            if (listView.adapter !== photoAdapter) listView.adapter = photoAdapter
            statusText.text = if (visiblePhotos.isEmpty()) getString(R.string.empty_gallery)
                else "${album?.name ?: "照片"}\n${visiblePhotos.size} 张照片"
        }
        scrollPositions[next.key].let { listView.setSelectionFromTop(it?.first ?: 0, it?.second ?: listView.paddingTop) }
        if (!hasImageAccess()) statusText.text = getString(R.string.permission_needed)
        updateNavSelection(next.albums)
    }

    private fun updateNavSelection(showingAlbums: Boolean) {
        val selected = if (isDarkMode()) Color.rgb(223, 238, 255) else Color.rgb(12, 64, 150)
        val normal = if (isDarkMode()) Color.rgb(212, 220, 230) else Color.rgb(48, 58, 72)
        photoNav.isSelected = !showingAlbums; albumNav.isSelected = showingAlbums
        photoNav.setTextColor(if (showingAlbums) normal else selected)
        albumNav.setTextColor(if (showingAlbums) selected else normal)
        bottomNav.select(showingAlbums)
    }

    private fun openPhoto(item: PhotoItem, sourceView: View) {
        if (openingPhoto) return
        openingPhoto = true
        val position = visiblePhotos.indexOfFirst { it.uri == item.uri }
        if (position < 0) { openingPhoto = false; return }
        GallerySession.setPhotos(visiblePhotos, position)
        val location = IntArray(2)
        sourceView.getLocationOnScreen(location)
        val sourceBounds = Rect(
            location[0],
            location[1],
            location[0] + sourceView.width,
            location[1] + sourceView.height,
        )
        val intent = Intent(this, PhotoActivity::class.java).apply {
            data = item.uri
            item.liveVideoUri?.let {
                putExtra(PhotoActivity.EXTRA_LIVE_VIDEO_URI, it.toString())
            }
            putExtra(PhotoActivity.EXTRA_MOTION_PHOTO, tileBinder.isMotionPhoto(item))
        }
        captureGalleryPreview { bitmap ->
            if (isFinishing || isDestroyed) {
                bitmap?.takeIf { !it.isRecycled }?.recycle()
                openingPhoto = false
                return@captureGalleryPreview
            }
            GallerySession.setLaunchPreview(bitmap, sourceBounds, item.uri)
            startActivity(intent)
            openingPhoto = false
        }
    }

    private fun captureGalleryPreview(onCaptured: (Bitmap?) -> Unit) {
        val width = rootView.width
        val height = rootView.height
        if (width <= 0 || height <= 0) {
            onCaptured(null)
            return
        }
        val bitmap = runCatching {
            Bitmap.createBitmap(
                (width * GALLERY_PREVIEW_SCALE).toInt().coerceAtLeast(1),
                (height * GALLERY_PREVIEW_SCALE).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
        }.getOrNull()
        if (bitmap == null) {
            onCaptured(null)
            return
        }
        PixelCopy.request(
            window,
            bitmap,
            { result ->
                if (result == PixelCopy.SUCCESS) {
                    onCaptured(bitmap)
                } else {
                    bitmap.recycle()
                    onCaptured(null)
                }
            },
            mainHandler,
        )
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
        (contentResolver.query(collection, projection.toTypedArray(), null, null, null)
            ?: throw IllegalStateException("MediaStore is unavailable")).use { cursor ->
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
                    modifiedSeconds = cursor.safeLong(modifiedColumn),
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
        return items.groupBy { it.albumKey() }
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
        if (awaitingPermission) return
        awaitingPermission = true
        getPreferences(MODE_PRIVATE).edit().putBoolean("asked", true).apply()
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
        val colors = if (isDarkMode()) {
            intArrayOf(Color.rgb(9, 13, 20), Color.rgb(14, 20, 30), Color.rgb(8, 12, 19))
        } else {
            intArrayOf(Color.rgb(247, 251, 255), Color.rgb(239, 246, 251), Color.rgb(249, 251, 255))
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors)
    }

    private fun glassBackground(radius: Int, fillColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(fillColor)
            setStroke(
                dp(1),
                if (isDarkMode()) Color.argb(72, 194, 218, 255) else Color.argb(145, 255, 255, 255),
            )
        }
    }

    companion object {
        private const val REQUEST_IMAGES = 2001
    }
}

private fun PhotoItem.albumKey() = bucketId.ifBlank { bucketName.ifBlank { "unknown" } }

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
    private val openPhoto: (PhotoItem, View) -> Unit,
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
            is PhotoListRow.Header -> (convertView as? TextView)?.apply { text = row.label } ?: buildHeader(row.label)
            is PhotoListRow.Photos -> bindPhotoRow((convertView as? LinearLayout) ?: buildPhotoRow(), row.items)
        }
    }

    private fun buildHeader(label: String): View {
        return TextView(activity).apply {
            text = label
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (activity.isDarkMode()) Color.rgb(230, 236, 246) else Color.rgb(17, 24, 38))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(20), activity.dp(18), activity.dp(8), activity.dp(9))
            setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun buildPhotoRow(): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(activity.dp(4), 0, activity.dp(4), 0)
            setBackgroundColor(Color.TRANSPARENT)
        }
        val size = ((activity.resources.displayMetrics.widthPixels - activity.dp(20)) / 3f).toInt()
        repeat(3) {
            val cell = buildPhotoCell(size)
            row.addView(
                cell,
                LinearLayout.LayoutParams(0, size, 1f).apply {
                    setMargins(activity.dp(2), activity.dp(2), activity.dp(2), activity.dp(2))
                },
            )
        }
        return row
    }

    private fun buildPhotoCell(size: Int): View {
        val frame = FrameLayout(activity).apply {
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size)
            background = mediaTileBackground(activity)
            elevation = activity.dp(1).toFloat()
            clipToOutline = true

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

        return frame
    }

    private fun bindPhotoRow(row: LinearLayout, items: List<PhotoItem>): View {
        repeat(3) { i ->
            val frame = row.getChildAt(i) as FrameLayout
            val item = items.getOrNull(i)
            frame.visibility = if (item == null) View.INVISIBLE else View.VISIBLE
            val image = frame.getChildAt(0) as ImageView
            if (item == null) {
                frame.setOnClickListener(null)
                repeat(frame.childCount) { frame.getChildAt(it).tag = null }
                image.setImageDrawable(null)
            } else {
                frame.contentDescription = item.name
                frame.setOnClickListener { openPhoto(item, image) }
                tileBinder.bind(image, frame.getChildAt(1) as TextView, frame.getChildAt(2) as TextView, item, true)
            }
        }
        return row
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
    private val hdrQueue: LinkedBlockingDeque<Runnable>,
    private val motionExecutor: ExecutorService,
    private val mainHandler: Handler,
) {
    private val thumbCache = object : LruCache<String, Bitmap>(thumbnailCacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
    }
    private val hdrCache = ConcurrentHashMap<String, Boolean>()
    private val motionPhotoCache = ConcurrentHashMap<String, Boolean>()
    // Only current bindings are retained. One completed URI notifies all live recipients.
    private val images = java.util.WeakHashMap<ImageView, String>()
    private val hdrLabels = java.util.WeakHashMap<TextView, String>()
    private val liveLabels = java.util.WeakHashMap<TextView, String>()
    private val thumbLoading = mutableSetOf<String>()
    private val hdrLoading = mutableSetOf<String>()
    private val motionPhotoLoading = mutableSetOf<String>()
    private val hdrStore = activity.getSharedPreferences("ultra-hdr-cache-v1", Activity.MODE_PRIVATE)

    fun bind(
        image: ImageView,
        hdrBadge: TextView,
        liveBadge: TextView,
        item: PhotoItem,
        showLive: Boolean,
    ) {
        val key = item.uri.toString()
        images[image] = key; hdrLabels[hdrBadge] = key
        if (showLive) liveLabels[liveBadge] = key else liveLabels.remove(liveBadge)
        image.tag = key
        hdrBadge.tag = key
        liveBadge.tag = key
        image.setImageBitmap(thumbCache.get(key))
        restoreHdrResult(item)
        hdrBadge.visibility = if (hdrCache[key] == true) View.VISIBLE else View.GONE
        liveBadge.visibility = if (showLive && isLivePhoto(item)) View.VISIBLE else View.GONE
        loadThumbnailIfNeeded(item)
        detectHdrIfNeeded(item)
        if (showLive) detectMotionPhotoIfNeeded(item)
    }

    fun isMotionPhoto(item: PhotoItem): Boolean = motionPhotoCache[item.uri.toString()] == true

    private fun loadThumbnailIfNeeded(item: PhotoItem) {
        val key = item.uri.toString()
        if (thumbCache.get(key) != null || !thumbLoading.add(key)) return

        executor.execute {
            val thumb = runCatching {
                activity.contentResolver.loadThumbnail(item.uri, Size(512, 512), null)
            }.getOrNull()

            mainHandler.post {
                thumbLoading.remove(key)
                if (thumb != null && !activity.isDestroyed) {
                    thumbCache.put(key, thumb)
                    images.forEach { (v, k) -> if (k == key && v.tag == k) v.setImageBitmap(thumb) }
                }
            }
        }
    }

    private fun detectHdrIfNeeded(item: PhotoItem) {
        val key = item.uri.toString()
        if (hdrCache.containsKey(key) || !hdrLoading.add(key)) return

        hdrQueue.offerFirst(Runnable {
            val isHdr = isUltraHdr(item.uri)
            mainHandler.post {
                hdrLoading.remove(key)
                if (activity.isDestroyed || isHdr == null) return@post
                hdrCache[key] = isHdr
                hdrStore.edit().putBoolean(hdrStoreKey(item), isHdr).apply()
                hdrLabels.forEach { (v,k) -> if (k == key && v.tag == k) v.visibility = if (isHdr) View.VISIBLE else View.GONE }
            }
        })
    }

    private fun detectMotionPhotoIfNeeded(item: PhotoItem) {
        if (item.liveVideoUri != null) return
        val key = item.uri.toString()
        if (motionPhotoCache.containsKey(key) || !motionPhotoLoading.add(key)) return

        motionExecutor.execute {
            val isMotionPhoto = MotionPhotoSupport.hasMotionPhotoMetadata(activity, item.uri)
            mainHandler.post {
                motionPhotoLoading.remove(key)
                if (activity.isDestroyed) return@post
                motionPhotoCache[key] = isMotionPhoto
                liveLabels.forEach { (v,k) -> if (k == key && v.tag == k) v.visibility = if (isMotionPhoto) View.VISIBLE else View.GONE }
            }
        }
    }

    private fun isUltraHdr(uri: Uri): Boolean? {
        if (Build.VERSION.SDK_INT < 34) return false
        return runCatching {
            val source = ImageDecoder.createSource(activity.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longestSide = max(info.size.width, info.size.height)
                val sample = max(1, longestSide / HDR_DETECTION_DIMENSION)
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val result = bitmap.gainmap != null
            bitmap.recycle()
            result
        }.getOrNull()
    }

    private fun restoreHdrResult(item: PhotoItem) {
        val key = item.uri.toString()
        if (hdrCache.containsKey(key)) return
        val storedKey = hdrStoreKey(item)
        if (hdrStore.contains(storedKey)) hdrCache[key] = hdrStore.getBoolean(storedKey, false)
    }

    private fun hdrStoreKey(item: PhotoItem): String {
        return "${item.uri}|${item.sizeBytes}|${item.dateMillis}"
    }

    fun clear() {
        images.clear(); hdrLabels.clear(); liveLabels.clear()
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
        setTextColor(
            if (activity.isDarkMode()) Color.rgb(224, 233, 246) else Color.rgb(52, 58, 66),
        )
        background = badgeGlassBackground(activity)
        setPadding(activity.dp(7), activity.dp(3), activity.dp(7), activity.dp(3))
        visibility = View.GONE
    }
}

private fun mediaTileBackground(activity: Activity): GradientDrawable {
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = activity.dp(8).toFloat()
        setColor(
            if (activity.isDarkMode()) Color.argb(210, 29, 36, 47) else Color.argb(126, 255, 255, 255),
        )
        setStroke(
            activity.dp(1),
            if (activity.isDarkMode()) Color.argb(78, 173, 201, 238) else Color.argb(138, 255, 255, 255),
        )
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
        setColor(
            if (activity.isDarkMode()) Color.argb(218, 38, 47, 61) else Color.argb(190, 236, 240, 244),
        )
        setStroke(
            activity.dp(1),
            if (activity.isDarkMode()) Color.argb(96, 189, 215, 248) else Color.argb(150, 255, 255, 255),
        )
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

private const val HDR_DETECTION_DIMENSION = 192
private const val GALLERY_PREVIEW_SCALE = 0.5f

private fun Activity.isDarkMode(): Boolean {
    return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
}

private fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
