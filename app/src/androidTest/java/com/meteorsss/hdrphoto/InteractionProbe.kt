package com.meteorsss.hdrphoto

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
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
                val bmp = Bitmap.createBitmap(160,240,Bitmap.Config.ARGB_8888); bmp.eraseColor(color)
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }; bmp.recycle()
                PhotoItem(i+1L,f.name,Uri.fromFile(f),160,240,null)
            }
            ui { GallerySession.setPhotos(photos,0) }
            val viewer = launch(PhotoActivity::class.java)
            val photo = ui { views(viewer.window.decorView).filterIsInstance<ZoomImageView>().single() }
            await("no initial image") { photo.width > 0 && photo.drawable != null }
            for ((step,target) in listOf(1 to 1,-1 to 0)) {
                val frames = mutableListOf<Float>()
                val width = ui { photo.width.toFloat() }; val distance = .34f * width
                val observer = android.view.ViewTreeObserver.OnPreDrawListener { frames.add(abs(photo.translationX)); true }
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
                }
                SystemClock.sleep(350) // Distinguish the second intentional swipe from a double tap.
            }
            ui { viewer.finish(); main.finish() }
            "PASS: refresh start + unchanged completion on both tabs; left/right post-release frames and page handoff"
        }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream",result.getOrElse { it.stackTraceToString() })
        })
    }
}
