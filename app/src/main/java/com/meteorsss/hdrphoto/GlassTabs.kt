package com.meteorsss.hdrphoto

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.*
import android.widget.*
import kotlin.math.abs

/** Share the hardware display list, not screenshots. The bar is a sibling, so it never samples itself. */
internal class GlassSource(context: Context) : LinearLayout(context) {
    val scene = RenderNode("gallery content")
    var onRecorded: (() -> Unit)? = null
    override fun dispatchDraw(canvas: Canvas) {
        if (!canvas.isHardwareAccelerated || width <= 0 || height <= 0) { super.dispatchDraw(canvas); return }
        scene.setPosition(0, 0, width, height)
        val recording = scene.beginRecording()
        try { super.dispatchDraw(recording) } finally { scene.endRecording() }
        canvas.drawRenderNode(scene)
        onRecorded?.invoke()
    }
    override fun onDetachedFromWindow() { scene.discardDisplayList(); super.onDetachedFromWindow() }
}

internal class GlassTabs(context: Context, private val source: GlassSource, private val dark: Boolean,
                         private val choose: (Boolean) -> Unit) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val backdrop = RenderNode("blurred gallery strip")
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = RectF()
    private var fraction = 0f
    private var selected = false
    private var dragging = false
    private var down = 0f
    private var downY = 0f
    private var tilt = 0f
    private var animation: ValueAnimator? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val inset = density * 5
    private val spread = (density * 22).toInt()
    init {
        setWillNotDraw(false); orientation = HORIZONTAL; gravity = Gravity.CENTER
        setPadding(inset.toInt(), 0, inset.toInt(), 0)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, width, height, height / 2f) }
        }
        clipToOutline = true; elevation = density * 9
        backdrop.setRenderEffect(RenderEffect.createBlurEffect(density * 9, density * 9, Shader.TileMode.CLAMP))
        source.onRecorded = { invalidate() }
    }
    fun installLabels(a: View, b: View) { listOf(a, b).forEach { addView(it, LayoutParams(0, -1, 1f)) } }
    fun select(albums: Boolean) {
        selected = albums; val target = if (albums) 1f else 0f
        animation?.cancel(); val startTilt = tilt
        animation = ValueAnimator.ofFloat(fraction, target).apply {
            duration = 185; addUpdateListener { fraction = it.animatedValue as Float; tilt = startTilt * (1f-it.animatedFraction); invalidate() }; start()
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (canvas.isHardwareAccelerated && source.scene.hasDisplayList()) {
            backdrop.setPosition(-spread, -spread, width + spread, height + spread)
            val c = backdrop.beginRecording()
            try {
                c.translate((source.left-left+spread).toFloat(), (source.top-top+spread).toFloat())
                c.drawRenderNode(source.scene)
            } finally { backdrop.endRecording() }
            canvas.drawRenderNode(backdrop)
        }
        brush.style = Paint.Style.FILL
        brush.color = if (dark) 0xA4232B39.toInt() else 0xA8EAF3FF.toInt()
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height/2f, height/2f, brush)
        val half = (width - 2*inset)/2; val x = inset + half*fraction
        rim.set(x, inset, x+half, height-inset)
        brush.color = Color.WHITE
        brush.shader = LinearGradient(x+half*(.25f+tilt*.15f), inset, x+half*.74f, height-inset,
            if (dark) intArrayOf(0x908BAACD.toInt(),0x50273D5F,0x807C95AB.toInt()) else intArrayOf(0xDFFFFFFF.toInt(),0x707EA7D2,0xBBF7FDFF.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rim, height/2f, height/2f, brush)
        brush.shader = null; brush.style = Paint.Style.STROKE; brush.strokeWidth = density
        brush.color = if (dark) 0x8AD8ECFF.toInt() else 0xD0FFFFFF.toInt()
        canvas.drawRoundRect(rim, height/2f, height/2f, brush)
        rim.set(density, density, width-density, height-density); canvas.drawRoundRect(rim,height/2f,height/2f,brush)
    }
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = e.x; downY = e.y; dragging = false }
            MotionEvent.ACTION_MOVE -> if (abs(e.x-down)>slop && abs(e.x-down)>abs(e.y-downY)) { dragging = true; animation?.cancel(); return true }
        }
        return false
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (dragging) {
                fraction = ((e.x-width*.25f)/(width*.5f)).coerceIn(0f,1f)
                tilt = ((e.x-down)/(width*.28f)).coerceIn(-1f,1f); invalidate()
            }
            MotionEvent.ACTION_UP -> if (dragging) { dragging = false; choose(e.x >= width/2f) } else performClick()
            MotionEvent.ACTION_CANCEL -> { dragging = false; select(selected) }
        }
        return true
    }
    override fun performClick(): Boolean = super.performClick()
    override fun onDetachedFromWindow() { animation?.cancel(); backdrop.discardDisplayList(); super.onDetachedFromWindow() }
}

/** Content swipes use pager direction; dragging the tab pill selects the label under the finger. */
internal class TabSwipeList(context: Context, private val choose: (Boolean) -> Unit) : ListView(context) {
    private var x0 = 0f; private var y0 = 0f; private var axis = 0
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        val dx = e.x-x0; val dy = e.y-y0
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0=e.x; y0=e.y; axis=0 }
            MotionEvent.ACTION_MOVE -> if (axis==0 && maxOf(abs(dx),abs(dy))>slop) {
                axis = if (abs(dx)>1.5f*abs(dy)) 1 else 2
                if(axis==1) { val cancel=MotionEvent.obtain(e); cancel.action=MotionEvent.ACTION_CANCEL; super.dispatchTouchEvent(cancel); cancel.recycle() }
            }
            MotionEvent.ACTION_UP -> if(axis==1 && abs(dx)>slop*3) choose(dx<0)
        }
        return if(axis==1) true else super.dispatchTouchEvent(e)
    }
}
