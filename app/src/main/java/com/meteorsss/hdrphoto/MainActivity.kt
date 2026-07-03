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
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
)

class MainActivity : Activity() {
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val photos = mutableListOf<PhotoItem>()
    private lateinit var statusText: TextView
    private lateinit var gridView: GridView
    private lateinit var adapter: PhotoGridAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 26) {
            window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.rgb(5, 5, 5))
        }

        statusText = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 18f
            setTextColor(Color.WHITE)
            setSingleLine(true)
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
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)),
        )

        gridView = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(124)
            horizontalSpacing = dp(2)
            verticalSpacing = dp(2)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setBackgroundColor(Color.BLACK)
            clipToPadding = false
            setPadding(dp(2), dp(2), dp(2), dp(8))
            onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, position, _ ->
                val item = photos[position]
                GallerySession.setPhotos(photos, position)
                val intent = Intent(this@MainActivity, PhotoActivity::class.java).apply {
                    data = item.uri
                    item.liveVideoUri?.let {
                        putExtra(PhotoActivity.EXTRA_LIVE_VIDEO_URI, it.toString())
                    }
                    putExtra(PhotoActivity.EXTRA_MOTION_PHOTO, this@MainActivity.adapter.isMotionPhoto(item))
                }
                startActivity(intent)
            }
        }
        adapter = PhotoGridAdapter(this, photos, executor, mainHandler)
        gridView.adapter = adapter

        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            gridView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        setContentView(root)
    }

    private fun showPermissionPrompt() {
        photos.clear()
        adapter.notifyDataSetChanged()
        statusText.text = getString(R.string.permission_needed)
        gridView.emptyView = TextView(this).apply {
            text = getString(R.string.grant_access)
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setOnClickListener { requestImageAccess() }
        }
        requestImageAccess()
    }

    private fun loadPhotos() {
        statusText.text = getString(R.string.loading)
        executor.execute {
            val loaded = queryImages()
            mainHandler.post {
                photos.clear()
                photos.addAll(loaded)
                adapter.notifyDataSetChanged()
                statusText.text = if (loaded.isEmpty()) {
                    getString(R.string.empty_gallery)
                } else {
                    "${getString(R.string.app_name)}  ${loaded.size}"
                }
            }
        }
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
        )
        if (Build.VERSION.SDK_INT >= 29) {
            projection += MediaStore.Images.Media.RELATIVE_PATH
        } else {
            projection += MediaStore.Images.Media.DATA
        }
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        contentResolver.query(collection, projection.toTypedArray(), null, null, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val pathColumn = if (Build.VERSION.SDK_INT >= 29) {
                cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            }
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn).orEmpty()
                val path = cursor.getString(pathColumn).orEmpty()
                result += PhotoItem(
                    id = id,
                    name = name,
                    uri = ContentUris.withAppendedId(collection, id),
                    width = cursor.getInt(widthColumn),
                    height = cursor.getInt(heightColumn),
                    liveVideoUri = videoPairs[livePairKey(name, path)],
                )
            }
        }
        return result
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
                if (extension != "mov") continue
                val id = cursor.getLong(idColumn)
                val path = cursor.getString(pathColumn).orEmpty()
                pairs[livePairKey(name, path)] = ContentUris.withAppendedId(collection, id)
            }
        }
        return pairs
    }

    private fun hasImageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            val hasImages = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_GRANTED
            val hasVideos = hasVideoAccess()
            (hasImages && hasVideos) ||
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
        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissions(permissions, REQUEST_IMAGES)
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

