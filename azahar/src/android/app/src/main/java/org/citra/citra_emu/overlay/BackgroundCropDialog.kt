// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.overlay

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Region
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.max
import kotlin.math.min
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R

/** Full screen dialog to move/zoom an image inside a frame with the game's aspect ratio. */
object BackgroundCropDialog {
    fun show(
        activity: AppCompatActivity,
        bitmap: Bitmap,
        portrait: Boolean,
        onCropped: (Bitmap) -> Unit
    ) {
        val metrics = activity.resources.displayMetrics
        val long = max(metrics.widthPixels, metrics.heightPixels).toFloat()
        val short = min(metrics.widthPixels, metrics.heightPixels).toFloat()
        val aspect = if (portrait) short / long else long / short

        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val cropView = CropView(activity, bitmap, aspect, portrait)

        val hint = TextView(activity).apply {
            setText(R.string.emulation_background_crop_hint)
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(0x99000000.toInt())
        }

        fun bar(): Button = Button(activity).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
        }

        val cancel = bar().apply {
            setText(android.R.string.cancel)
            setOnClickListener { dialog.dismiss() }
        }
        val preview = bar().apply {
            setText(R.string.emulation_background_hide_screens)
            // Nothing to show if the layout couldn't be read
            visibility = if (cropView.realScreens.isEmpty()) View.INVISIBLE else View.VISIBLE
            setOnClickListener {
                cropView.showScreens = !cropView.showScreens
                setText(
                    if (cropView.showScreens) R.string.emulation_background_hide_screens
                    else R.string.emulation_background_show_screens
                )
            }
        }
        val done = bar().apply {
            setText(R.string.emulation_background_crop_done)
            setOnClickListener {
                val cropped = cropView.crop()
                dialog.dismiss()
                onCropped(cropped)
            }
        }

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC000000.toInt())
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(cancel, lp)
            addView(preview, lp)
            addView(done, lp)
        }

        val root = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
            addView(
                cropView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                hint,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                )
            )
            addView(
                buttons,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
            )
        }

        dialog.setContentView(root)
        dialog.setCancelable(true)
        dialog.show()
    }

    private class CropView(
        context: Context,
        private val bitmap: Bitmap,
        private val aspect: Float,
        private val portrait: Boolean
    ) : View(context) {
        var showScreens = true
            set(value) {
                field = value
                invalidate()
            }

        private val imgMatrix = Matrix()
        private val frame = RectF()
        private val rect = RectF()
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 2f * resources.displayMetrics.density
        }
        private val screenFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC202020.toInt() }
        private val screenStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFCCCCCC.toInt()
            strokeWidth = 1.5f * resources.displayMetrics.density
        }
        /** The screens as the game will place them with the current layout (0..1 of the surface). */
        val realScreens: List<RectF> = try {
            val metrics = context.resources.displayMetrics
            val long = max(metrics.widthPixels, metrics.heightPixels)
            val short = min(metrics.widthPixels, metrics.heightPixels)
            val l = NativeLibrary.getLayoutPreview(
                if (portrait) short else long,
                if (portrait) long else short,
                portrait
            )
            val w = if (l.size >= 2) l[0] else 0f
            val h = if (l.size >= 2) l[1] else 0f
            val list = ArrayList<RectF>()
            if (w > 0f && h > 0f) {
                var i = 2
                while (i + 3 < l.size) {
                    list.add(RectF(l[i] / w, l[i + 1] / h, l[i + 2] / w, l[i + 3] / h))
                    i += 4
                }
            }
            list
        } catch (e: Throwable) {
            emptyList()
        }

        private var minScale = 1f
        private var initialized = false
        private var lastX = 0f
        private var lastY = 0f

        private val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val current = currentScale()
                    val target = (current * detector.scaleFactor).coerceIn(minScale, minScale * 6f)
                    val factor = target / current
                    imgMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                    clampToFrame()
                    invalidate()
                    return true
                }
            }
        )

        private fun currentScale(): Float {
            val v = FloatArray(9)
            imgMatrix.getValues(v)
            return v[Matrix.MSCALE_X]
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val margin = 24f * resources.displayMetrics.density
            val top = 56f * resources.displayMetrics.density // room for the hint
            val bottom = 56f * resources.displayMetrics.density // room for the buttons
            val availW = w - margin * 2
            val availH = h - top - bottom - margin
            var fw = availW
            var fh = fw / aspect
            if (fh > availH) {
                fh = availH
                fw = fh * aspect
            }
            val cx = w / 2f
            val cy = top + (h - top - bottom) / 2f
            frame.set(cx - fw / 2f, cy - fh / 2f, cx + fw / 2f, cy + fh / 2f)

            minScale = max(frame.width() / bitmap.width, frame.height() / bitmap.height)
            if (!initialized) {
                initialized = true
                imgMatrix.setScale(minScale, minScale)
                imgMatrix.postTranslate(
                    frame.centerX() - bitmap.width * minScale / 2f,
                    frame.centerY() - bitmap.height * minScale / 2f
                )
            }
            clampToFrame()
        }

        private fun clampToFrame() {
            rect.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            imgMatrix.mapRect(rect)
            var dx = 0f
            var dy = 0f
            if (rect.left > frame.left) dx = frame.left - rect.left
            else if (rect.right < frame.right) dx = frame.right - rect.right
            if (rect.top > frame.top) dy = frame.top - rect.top
            else if (rect.bottom < frame.bottom) dy = frame.bottom - rect.bottom
            imgMatrix.postTranslate(dx, dy)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    lastX = event.getX(0)
                    lastY = event.getY(0)
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                        imgMatrix.postTranslate(event.x - lastX, event.y - lastY)
                        clampToFrame()
                        invalidate()
                    }
                    lastX = event.getX(0)
                    lastY = event.getY(0)
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap, imgMatrix, bitmapPaint)

            // Dim everything outside the frame
            canvas.save()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                canvas.clipOutRect(frame)
            } else {
                @Suppress("DEPRECATION")
                canvas.clipRect(frame, Region.Op.DIFFERENCE)
            }
            canvas.drawColor(0xB0000000.toInt())
            canvas.restore()

            if (showScreens) {
                canvas.save()
                canvas.clipRect(frame)
                drawScreenMockups(canvas)
                canvas.restore()
            }

            canvas.drawRect(frame, borderPaint)
        }

        private fun drawScreenMockups(canvas: Canvas) {
            for (n in realScreens) {
                val r = RectF(
                    frame.left + n.left * frame.width(),
                    frame.top + n.top * frame.height(),
                    frame.left + n.right * frame.width(),
                    frame.top + n.bottom * frame.height()
                )
                canvas.drawRect(r, screenFill)
                canvas.drawRect(r, screenStroke)
            }
        }

        /** Returns the part of the image currently inside the frame. */
        fun crop(): Bitmap {
            val inverse = Matrix()
            imgMatrix.invert(inverse)
            val r = RectF(frame)
            inverse.mapRect(r)
            val left = r.left.coerceIn(0f, bitmap.width - 1f).toInt()
            val topPx = r.top.coerceIn(0f, bitmap.height - 1f).toInt()
            val width = r.width().toInt().coerceIn(1, bitmap.width - left)
            val height = r.height().toInt().coerceIn(1, bitmap.height - topPx)
            return Bitmap.createBitmap(bitmap, left, topPx, width, height)
        }
    }
}
