package com.example.coupontracker.ui.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import java.util.EnumSet
import java.util.LinkedHashSet
import kotlin.math.max
import kotlin.math.min

/**
 * Custom view that renders detected coupon bounding boxes and allows manual adjustments.
 * The view keeps all rectangles within the bitmap bounds and enforces a minimum size
 * so users cannot shrink a coupon beyond a usable tap/drag target.
 */
class MultiCouponOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** The edges that are currently being resized by the user. */
    enum class ResizeEdge { LEFT, TOP, RIGHT, BOTTOM }

    interface OnCouponBoundsChangedListener {
        fun onCouponBoundsChanged(id: String, rect: RectF)
    }

    private val listeners = LinkedHashSet<OnCouponBoundsChangedListener>()
    private val couponBounds = mutableMapOf<String, RectF>()

    private val bitmapBounds = RectF()

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            2f,
            resources.displayMetrics,
        )
    }

    private val activeCouponPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.STROKE
        strokeWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            3f,
            resources.displayMetrics,
        )
    }

    private val minimumSelectionSizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        48f,
        resources.displayMetrics,
    )

    private var activeResizeEdges: EnumSet<ResizeEdge> = EnumSet.noneOf(ResizeEdge::class.java)
    private var activeCouponId: String? = null

    fun setBitmap(bitmap: Bitmap?) {
        if (bitmap == null) {
            bitmapBounds.setEmpty()
        } else {
            bitmapBounds.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        }
        invalidate()
    }

    fun addOnCouponBoundsChangedListener(listener: OnCouponBoundsChangedListener) {
        listeners.add(listener)
    }

    fun removeOnCouponBoundsChangedListener(listener: OnCouponBoundsChangedListener) {
        listeners.remove(listener)
    }

    fun setActiveCoupon(id: String?) {
        activeCouponId = id
        invalidate()
    }

    fun setActiveResizeEdges(edges: Set<ResizeEdge>) {
        activeResizeEdges = if (edges.isEmpty()) {
            EnumSet.noneOf(ResizeEdge::class.java)
        } else {
            EnumSet.copyOf(edges)
        }
    }

    fun getCouponBounds(id: String): RectF? = couponBounds[id]?.let { RectF(it) }

    fun setCouponBounds(id: String, rect: RectF) {
        handleCouponBoundingBoxChanged(id, rect)
    }

    fun getAllCouponBounds(): Map<String, RectF> =
        couponBounds.mapValues { RectF(it.value) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (couponBounds.isEmpty()) return

        couponBounds.forEach { (id, rect) ->
            val paint = if (id == activeCouponId) activeCouponPaint else outlinePaint
            canvas.drawRect(rect, paint)
        }
    }

    private fun isEdgeActive(edge: ResizeEdge): Boolean =
        activeResizeEdges.contains(edge)

    /**
     * Adjust the rectangle so it respects the minimum size constraints while staying within
     * the bitmap bounds. The adjustment considers which edges are currently active so we can
     * slide the opposite side instead of allowing the box to overflow the bitmap.
     */
    private fun ensureMinimumSize(rect: RectF): RectF {
        if (bitmapBounds.isEmpty) {
            return RectF(rect)
        }

        val adjusted = RectF(rect)
        val (left, right) = enforceAxisConstraints(
            start = adjusted.left,
            end = adjusted.right,
            minSizePx = minimumSelectionSizePx,
            maxBound = bitmapBounds.width(),
            startActive = isEdgeActive(ResizeEdge.LEFT),
            endActive = isEdgeActive(ResizeEdge.RIGHT),
        )
        val (top, bottom) = enforceAxisConstraints(
            start = adjusted.top,
            end = adjusted.bottom,
            minSizePx = minimumSelectionSizePx,
            maxBound = bitmapBounds.height(),
            startActive = isEdgeActive(ResizeEdge.TOP),
            endActive = isEdgeActive(ResizeEdge.BOTTOM),
        )

        adjusted.left = left
        adjusted.right = right
        adjusted.top = top
        adjusted.bottom = bottom

        return adjusted
    }

    private fun enforceAxisConstraints(
        start: Float,
        end: Float,
        minSizePx: Float,
        maxBound: Float,
        startActive: Boolean,
        endActive: Boolean,
    ): Pair<Float, Float> {
        if (maxBound <= 0f) {
            return 0f to 0f
        }

        var leading = start
        var trailing = end
        var leadingActive = startActive
        var trailingActive = endActive

        if (leading > trailing) {
            val tmp = leading
            leading = trailing
            trailing = tmp

            val tmpActive = leadingActive
            leadingActive = trailingActive
            trailingActive = tmpActive
        }

        val targetSize = min(minSizePx, maxBound)
        var span = trailing - leading

        if (span < targetSize) {
            when {
                leadingActive && !trailingActive -> {
                    leading = trailing - targetSize
                }
                trailingActive && !leadingActive -> {
                    trailing = leading + targetSize
                }
                else -> {
                    val center = (leading + trailing) / 2f
                    leading = center - targetSize / 2f
                    trailing = center + targetSize / 2f
                }
            }
            span = trailing - leading
        }

        if (leading < 0f) {
            if (leadingActive && !trailingActive) {
                leading = 0f
                trailing = max(targetSize, min(trailing, maxBound))
            } else {
                val shift = -leading
                leading = 0f
                trailing += shift
            }
        }

        if (trailing > maxBound) {
            if (trailingActive && !leadingActive) {
                trailing = maxBound
                leading = max(0f, min(leading, trailing - targetSize))
            } else {
                val shift = trailing - maxBound
                trailing = maxBound
                leading -= shift
            }
        }

        if (leading < 0f) {
            leading = 0f
            trailing = min(maxBound, leading + max(span, targetSize))
        }
        if (trailing > maxBound) {
            trailing = maxBound
            leading = max(0f, trailing - max(span, targetSize))
        }

        span = trailing - leading
        if (span < targetSize) {
            val size = targetSize
            when {
                trailingActive && !leadingActive -> {
                    trailing = min(maxBound, max(size, trailing))
                    leading = trailing - size
                    if (leading < 0f) {
                        leading = 0f
                        trailing = size
                    }
                }
                leadingActive && !trailingActive -> {
                    leading = max(0f, min(leading, maxBound - size))
                    trailing = leading + size
                }
                else -> {
                    if (size >= maxBound) {
                        leading = 0f
                        trailing = maxBound
                    } else {
                        val center = ((leading + trailing) / 2f).coerceIn(
                            size / 2f,
                            maxBound - size / 2f,
                        )
                        leading = center - size / 2f
                        trailing = center + size / 2f
                    }
                }
            }
        }

        leading = leading.coerceIn(0f, maxBound)
        trailing = trailing.coerceIn(0f, maxBound)

        if (trailing - leading < targetSize) {
            val size = min(targetSize, maxBound)
            when {
                trailingActive && !leadingActive -> {
                    trailing = min(maxBound, max(size, trailing))
                    leading = trailing - size
                }
                leadingActive && !trailingActive -> {
                    leading = max(0f, min(leading, maxBound - size))
                    trailing = leading + size
                }
                else -> {
                    trailing = min(maxBound, (leading + size))
                    leading = trailing - size
                    if (leading < 0f) {
                        leading = 0f
                        trailing = size
                    }
                }
            }
        }

        if (targetSize >= maxBound) {
            return 0f to maxBound
        }

        return leading to trailing
    }

    private fun clampRectToBitmap(rect: RectF): RectF {
        if (bitmapBounds.isEmpty) {
            return RectF(rect)
        }

        val clamped = RectF(rect)
        clamped.left = clamped.left.coerceIn(bitmapBounds.left, bitmapBounds.right)
        clamped.right = clamped.right.coerceIn(bitmapBounds.left, bitmapBounds.right)
        clamped.top = clamped.top.coerceIn(bitmapBounds.top, bitmapBounds.bottom)
        clamped.bottom = clamped.bottom.coerceIn(bitmapBounds.top, bitmapBounds.bottom)

        if (clamped.right < clamped.left) {
            val mid = (clamped.left + clamped.right) / 2f
            clamped.left = mid
            clamped.right = mid
        }
        if (clamped.bottom < clamped.top) {
            val mid = (clamped.top + clamped.bottom) / 2f
            clamped.top = mid
            clamped.bottom = mid
        }

        return clamped
    }

    private fun sanitizeBounds(rect: RectF): RectF {
        var adjusted = ensureMinimumSize(rect)
        adjusted = clampRectToBitmap(adjusted)
        adjusted = ensureMinimumSize(adjusted)
        return adjusted
    }

    fun handleCouponBoundingBoxChanged(id: String, rect: RectF) {
        val sanitized = sanitizeBounds(rect)
        couponBounds[id] = RectF(sanitized)
        listeners.forEach { listener ->
            listener.onCouponBoundsChanged(id, RectF(sanitized))
        }
        invalidate()
    }
}

