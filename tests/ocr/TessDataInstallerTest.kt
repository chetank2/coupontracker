package com.example.coupontracker.ocr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class TessDataInstallerTest {

    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    @Test
    fun `installs tessdata when assets are missing`() {
        val workingDir = createWorkingDir()
        val trainedData = "sample-trained-data".toByteArray()
        val checksum = TessDataInstaller.computeSha256(trainedData)

        val installer = createInstaller(workingDir, trainedData, checksum)

        val result = installer.installIfNeeded()

        val trainedFile = File(workingDir, "eng.traineddata")
        val sentinelFile = File(workingDir, "eng.traineddata.version")

        assertTrue("traineddata file should be created", trainedFile.exists())
        assertTrue("sentinel should be created", sentinelFile.exists())
        assertEquals("installed checksum must match asset", checksum, result.installedChecksum)
        assertTrue("first install should copy asset", result.copied)
        assertEquals(
            "sentinel should include version and checksum",
            "test-version|$checksum",
            sentinelFile.readText()
        )
    }

    @Test
    fun `skips reinstall when checksum and sentinel match`() {
        val workingDir = createWorkingDir()
        val trainedData = "stable-trained-data".toByteArray()
        val checksum = TessDataInstaller.computeSha256(trainedData)
        val installer = createInstaller(workingDir, trainedData, checksum)

        installer.installIfNeeded()
        val secondResult = installer.installIfNeeded()

        assertFalse("second run should not copy asset", secondResult.copied)
    }

    @Test
    fun `reinstalls when traineddata is corrupted`() {
        val workingDir = createWorkingDir()
        val trainedData = "golden-trained-data".toByteArray()
        val checksum = TessDataInstaller.computeSha256(trainedData)
        val installer = createInstaller(workingDir, trainedData, checksum)

        installer.installIfNeeded()

        // Corrupt the installed file to simulate partial download
        File(workingDir, "eng.traineddata").writeText("corrupted-data")

        val recoveryResult = installer.installIfNeeded()

        assertTrue("installer should recopy corrupted asset", recoveryResult.copied)
        val restoredBytes = File(workingDir, "eng.traineddata").readBytes()
        assertEquals("reinstalled file must match the original asset", trainedData.toList(), restoredBytes.toList())
    }

    private fun createInstaller(dir: File, data: ByteArray, checksum: String): TessDataInstaller {
        return TessDataInstaller(
            assetOpener = { ByteArrayInputStream(data) },
            tessDataDir = dir,
            trainedDataFilename = "eng.traineddata",
            sentinelFilename = "eng.traineddata.version",
            expectedChecksum = checksum,
            expectedVersion = "test-version"
        )
    }

    private fun createWorkingDir(): File {
        val path = Files.createTempDirectory("tess-installer-test")
        return path.toFile().also { tempDirs.add(it) }
    }
}
