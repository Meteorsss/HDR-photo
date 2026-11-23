package com.meteorsss.hdrphoto

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Gainmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.view.ViewConfiguration
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.util.concurrent.FutureTask
import kotlin.math.abs

/** Runs against real Android views/animators. Uses two generated images, never user media. */
class InteractionProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun <T> ui(block: () -> T): T {
        val task = FutureTask<T> { block() }; runOnMainSync(task); return task.get()
    }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun labels(a: Activity) = views(a.window.decorView).filterIsInstance<TextView>().filter { it.isShown }
    private fun await(message: String, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < end) { if (ui(check)) return; SystemClock.sleep(20) }
        error(message)
    }
    private fun launch(cls: Class<*>) = startActivitySync(Intent(targetContext,cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun field(a: Activity, name: String): Any? = a.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(a)
    private fun setField(a: Activity, name: String, value: Any?) { a.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(a, value) }
    private var touchDown = 0L
    private fun touch(a: Activity, photo: View, action: Int) {
        ui {
            if (action == MotionEvent.ACTION_DOWN) touchDown = SystemClock.uptimeMillis()
            val e = MotionEvent.obtain(touchDown, SystemClock.uptimeMillis(), action, photo.width*.5f, photo.height*.5f, 0)
            a.dispatchTouchEvent(e); e.recycle()
        }
    }
    private fun hold(a: Activity, photo: View) {
        SystemClock.sleep(350) // Independent gesture, not a double tap.
        touch(a,photo,MotionEvent.ACTION_DOWN)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 120)
    }
    override fun onStart() {
        val result = runCatching {
            val main = launch(MainActivity::class.java)
            await("gallery not loaded") { labels(main).any { it.text == "没有找到照片" || it.text.startsWith("照片\n") } }
            for ((tab, scope) in listOf("照片" to "照片列表", "相册" to "相册列表")) {
                ui {
                    labels(main).first { it.text == tab }.performClick()
                    val refresh = labels(main).first { it.text == "刷新" }; refresh.performClick()
                    check(!refresh.isEnabled && refresh.text == "刷新中…")
                }
                await("no completion for $scope") { labels(main).any { it.text == "$scope 已刷新，无变化" } }
            }
            val photos = listOf(Color.RED, Color.BLUE).mapIndexed { i, color ->
                val f = File(targetContext.cacheDir,"probe-page-$i.png")
                val bmp = Bitmap.createBitmap(1600,2400,Bitmap.Config.ARGB_8888); bmp.eraseColor(color)
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }; bmp.recycle()
                PhotoItem(i+1L,f.name,Uri.fromFile(f),1600,2400,null)
            }
            ui { GallerySession.setPhotos(photos,0) }
            val viewer = launch(PhotoActivity::class.java)
            val photo = ui { views(viewer.window.decorView).filterIsInstance<ZoomImageView>().single() }
            await("no initial image") { photo.width > 0 && photo.drawable != null }
            ui { check(photo.drawable.intrinsicWidth == 1600) { "initial image was a thumbnail" } }
            val initialFrame = ui { photo.drawable }
            SystemClock.sleep(700)
            ui { check(photo.drawable === initialFrame) { "initial frame was replaced" } }
            for ((step,target) in listOf(1 to 1,-1 to 0)) {
                val frames = mutableListOf<Float>()
                val width = ui { photo.width.toFloat() }; val distance = .34f * width
                var movingFrame: Drawable? = null
                val observer = android.view.ViewTreeObserver.OnPreDrawListener {
                    frames.add(abs(photo.translationX))
                    val adjacent = field(viewer,"neighbor") as ImageView
                    if (adjacent.visibility == View.VISIBLE) movingFrame = adjacent.drawable
                    true
                }
                ui {
                    val down = SystemClock.uptimeMillis()
                    fun finger(action: Int, x: Float) {
                        val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,photo.height*.5f,0)
                        viewer.dispatchTouchEvent(e); e.recycle()
                    }
                    finger(MotionEvent.ACTION_DOWN,width*.5f)
                    finger(MotionEvent.ACTION_MOVE,width*.5f-step*distance)
                    finger(MotionEvent.ACTION_UP,width*.5f-step*distance)
                    check(GallerySession.index != target) { "index changed on finger-up" }
                    photo.viewTreeObserver.addOnPreDrawListener(observer)
                }
                await("page did not settle: $step") { GallerySession.index == target && photo.translationX == 0f }
                ui {
                    photo.viewTreeObserver.removeOnPreDrawListener(observer)
                    check(frames.any { it > distance+1 && it < width-1 }) { "no intermediate draw after release: $frames" }
                    check(movingFrame?.intrinsicWidth == 1600) { "swipe used a downsampled preview" }
                    check(photo.drawable === movingFrame) { "handoff replaced the displayed frame" }
                }
                SystemClock.sleep(700)
                ui { check(photo.drawable === movingFrame) { "settled image upgraded to a different frame" } }
                SystemClock.sleep(350) // Distinguish the second intentional swipe from a double tap.
            }
            ui { viewer.finish() }
            val clip = File(targetContext.cacheDir, "live-probe.mp4")
            context.assets.open("live-probe.mp4").use { input -> clip.outputStream().use { input.copyTo(it) } }
            ui { GallerySession.setPhotos(listOf(photos.first().copy(liveVideoUri = Uri.fromFile(clip))),0) }
            val live = launch(PhotoActivity::class.java)
            val livePhoto = ui { views(live.window.decorView).filterIsInstance<ZoomImageView>().single() }
            await("live still not ready") { field(live,"displayedUri") != null && labels(live).any { it.text == "LIVE" } }
            // A LIVE + HDR photo must play, not strip the gainmap.
            ui { (livePhoto.drawable as BitmapDrawable).bitmap.gainmap = Gainmap(Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)) }
            val still = ui { livePhoto.drawable }
            touch(live,livePhoto,MotionEvent.ACTION_DOWN); touch(live,livePhoto,MotionEvent.ACTION_UP)
            ui { check(field(live,"mediaPlayer") == null) { "tap should not play" } }
            hold(live,livePhoto)
            await("hold did not play LIVE") { (field(live,"mediaPlayer") as? MediaPlayer)?.isPlaying == true && livePhoto.visibility == View.GONE }
            ui {
                check((field(live,"mediaPlayer") as MediaPlayer).isLooping)
                check((livePhoto.drawable as BitmapDrawable).bitmap.gainmap != null) { "LIVE hold incorrectly selected SDR" }
            }
            touch(live,livePhoto,MotionEvent.ACTION_UP)
            ui { check(field(live,"mediaPlayer") == null && livePhoto.visibility == View.VISIBLE && livePhoto.drawable === still) }
            hold(live,livePhoto)
            await("second hold did not play") { (field(live,"mediaPlayer") as? MediaPlayer)?.isPlaying == true }
            touch(live,livePhoto,MotionEvent.ACTION_CANCEL)
            ui { check(field(live,"mediaPlayer") == null && livePhoto.visibility == View.VISIBLE) }
            // Extraction finishing after release must never autoplay.
            ui { setField(live,"liveVideoUri",null); setField(live,"liveLookupFinished",false) }
            hold(live,livePhoto); touch(live,livePhoto,MotionEvent.ACTION_UP)
            ui {
                val token = (field(live,"loadToken") as java.util.concurrent.atomic.AtomicInteger).get()
                live.javaClass.getDeclaredMethod("setLiveVideo",Uri::class.java,Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(live,Uri.fromFile(clip),token,false)
                check(field(live,"mediaPlayer") == null && field(live,"pendingVideoUri") == null)
                labels(live).first { it.text == "LIVE" }.performClick()
            }
            await("LIVE button stopped working") { (field(live,"mediaPlayer") as? MediaPlayer)?.isPlaying == true }
            ui { labels(live).first { it.text == "PHOTO" }.performClick(); check(livePhoto.drawable === still) }
            ui { live.finish() }
            ui { GallerySession.setPhotos(listOf(photos.first()),0) }
            val hdr = launch(PhotoActivity::class.java)
            val hdrPhoto = ui { views(hdr.window.decorView).filterIsInstance<ZoomImageView>().single() }
            await("non-LIVE image not ready") { field(hdr,"displayedUri") != null && field(hdr,"liveLookupFinished") == true }
            ui { (hdrPhoto.drawable as BitmapDrawable).bitmap.gainmap = Gainmap(Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)) }
            hold(hdr,hdrPhoto)
            ui { check((hdrPhoto.drawable as BitmapDrawable).bitmap.gainmap == null) { "non-LIVE HDR hold no longer shows SDR" } }
            touch(hdr,hdrPhoto,MotionEvent.ACTION_UP)
            ui { check((hdrPhoto.drawable as BitmapDrawable).bitmap.gainmap != null); hdr.finish(); main.finish() }
            check(clip.exists()) { "paired video source was deleted" }
            "PASS: refresh; full-quality initial frame and left/right handoff identity; LIVE hold/release/cancel, delayed metadata, button playback; SDR hold retained"
        }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream",result.getOrElse { it.stackTraceToString() })
        })
    }
}
