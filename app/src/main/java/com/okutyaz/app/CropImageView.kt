package com.okutyaz.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** OCR ile bulunan, dokunarak seçilebilen metin kutusu (koordinatlar fotoğraf pikselidir). */
class OcrBox(val rect: RectF, val text: String) {
    var selected = false
}

/** Fotoğraf üzerinde taşınabilir ve boyutlandırılabilir seçim çerçevesi gösteren görünüm. */
class CropImageView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private val imageRect = RectF()
    private val crop = RectF()
    private val density = resources.displayMetrics.density
    private val slop = 30f * density
    private val minSize = 56f * density

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint().apply { color = 0x99000000.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF; style = Paint.Style.STROKE; strokeWidth = 1f * density
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(37, 99, 235); style = Paint.Style.STROKE; strokeWidth = 2.5f * density
    }
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF94A3B8.toInt(); textAlign = Paint.Align.CENTER; textSize = 15f * density
    }

    private var touchMode = 0 // 0 yok, 1 taşı, 2 boyutlandır, 3 yeni seçim
    private var edgeL = false
    private var edgeT = false
    private var edgeR = false
    private var edgeB = false
    private var lastX = 0f
    private var lastY = 0f
    private var anchorX = 0f
    private var anchorY = 0f

    // Görünüm modu: 0 satır seç, 1 kelime seç, 2 alan çiz
    private var viewMode = 0
    private var lineBoxes: List<OcrBox> = emptyList()
    private var wordBoxes: List<OcrBox> = emptyList()
    private var downX = 0f
    private var downY = 0f
    var onSelectionChanged: ((String) -> Unit)? = null
    private val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(59, 130, 246); style = Paint.Style.STROKE; strokeWidth = 1.5f * density
    }
    private val boxFill = Paint().apply { color = 0x333B82F6 }
    private val selFill = Paint().apply { color = 0x99FBBF24.toInt() }
    private val selStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(251, 191, 36); style = Paint.Style.STROKE; strokeWidth = 2.5f * density
    }

    private fun clamp(v: Float, lo: Float, hi: Float): Float = if (hi < lo) lo else min(max(v, lo), hi)

    fun hasImage(): Boolean = bitmap != null

    fun setBitmap(value: Bitmap) {
        bitmap = value
        lineBoxes = emptyList()
        wordBoxes = emptyList()
        layoutImage()
        resetSelection()
        invalidate()
    }

    private fun layoutImage() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        val pad = 12f * density
        val availW = width - 2 * pad
        val availH = height - 2 * pad
        if (availW <= 0f || availH <= 0f) return
        val scale = min(availW / b.width, availH / b.height)
        val w = b.width * scale
        val h = b.height * scale
        imageRect.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    private fun resetSelection() {
        if (bitmap == null) return
        val dx = imageRect.width() * 0.08f
        val dy = imageRect.height() * 0.08f
        crop.set(imageRect.left + dx, imageRect.top + dy, imageRect.right - dx, imageRect.bottom - dy)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (bitmap == null) return
        val oldImage = RectF(imageRect)
        val oldCrop = RectF(crop)
        layoutImage()
        if (oldImage.width() > 0f && oldImage.height() > 0f) {
            crop.left = imageRect.left + (oldCrop.left - oldImage.left) / oldImage.width() * imageRect.width()
            crop.right = imageRect.left + (oldCrop.right - oldImage.left) / oldImage.width() * imageRect.width()
            crop.top = imageRect.top + (oldCrop.top - oldImage.top) / oldImage.height() * imageRect.height()
            crop.bottom = imageRect.top + (oldCrop.bottom - oldImage.top) / oldImage.height() * imageRect.height()
        } else {
            resetSelection()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bitmap
        if (b == null) {
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawText("Fotoğraf yok", cx, cy - 4f * density, placeholder)
            canvas.drawText("Kamera veya Galeri ile başla", cx, cy + 20f * density, placeholder)
            return
        }
        canvas.drawBitmap(b, null, imageRect, bitmapPaint)
        if (viewMode < 2) {
            val s = imageRect.width() / b.width
            val rr = 4f * density
            for (box in activeBoxes()) {
                val r = RectF(
                    imageRect.left + box.rect.left * s, imageRect.top + box.rect.top * s,
                    imageRect.left + box.rect.right * s, imageRect.top + box.rect.bottom * s
                )
                r.inset(-2f * density, -1f * density)
                if (box.selected) {
                    canvas.drawRoundRect(r, rr, rr, selFill)
                    canvas.drawRoundRect(r, rr, rr, selStroke)
                } else {
                    canvas.drawRoundRect(r, rr, rr, boxFill)
                    canvas.drawRoundRect(r, rr, rr, boxStroke)
                }
            }
            return
        }
        // Seçim dışındaki alanı karart
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, crop.top, shade)
        canvas.drawRect(imageRect.left, crop.bottom, imageRect.right, imageRect.bottom, shade)
        canvas.drawRect(imageRect.left, crop.top, crop.left, crop.bottom, shade)
        canvas.drawRect(crop.right, crop.top, imageRect.right, crop.bottom, shade)
        // Kılavuz çizgileri
        val tw = crop.width() / 3f
        val th = crop.height() / 3f
        canvas.drawLine(crop.left + tw, crop.top, crop.left + tw, crop.bottom, grid)
        canvas.drawLine(crop.left + 2f * tw, crop.top, crop.left + 2f * tw, crop.bottom, grid)
        canvas.drawLine(crop.left, crop.top + th, crop.right, crop.top + th, grid)
        canvas.drawLine(crop.left, crop.top + 2f * th, crop.right, crop.top + 2f * th, grid)
        canvas.drawRect(crop, border)
        // Köşe ve kenar tutamaçları
        val r = 9f * density
        val rs = 5f * density
        drawHandle(canvas, crop.left, crop.top, r)
        drawHandle(canvas, crop.right, crop.top, r)
        drawHandle(canvas, crop.left, crop.bottom, r)
        drawHandle(canvas, crop.right, crop.bottom, r)
        drawHandle(canvas, crop.centerX(), crop.top, rs)
        drawHandle(canvas, crop.centerX(), crop.bottom, rs)
        drawHandle(canvas, crop.left, crop.centerY(), rs)
        drawHandle(canvas, crop.right, crop.centerY(), rs)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float, radius: Float) {
        canvas.drawCircle(x, y, radius, handleFill)
        canvas.drawCircle(x, y, radius, handleStroke)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null || imageRect.width() <= 0f) return false
        if (viewMode < 2) return handleTap(e)
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val inY = y > crop.top - slop && y < crop.bottom + slop
                val inX = x > crop.left - slop && x < crop.right + slop
                edgeL = abs(x - crop.left) < slop && inY
                edgeR = abs(x - crop.right) < slop && inY
                edgeT = abs(y - crop.top) < slop && inX
                edgeB = abs(y - crop.bottom) < slop && inX
                if (edgeL && edgeR) { if (abs(x - crop.left) < abs(x - crop.right)) edgeR = false else edgeL = false }
                if (edgeT && edgeB) { if (abs(y - crop.top) < abs(y - crop.bottom)) edgeB = false else edgeT = false }
                if (edgeL || edgeR || edgeT || edgeB) {
                    touchMode = 2
                } else if (crop.contains(x, y)) {
                    touchMode = 1
                } else {
                    anchorX = clamp(x, imageRect.left, imageRect.right)
                    anchorY = clamp(y, imageRect.top, imageRect.bottom)
                    crop.set(anchorX, anchorY, anchorX, anchorY)
                    touchMode = 3
                }
                lastX = x
                lastY = y
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (touchMode) {
                    1 -> {
                        val dx = clamp(x - lastX, imageRect.left - crop.left, imageRect.right - crop.right)
                        val dy = clamp(y - lastY, imageRect.top - crop.top, imageRect.bottom - crop.bottom)
                        crop.offset(dx, dy)
                    }
                    2 -> {
                        if (edgeL) crop.left = clamp(x, imageRect.left, crop.right - minSize)
                        if (edgeR) crop.right = clamp(x, crop.left + minSize, imageRect.right)
                        if (edgeT) crop.top = clamp(y, imageRect.top, crop.bottom - minSize)
                        if (edgeB) crop.bottom = clamp(y, crop.top + minSize, imageRect.bottom)
                    }
                    3 -> {
                        val cx = clamp(x, imageRect.left, imageRect.right)
                        val cy = clamp(y, imageRect.top, imageRect.bottom)
                        crop.set(min(anchorX, cx), min(anchorY, cy), max(anchorX, cx), max(anchorY, cy))
                    }
                }
                lastX = x
                lastY = y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (touchMode == 3) enforceMin()
                touchMode = 0
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return true
    }

    fun setBoxes(lines: List<OcrBox>, words: List<OcrBox>) {
        lineBoxes = lines
        wordBoxes = words
        invalidate()
    }

    fun boxCount(): Int = lineBoxes.size

    fun clearSelection() {
        for (b in lineBoxes) b.selected = false
        for (b in wordBoxes) b.selected = false
        invalidate()
    }

    fun setViewMode(m: Int) {
        viewMode = m
        for (b in lineBoxes) b.selected = false
        for (b in wordBoxes) b.selected = false
        invalidate()
    }

    private fun activeBoxes(): List<OcrBox> = if (viewMode == 0) lineBoxes else wordBoxes

    private fun handleTap(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (abs(e.x - downX) < slop / 2f && abs(e.y - downY) < slop / 2f) toggleAt(e.x, e.y)
            }
        }
        return true
    }

    private fun toggleAt(x: Float, y: Float) {
        val b = bitmap ?: return
        val s = imageRect.width() / b.width
        if (s <= 0f) return
        val bx = (x - imageRect.left) / s
        val by = (y - imageRect.top) / s
        val pad = 6f * density / s
        var best: OcrBox? = null
        var bestArea = Float.MAX_VALUE
        for (box in activeBoxes()) {
            val r = box.rect
            if (bx >= r.left - pad && bx <= r.right + pad && by >= r.top - pad && by <= r.bottom + pad) {
                val area = r.width() * r.height()
                if (area < bestArea) {
                    best = box
                    bestArea = area
                }
            }
        }
        val hit = best ?: return
        hit.selected = !hit.selected
        invalidate()
        onSelectionChanged?.invoke(selectedText())
    }

    /** Seçili kutuların metnini okuma sırasına göre (satır satır, soldan sağa) birleştirir. */
    private fun selectedText(): String {
        val sel = activeBoxes().filter { it.selected }
        if (sel.isEmpty()) return ""
        val sorted = sel.sortedBy { it.rect.centerY() }
        val rows = ArrayList<ArrayList<OcrBox>>()
        var refY = 0f
        var refH = 0f
        for (box in sorted) {
            if (rows.isEmpty() || abs(box.rect.centerY() - refY) > refH * 0.6f) {
                rows.add(arrayListOf(box))
                refY = box.rect.centerY()
                refH = box.rect.height()
            } else {
                rows[rows.size - 1].add(box)
            }
        }
        return rows.joinToString(" ") { row -> row.sortedBy { it.rect.left }.joinToString(" ") { it.text } }
    }

    private fun enforceMin() {
        if (crop.width() < minSize) {
            val l = clamp(crop.centerX() - minSize / 2f, imageRect.left, imageRect.right - minSize)
            crop.left = l
            crop.right = l + minSize
        }
        if (crop.height() < minSize) {
            val t = clamp(crop.centerY() - minSize / 2f, imageRect.top, imageRect.bottom - minSize)
            crop.top = t
            crop.bottom = t + minSize
        }
    }

    /** Seçili alanı orijinal fotoğraf çözünürlüğünde keser. */
    fun crop(): Bitmap? {
        val b = bitmap ?: return null
        if (imageRect.width() <= 0f || imageRect.height() <= 0f) return b
        val sx = b.width / imageRect.width()
        val sy = b.height / imageRect.height()
        val l = ((crop.left - imageRect.left) * sx).toInt().coerceIn(0, b.width - 1)
        val t = ((crop.top - imageRect.top) * sy).toInt().coerceIn(0, b.height - 1)
        val rr = ((crop.right - imageRect.left) * sx).toInt().coerceIn(l + 1, b.width)
        val bb = ((crop.bottom - imageRect.top) * sy).toInt().coerceIn(t + 1, b.height)
        return Bitmap.createBitmap(b, l, t, rr - l, bb - t)
    }

    override fun performClick(): Boolean = super.performClick()
}
