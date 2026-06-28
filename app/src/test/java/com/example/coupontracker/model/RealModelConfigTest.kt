package com.example.coupontracker.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RealModelConfigTest {

    @Test
    fun `model config does not use checksum bypass sentinel`() {
        val checksums = RealModelConfig.ALL_FILES.map { it.sha256 }

        assertFalse(checksums.contains("COMPUTE_ON_FIRST_DOWNLOAD"))
    }

    @Test
    fun `unknown upstream checksums are represented as null`() {
        RealModelConfig.ALL_FILES.forEach { file ->
            assertNull(file.sha256)
        }
    }
}
