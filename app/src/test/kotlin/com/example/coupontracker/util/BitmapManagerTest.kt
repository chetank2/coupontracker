package com.example.coupontracker.util

import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

/**
 * Unit tests for BitmapManager memory management
 */
@RunWith(RobolectricTestRunner::class)
class BitmapManagerTest {

    @Before
    fun setup() {
        BitmapManager.cleanup()
    }

    @After
    fun cleanup() {
        BitmapManager.cleanup()
    }

    @Test
    fun `createManagedBitmap should track memory usage`() = runBlocking {
        val sourceBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        
        val initialStats = BitmapManager.getMemoryStats()
        assertEquals(0, initialStats.currentPixels)
        
        val managedBitmap = BitmapManager.createManagedBitmap(sourceBitmap, "test")
        
        val afterStats = BitmapManager.getMemoryStats()
        assertTrue("Memory usage should increase", afterStats.currentPixels > 0)
        assertEquals(1, afterStats.activeBitmapCount)
        
        BitmapManager.releaseBitmap(managedBitmap.id)
        
        val finalStats = BitmapManager.getMemoryStats()
        assertEquals(0, finalStats.currentPixels)
        assertEquals(0, finalStats.activeBitmapCount)
    }

    @Test
    fun `createManagedCrop should handle invalid bounds`() = runBlocking {
        val sourceBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        
        // Test invalid crop bounds
        val invalidRect = Rect(-10, -10, -5, -5)
        val result = BitmapManager.createManagedCrop(sourceBitmap, invalidRect, "invalid")
        
        assertNull("Invalid crop should return null", result)
    }

    @Test
    fun `createManagedCrop should adjust bounds correctly`() = runBlocking {
        val sourceBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        
        // Test crop that extends beyond bitmap bounds
        val oversizedRect = Rect(50, 50, 150, 150)
        val result = BitmapManager.createManagedCrop(sourceBitmap, oversizedRect, "oversized")
        
        assertNotNull("Valid crop should succeed", result)
        result?.let { crop ->
            assertEquals("Crop width should be adjusted", 50, crop.bitmap.width)
            assertEquals("Crop height should be adjusted", 50, crop.bitmap.height)
            BitmapManager.releaseBitmap(crop.id)
        }
    }

    @Test
    fun `large bitmap should be downsampled`() = runBlocking {
        val largeBitmap = Bitmap.createBitmap(3000, 3000, Bitmap.Config.ARGB_8888)
        
        val managedBitmap = BitmapManager.createManagedBitmap(largeBitmap, "large", maxDimension = 1024)
        
        assertTrue("Large bitmap should be downsampled", managedBitmap.wasDownsampled)
        assertTrue("Width should be reduced", managedBitmap.bitmap.width <= 1024)
        assertTrue("Height should be reduced", managedBitmap.bitmap.height <= 1024)
        assertTrue("Final pixels should be less than original", 
                  managedBitmap.finalPixels < managedBitmap.originalPixels)
        
        BitmapManager.releaseBitmap(managedBitmap.id)
    }

    @Test
    fun `memory stats should be accurate`() = runBlocking {
        val bitmap1 = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val bitmap2 = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        
        val managed1 = BitmapManager.createManagedBitmap(bitmap1, "test1")
        val managed2 = BitmapManager.createManagedBitmap(bitmap2, "test2")
        
        val stats = BitmapManager.getMemoryStats()
        
        assertEquals(2, stats.activeBitmapCount)
        assertEquals(50000L, stats.currentPixels) // 100*100 + 200*200 = 50,000
        assertTrue("Memory usage should be positive", stats.currentMemoryMB > 0)
        assertTrue("Utilization should be calculated", stats.utilizationPercent > 0)
        
        BitmapManager.releaseBitmap(managed1.id)
        BitmapManager.releaseBitmap(managed2.id)
    }

    @Test
    fun `cleanup should release all bitmaps`() = runBlocking {
        val bitmap1 = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val bitmap2 = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        
        BitmapManager.createManagedBitmap(bitmap1, "test1")
        BitmapManager.createManagedBitmap(bitmap2, "test2")
        
        val beforeCleanup = BitmapManager.getMemoryStats()
        assertEquals(2, beforeCleanup.activeBitmapCount)
        
        BitmapManager.cleanup()
        
        val afterCleanup = BitmapManager.getMemoryStats()
        assertEquals(0, afterCleanup.activeBitmapCount)
        assertEquals(0, afterCleanup.currentPixels)
    }
}
