package com.example.coupontracker.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.example.coupontracker.data.model.CouponField
import com.example.coupontracker.data.model.FieldType

/**
 * Utility functions for image processing
 */
object ImageUtils {
    /**
     * Resize a bitmap while preserving aspect ratio
     */
    fun resizeBitmap(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        val scaleWidth = targetWidth.toFloat() / width
        val scaleHeight = targetHeight.toFloat() / height
        
        // Use the smaller scale to ensure the entire image fits
        val scale = minOf(scaleWidth, scaleHeight)
        
        val matrix = Matrix()
        matrix.postScale(scale, scale)
        
        val resizedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
        
        // If the resized bitmap is smaller than the target size, pad it with black
        if (resizedBitmap.width != targetWidth || resizedBitmap.height != targetHeight) {
            val paddedBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(paddedBitmap)
            canvas.drawColor(Color.BLACK)
            
            // Center the resized bitmap
            val left = (targetWidth - resizedBitmap.width) / 2f
            val top = (targetHeight - resizedBitmap.height) / 2f
            
            canvas.drawBitmap(resizedBitmap, left, top, null)
            
            return paddedBitmap
        }
        
        return resizedBitmap
    }
    
    /**
     * Draw detected fields on a bitmap
     */
    fun drawDetections(bitmap: Bitmap, fields: List<CouponField>): Bitmap {
        val resultBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)
        
        fields.forEach { field ->
            val paint = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 4f
                color = getColorForFieldType(field.type)
            }
            
            // Draw bounding box
            canvas.drawRect(field.boundingBox, paint)
            
            // Draw label
            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = 36f
                style = Paint.Style.FILL
            }
            
            val labelBgPaint = Paint().apply {
                color = getColorForFieldType(field.type)
                style = Paint.Style.FILL
            }
            
            val label = "${field.type.name} (${(field.confidence * 100).toInt()}%)"
            val textWidth = textPaint.measureText(label)
            val textHeight = 40f
            
            // Draw label background
            canvas.drawRect(
                field.boundingBox.left,
                field.boundingBox.top - textHeight,
                field.boundingBox.left + textWidth + 10,
                field.boundingBox.top,
                labelBgPaint
            )
            
            // Draw label text
            canvas.drawText(
                label,
                field.boundingBox.left + 5,
                field.boundingBox.top - 10,
                textPaint
            )
        }
        
        return resultBitmap
    }
    
    /**
     * Get color for field type
     */
    private fun getColorForFieldType(type: FieldType): Int {
        return when (type) {
            FieldType.STORE_NAME -> Color.RED
            FieldType.COUPON_CODE -> Color.GREEN
            FieldType.EXPIRY_DATE -> Color.BLUE
            FieldType.DESCRIPTION -> Color.YELLOW
            FieldType.AMOUNT -> Color.MAGENTA
            FieldType.MIN_PURCHASE -> Color.CYAN
            FieldType.OTHER -> Color.WHITE
        }
    }
}
