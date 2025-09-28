package com.example.coupontracker.ui.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.DashPathEffect
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.coupontracker.R
import com.example.coupontracker.ml.CouponInstance
import com.example.coupontracker.ml.CouponStatus
import com.example.coupontracker.ml.FieldType
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Overlay view that renders detected coupons and supports interactive editing.
 */
class MultiCouponOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OverlayListener {
        fun onSelectionChanged(selectedIds: Set<String>)
        fun onCouponBoundingBoxChanged(couponId: String, newBoundingBox: RectF)
        fun onAddCouponRequested(boundingBox: RectF)
        fun onDeleteRequested(selectedIds: Set<String>)
    }

    private data class OverlayEntry(
        var instance: CouponInstance,
        val rect: RectF
    )

    private enum class Edge {
        LEFT, TOP, RIGHT, BOTTOM
    }

    private enum class EditMode {
        NONE,
        MOVE,
        RESIZE
    }

    private var overlayListener: OverlayListener? = null

    private val overlayEntries = mutableListOf<OverlayEntry>()
    private val selectedIds = linkedSetOf<String>()

    private var bitmap: Bitmap? = null
    private val drawMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val viewRect = RectF()
    private val imageRect = RectF()

    private val boundingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = ContextCompat.getColor(context, R.color.primary)
    }
    private val selectionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(60, 33, 150, 243)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
    }
    private val fieldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val addTilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = ContextCompat.getColor(context, R.color.secondary)
        pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
    }

    private val gestureDetector = GestureDetector(context, OverlayGestureListener())

    private var editMode: EditMode = EditMode.NONE
    private var editingEntry: OverlayEntry? = null
    private var activeEdges: EnumSet<Edge>? = null
    private var lastBitmapPoint: PointF? = null

    private var addTileMode = false
    private var tempTileRect: RectF? = null

    fun setOverlayListener(listener: OverlayListener) {
        overlayListener = listener
    }

    fun setImageBitmap(bitmap: Bitmap?) {
        this.bitmap = bitmap
        updateMatrix()
        invalidate()
    }

    fun setCouponInstances(instances: List<CouponInstance>) {
        overlayEntries.clear()
        instances.forEach { instance ->
            overlayEntries.add(OverlayEntry(instance, RectF(instance.boundingBox)))
        }
        selectedIds.retainAll(instances.map { it.id }.toSet())
        invalidate()
    }

    fun addCouponInstance(instance: CouponInstance) {
        overlayEntries.add(OverlayEntry(instance, RectF(instance.boundingBox)))
        selectedIds.add(instance.id)
        overlayListener?.onSelectionChanged(selectedIds)
        invalidate()
    }

    fun updateCouponInstance(instance: CouponInstance) {
        val entry = overlayEntries.firstOrNull { it.instance.id == instance.id }
        if (entry != null) {
            entry.instance = instance
            entry.rect.set(instance.boundingBox)
            invalidate()
        }
    }

    fun removeCoupons(ids: Set<String>) {
        overlayEntries.removeAll { ids.contains(it.instance.id) }
        selectedIds.removeAll(ids)
        overlayListener?.onSelectionChanged(selectedIds)
        invalidate()
    }

    fun setSelectedCouponIds(ids: Set<String>) {
        selectedIds.clear()
        selectedIds.addAll(ids)
        invalidate()
    }

    fun selectAll() {
        selectedIds.clear()
        selectedIds.addAll(overlayEntries.map { it.instance.id })
        overlayListener?.onSelectionChanged(selectedIds)
        invalidate()
    }

    fun clearSelection() {
        selectedIds.clear()
        overlayListener?.onSelectionChanged(selectedIds)
        invalidate()
    }

    fun toggleAddTileMode(): Boolean {
        addTileMode = !addTileMode
        tempTileRect = null
        invalidate()
        return addTileMode
    }

    fun exitAddTileMode() {
        addTileMode = false
        tempTileRect = null
        invalidate()
    }

    fun deleteSelected() {
        if (selectedIds.isEmpty()) return
        overlayListener?.onDeleteRequested(selectedIds.toSet())
    }

    fun isInAddTileMode(): Boolean = addTileMode

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let { bmp ->
            canvas.drawBitmap(bmp, drawMatrix, null)
        }

        overlayEntries.forEachIndexed { index, entry ->
            val viewRect = RectF(entry.rect)
            drawMatrix.mapRect(viewRect)

            boundingPaint.color = when (entry.instance.status) {
                CouponStatus.COMPLETE -> ContextCompat.getColor(context, android.R.color.holo_green_light)
                CouponStatus.PARTIAL_TOP -> ContextCompat.getColor(context, android.R.color.holo_orange_light)
                CouponStatus.PARTIAL_BOTTOM -> ContextCompat.getColor(context, android.R.color.holo_red_light)
            }

            canvas.drawRect(viewRect, boundingPaint)

            if (selectedIds.contains(entry.instance.id)) {
                canvas.drawRect(viewRect, selectionFillPaint)
                canvas.drawRect(viewRect, selectedPaint)
            }

            canvas.drawText((index + 1).toString(), viewRect.left + 12f, viewRect.top + 42f, textPaint)

            entry.instance.fields.forEach { field ->
                fieldPaint.color = when (field.fieldType) {
                    FieldType.CODE_REGION -> ContextCompat.getColor(context, R.color.field_code)
                    FieldType.BENEFIT_REGION -> ContextCompat.getColor(context, R.color.field_benefit)
                    FieldType.EXPIRY_REGION -> ContextCompat.getColor(context, R.color.field_expiry)
                    FieldType.APP_REGION -> ContextCompat.getColor(context, R.color.field_app)
                    FieldType.TERMS_REGION -> ContextCompat.getColor(context, R.color.field_terms)
                }
                val fieldRect = RectF(field.boundingBox)
                drawMatrix.mapRect(fieldRect)
                canvas.drawRect(fieldRect, fieldPaint)
            }
        }

        tempTileRect?.let { rect ->
            val mapped = RectF(rect)
            drawMatrix.mapRect(mapped)
            canvas.drawRect(mapped, addTilePaint)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewRect.set(paddingLeft.toFloat(), paddingTop.toFloat(), (w - paddingRight).toFloat(), (h - paddingBottom).toFloat())
        updateMatrix()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false

        val handledByGesture = gestureDetector.onTouchEvent(event)
        val withinImage = imageRect.contains(event.x, event.y)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (addTileMode && withinImage) {
                    val start = toBitmapPoint(event.x, event.y)
                    tempTileRect = RectF(start.x, start.y, start.x, start.y)
                    parent.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (addTileMode) {
                    tempTileRect?.let { rect ->
                        val point = toBitmapPoint(event.x, event.y)
                        rect.right = point.x
                        rect.bottom = point.y
                        normalizeRect(rect)
                        invalidate()
                    }
                    return true
                }

                if (editMode != EditMode.NONE && editingEntry != null) {
                    val point = toBitmapPoint(event.x, event.y)
                    handleDrag(point)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (addTileMode) {
                    val rect = tempTileRect
                    tempTileRect = null
                    invalidate()
                    if (event.actionMasked == MotionEvent.ACTION_UP && rect != null && rect.width() > MIN_TILE_SIZE && rect.height() > MIN_TILE_SIZE) {
                        overlayListener?.onAddCouponRequested(RectF(rect))
                    }
                    return true
                }

                if (editMode != EditMode.NONE && editingEntry != null) {
                    val rect = RectF(editingEntry!!.rect)
                    overlayListener?.onCouponBoundingBoxChanged(editingEntry!!.instance.id, rect)
                }
                resetEditState()
            }
        }

        return handledByGesture || addTileMode || editMode != EditMode.NONE
    }

    private fun handleDrag(point: PointF) {
        val entry = editingEntry ?: return
        val rect = entry.rect
        val bitmap = bitmap ?: return
        val lastPoint = lastBitmapPoint ?: point

        when (editMode) {
            EditMode.MOVE -> {
                val dx = point.x - lastPoint.x
                val dy = point.y - lastPoint.y
                rect.offset(dx, dy)
                clampRect(rect, bitmap)
            }
            EditMode.RESIZE -> {
                val edges = activeEdges ?: EnumSet.noneOf(Edge::class.java)
                if (edges.contains(Edge.LEFT)) {
                    rect.left = point.x
                }
                if (edges.contains(Edge.RIGHT)) {
                    rect.right = point.x
                }
                if (edges.contains(Edge.TOP)) {
                    rect.top = point.y
                }
                if (edges.contains(Edge.BOTTOM)) {
                    rect.bottom = point.y
                }
                normalizeRect(rect)
                clampRect(rect, bitmap)
                ensureMinimumSize(rect)
            }
            else -> Unit
        }

        lastBitmapPoint = point
    }

    private fun updateMatrix() {
        val bmp = bitmap ?: return
        val contentWidth = viewRect.width()
        val contentHeight = viewRect.height()
        if (contentWidth <= 0f || contentHeight <= 0f) return

        val scale = min(contentWidth / bmp.width, contentHeight / bmp.height)
        val scaledWidth = bmp.width * scale
        val scaledHeight = bmp.height * scale
        val dx = viewRect.left + (contentWidth - scaledWidth) / 2f
        val dy = viewRect.top + (contentHeight - scaledHeight) / 2f

        drawMatrix.reset()
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(dx, dy)

        imageRect.set(dx, dy, dx + scaledWidth, dy + scaledHeight)
        drawMatrix.invert(inverseMatrix)
    }

    private fun toBitmapPoint(x: Float, y: Float): PointF {
        val pts = floatArrayOf(x, y)
        inverseMatrix.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    private fun resetEditState() {
        editMode = EditMode.NONE
        editingEntry = null
        activeEdges = null
        lastBitmapPoint = null
    }

    private fun findEntryAt(point: PointF): OverlayEntry? {
        return overlayEntries.firstOrNull { it.rect.contains(point.x, point.y) }
    }

    private fun selectEntry(entry: OverlayEntry, toggle: Boolean = true) {
        if (toggle) {
            if (!selectedIds.add(entry.instance.id)) {
                selectedIds.remove(entry.instance.id)
            }
        } else {
            selectedIds.clear()
            selectedIds.add(entry.instance.id)
        }
        overlayListener?.onSelectionChanged(selectedIds)
        invalidate()
    }

    private fun clampRect(rect: RectF, bitmap: Bitmap) {
        rect.left = rect.left.coerceIn(0f, bitmap.width.toFloat())
        rect.top = rect.top.coerceIn(0f, bitmap.height.toFloat())
        rect.right = rect.right.coerceIn(0f, bitmap.width.toFloat())
        rect.bottom = rect.bottom.coerceIn(0f, bitmap.height.toFloat())
        ensureMinimumSize(rect)
    }

    private fun ensureMinimumSize(rect: RectF) {
        if (rect.width() < MIN_TILE_SIZE) {
            rect.right = rect.left + MIN_TILE_SIZE
        }
        if (rect.height() < MIN_TILE_SIZE) {
            rect.bottom = rect.top + MIN_TILE_SIZE
        }
    }

    private fun normalizeRect(rect: RectF) {
        val left = min(rect.left, rect.right)
        val top = min(rect.top, rect.bottom)
        val right = max(rect.left, rect.right)
        val bottom = max(rect.top, rect.bottom)
        rect.set(left, top, right, bottom)
    }

    private fun determineEditMode(entry: OverlayEntry, point: PointF): EditMode {
        val rect = entry.rect
        val threshold = EDGE_SLOP
        val edges = EnumSet.noneOf(Edge::class.java)

        if (abs(point.x - rect.left) <= threshold) {
            edges.add(Edge.LEFT)
        }
        if (abs(point.x - rect.right) <= threshold) {
            edges.add(Edge.RIGHT)
        }
        if (abs(point.y - rect.top) <= threshold) {
            edges.add(Edge.TOP)
        }
        if (abs(point.y - rect.bottom) <= threshold) {
            edges.add(Edge.BOTTOM)
        }

        return if (edges.isEmpty()) {
            editMode = EditMode.MOVE
            activeEdges = null
            EditMode.MOVE
        } else {
            activeEdges = edges
            editMode = EditMode.RESIZE
            EditMode.RESIZE
        }
    }

    private inner class OverlayGestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (addTileMode) return true
            val point = toBitmapPoint(e.x, e.y)
            val entry = findEntryAt(point)
            if (entry != null) {
                selectEntry(entry)
                return true
            }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            if (addTileMode) return
            val point = toBitmapPoint(e.x, e.y)
            val entry = findEntryAt(point) ?: return
            selectEntry(entry, toggle = false)
            editingEntry = entry
            lastBitmapPoint = point
            determineEditMode(entry, point)
            parent.requestDisallowInterceptTouchEvent(true)
        }
    }

    companion object {
        private const val EDGE_SLOP = 24f
        private const val MIN_TILE_SIZE = 24f
    }
}
