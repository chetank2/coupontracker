package com.example.build

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.util.Locale

abstract class BumpVersionTask : DefaultTask() {

    private var bumpType: String = "patch"
    private var metadataOverride: String? = null

    @Option(option = "type", description = "Version component to bump: major, minor, or patch")
    fun setType(value: String) {
        bumpType = value.lowercase(Locale.US)
    }

    @Input
    fun getType(): String = bumpType

    @Option(option = "metadata", description = "Optional metadata suffix (e.g. rc1)")
    fun setMetadata(value: String) {
        metadataOverride = value
    }

    @Input
    @Optional
    fun getMetadata(): String? = metadataOverride

    @TaskAction
    fun bump() {
        val current = Versioning.load(project)
        val updated = when (bumpType) {
            "major" -> current.copy(
                major = current.major + 1,
                minor = 0,
                patch = 0
            )
            "minor" -> current.copy(
                minor = current.minor + 1,
                patch = 0
            )
            else -> current.copy(patch = current.patch + 1)
        }.let { info ->
            metadataOverride?.let { info.copy(metadata = it.trim()) } ?: info
        }

        Versioning.save(project, updated)
        logger.lifecycle(
            "Version bumped to ${updated.formatted(updated.metadata)} (code ${updated.versionCode()})"
        )
    }
}