class PhotoGridAdapter(
    private val activity: Activity,
    private val photos: List<PhotoItem>,
    private val executor: ExecutorService,
    private val mainHandler: Handler,
) : BaseAdapter() {
    private val thumbCache = ConcurrentHashMap<String, Bitmap>()
    private val hdrCache = ConcurrentHashMap<String, Boolean>()
    private val motionPhotoCache = ConcurrentHashMap<String, Boolean>()
    private val thumbLoading = ConcurrentHashMap.newKeySet<String>()
    private val hdrLoading = ConcurrentHashMap.newKeySet<String>()
    private val motionPhotoLoading = ConcurrentHashMap.newKeySet<String>()

    override fun getCount(): Int = photos.size
    override fun getItem(position: Int): PhotoItem = photos[position]
    override fun getItemId(position: Int): Long = photos[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val holder: Holder
        val view = if (convertView == null) {
            val frame = FrameLayout(activity).apply {
                layoutParams = AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    activity.dp(128),
                )
                setBackgroundColor(Color.rgb(12, 12, 12))
            }
            val image = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.rgb(18, 18, 18))
            }
            frame.addView(image, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

            val hdrBadge = TextView(activity).apply {
                text = "HDR"
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT
                setTextColor(Color.rgb(58, 58, 58))
                setBackgroundResource(R.drawable.badge_hdr)
                setPadding(activity.dp(7), activity.dp(3), activity.dp(7), activity.dp(3))
                visibility = View.GONE
            }
            val badgeParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                setMargins(0, activity.dp(6), activity.dp(6), 0)
            }
            frame.addView(hdrBadge, badgeParams)

            val liveBadge = TextView(activity).apply {
                text = "LIVE"
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT
                setTextColor(Color.rgb(58, 58, 58))
                setBackgroundResource(R.drawable.badge_hdr)
                setPadding(activity.dp(7), activity.dp(3), activity.dp(7), activity.dp(3))
                visibility = View.GONE
            }
            val liveParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply {
                setMargins(activity.dp(6), activity.dp(6), 0, 0)
            }
            frame.addView(liveBadge, liveParams)
            holder = Holder(image, hdrBadge, liveBadge)
            frame.tag = holder
            frame
        } else {
            holder = convertView.tag as Holder
            convertView
        }

        val item = photos[position]
        val key = item.uri.toString()
        holder.image.tag = key
        holder.hdrBadge.tag = key
        holder.liveBadge.tag = key
        holder.image.setImageBitmap(thumbCache[key])
        holder.hdrBadge.visibility = if (hdrCache[key] == true) View.VISIBLE else View.GONE
        holder.liveBadge.visibility = if (isLivePhoto(item)) View.VISIBLE else View.GONE
        loadThumbnailIfNeeded(item, holder)
        detectHdrIfNeeded(item, holder)
        detectMotionPhotoIfNeeded(item, holder)
        return view
    }

    fun isMotionPhoto(item: PhotoItem): Boolean = motionPhotoCache[item.uri.toString()] == true

    private fun loadThumbnailIfNeeded(item: PhotoItem, holder: Holder) {
        val key = item.uri.toString()
        if (thumbCache.containsKey(key) || !thumbLoading.add(key)) return

        executor.execute {
            val thumb = runCatching {
                activity.contentResolver.loadThumbnail(item.uri, Size(512, 512), null)
            }.getOrNull()

            if (thumb != null) {
                thumbCache[key] = thumb
                mainHandler.post {
                    if (holder.image.tag == key) {
                        holder.image.setImageBitmap(thumb)
                    }
                }
            }
            thumbLoading.remove(key)
        }
    }

    private fun detectHdrIfNeeded(item: PhotoItem, holder: Holder) {
        val key = item.uri.toString()
        if (hdrCache.containsKey(key) || !hdrLoading.add(key)) return

        executor.execute {
            val isHdr = isUltraHdr(item.uri)
            hdrCache[key] = isHdr
            mainHandler.post {
                if (holder.hdrBadge.tag == key) {
                    holder.hdrBadge.visibility = if (isHdr) View.VISIBLE else View.GONE
                }
            }
            hdrLoading.remove(key)
        }
    }

    private fun detectMotionPhotoIfNeeded(item: PhotoItem, holder: Holder) {
        if (item.liveVideoUri != null) return
        val key = item.uri.toString()
        if (motionPhotoCache.containsKey(key) || !motionPhotoLoading.add(key)) return

        executor.execute {
            val isMotionPhoto = MotionPhotoSupport.hasMotionPhotoMetadata(activity, item.uri)
            motionPhotoCache[key] = isMotionPhoto
            mainHandler.post {
                if (holder.liveBadge.tag == key) {
                    holder.liveBadge.visibility = if (isMotionPhoto) View.VISIBLE else View.GONE
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

    private data class Holder(
        val image: ImageView,
        val hdrBadge: TextView,
        val liveBadge: TextView,
    )
}

private fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
