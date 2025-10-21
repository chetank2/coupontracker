package com.example.build

import org.gradle.api.Project
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

object Versioning {
    private const val CONFIG_PATH = "config/version.properties"

    fun load(project: Project): VersionInfo {
        val props = Properties()
        val file = versionFile(project)
        if (file.exists()) {
            FileInputStream(file).use(props::load)
        }

        val major = props.getProperty("major")?.toIntOrNull() ?: 0
        val minor = props.getProperty("minor")?.toIntOrNull() ?: 0
        val patch = props.getProperty("patch")?.toIntOrNull() ?: 0
        val metadata = props.getProperty("metadata")?.trim().orEmpty()

        return VersionInfo(major, minor, patch, metadata)
    }

    fun save(project: Project, info: VersionInfo) {
        val file = versionFile(project)
        file.parentFile?.let { parent ->
            if (!parent.exists()) {
                parent.mkdirs()
            }
        }

        val props = Properties().apply {
            setProperty("major", info.major.toString())
            setProperty("minor", info.minor.toString())
            setProperty("patch", info.patch.toString())
            setProperty("metadata", info.metadata)
        }

        FileOutputStream(file).use { output ->
            props.store(output, "Updated via bumpVersion task")
        }
    }

    fun currentGitHash(project: Project): String? = try {
        val output = ByteArrayOutputStream()
        val errorBuffer = ByteArrayOutputStream()
        project.exec { execSpec ->
            execSpec.commandLine("git", "rev-parse", "--short", "HEAD")
            execSpec.standardOutput = output
            execSpec.errorOutput = errorBuffer
            execSpec.isIgnoreExitValue = true
        }
        output.toString().trim().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    fun sanitizeForFilename(value: String): String =
        value.replace("[^A-Za-z0-9._-]".toRegex(), "_")

    private fun versionFile(project: Project): File =
        project.rootProject.file(CONFIG_PATH)
}
