package com.meteorsss.hdrphoto

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.min

class ZoomImageView(context: Context) : ImageView(context) {
    private val photoMatrix = Matrix()
    private val values = FloatArray(9)
    private var minScale = 1f
    private var maxScale = 8f
    private var currentScale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (currentScale * detector.scaleFactor).coerceIn(minScale, maxScale)
                val factor = target / currentScale
                photoMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                currentScale = target
                constrain()
                imageMatrix = photoMatrix
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (drawable == null) return true
                if (currentScale > minScale * 1.4f) {
                    resetToFit()
                } else {
                    val target = minScale * 2.6f
                    val factor = target / currentScale
                    photoMatrix.postScale(factor, factor, event.x, event.y)
                    currentScale = target
                    constrain()
                    imageMatrix = photoMatrix
                }
                return true
            }
        },
    )

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { resetToFit() }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        post { resetToFit() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && !scaleDetector.isInProgress) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    photoMatrix.postTranslate(dx, dy)
                    constrain()
                    imageMatrix = photoMatrix
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            MotionEvent.ACTION_POINTER_UP -> {
                lastX = event.x
                lastY = event.y
            }
        }
        return true
    }

    fun resetToFit() {
        val drawable = drawable ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        val drawableWidth = drawable.intrinsicWidth.toFloat()
        val drawableHeight = drawable.intrinsicHeight.toFloat()
        if (drawableWidth <= 0f || drawableHeight <= 0f) return

        val scale = min(viewWidth / drawableWidth, viewHeight / drawableHeight)
        val dx = (viewWidth - drawableWidth * scale) * 0.5f
        val dy = (viewHeight - drawableHeight * scale) * 0.5f

        photoMatrix.reset()
        photoMatrix.postScale(scale, scale)
        photoMatrix.postTranslate(dx, dy)
        minScale = scale
        maxScale = scale * 8f
        currentScale = scale
        imageMatrix = photoMatrix
    }

    private fun constrain() {
        val drawable = drawable ?: return
        val rect = RectF(
            0f,
            0f,
            drawable.intrinsicWidth.toFloat(),
            drawable.intrinsicHeight.toFloat(),
        )
        photoMatrix.mapRect(rect)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        var dx = 0f
        var dy = 0f

        dx = when {
            rect.width() <= viewWidth -> (viewWidth - rect.width()) * 0.5f - rect.left
            rect.left > 0f -> -rect.left
            rect.right < viewWidth -> viewWidth - rect.right
            else -> 0f
        }

        dy = when {
            rect.height() <= viewHeight -> (viewHeight - rect.height()) * 0.5f - rect.top
            rect.top > 0f -> -rect.top
            rect.bottom < viewHeight -> viewHeight - rect.bottom
            else -> 0f
        }

        photoMatrix.postTranslate(dx, dy)
        photoMatrix.getValues(values)
        currentScale = values[Matrix.MSCALE_X]
    }
}
