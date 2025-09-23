package com.example.coupontracker.util

import android.content.Context
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Manages on-device model bundles packaged under assets/models/.
 *
 * The manager supports multiple bundle "slots" (e.g. active, previous) and allows
 * switching between them at runtime. Consumers can observe bundle changes via the
 * provided listener API or StateFlow.
 */
class ModelManager private constructor(
    private val assetSource: AssetSource,
    private val logger: Logger
) {

    data class ActiveBundleState(
        val slot: BundleSlot?,
        val bundle: ModelBundle
    )

    data class ModelBundle(
        val directory: String,
        val bundleId: String?,
        val name: String,
        val version: SemVer,
        val basePath: String,
        val description: String?,
        val minimumAppVersion: String?,
        val files: Map<String, String>,
        val checksums: Map<String, String>,
        val slot: BundleSlot?
    ) {
        fun resolveFile(file: ModelFile): String {
            val relative = files[file.key]
            if (relative != null) {
                return "$basePath/$relative"
            }
            if (file.optional) {
                throw IllegalStateException(
                    "Optional file '${file.key}' not defined in manifest for $name ${version.raw}"
                )
            }
            throw IllegalArgumentException(
                "File key '${file.key}' not defined in manifest for $name ${version.raw}"
            )
        }

        fun resolveFileOrNull(file: ModelFile): String? = files[file.key]?.let { "$basePath/$it" }

        fun resolveRaw(key: String): String = files[key]?.let { "$basePath/$it" } ?: throw IllegalArgumentException(
            "File key '$key' not defined in manifest for $name ${version.raw}"
        )

        fun hasFile(file: ModelFile): Boolean = files.containsKey(file.key)
    }

    fun interface ModelBundleListener {
        fun onBundleActivated(bundle: ModelBundle)
    }

    private val bundlesByDirectory = mutableMapOf<String, ModelBundle>()
    private val bundlesBySlot = java.util.EnumMap<BundleSlot, ModelBundle>(BundleSlot::class.java)
    private val listeners = CopyOnWriteArrayList<ModelBundleListener>()

    private val activeStateRef: AtomicReference<ActiveBundleState>
    private val activeStateFlowInternal: MutableStateFlow<ActiveBundleState>

    init {
        val discovered = discoverBundles()
        discovered.forEach { bundle ->
            bundlesByDirectory[bundle.directory] = bundle
            bundle.slot?.let { bundlesBySlot[it] = bundle }
        }

        val initialState = determineInitialState(discovered)
        verifyChecksums(initialState.bundle)
        activeStateRef = AtomicReference(initialState)
        activeStateFlowInternal = MutableStateFlow(initialState)

        logger.i(
            TAG,
            "Loaded model bundle '${initialState.bundle.name}' version ${initialState.bundle.version.raw} from ${initialState.bundle.basePath}"
        )
    }

    val activeState: ActiveBundleState
        get() = activeStateRef.get()

    fun active(): ModelBundle = activeStateRef.get().bundle

    fun activeStateFlow(): StateFlow<ActiveBundleState> = activeStateFlowInternal.asStateFlow()

    fun availableSlots(): Set<BundleSlot> = bundlesBySlot.keys

    fun currentSlot(): BundleSlot? = activeStateRef.get().slot

    fun availableBundles(): Collection<ModelBundle> = bundlesByDirectory.values

    fun addListener(listener: ModelBundleListener) {
        listeners.add(listener)
        listener.onBundleActivated(active())
    }

    fun removeListener(listener: ModelBundleListener) {
        listeners.remove(listener)
    }

    fun setActiveSlot(slot: BundleSlot): Boolean {
        val targetBundle = bundlesBySlot[slot]
            ?: run {
                logger.w(TAG, "Requested slot $slot has no associated bundle")
                return false
            }
        val current = activeStateRef.get()
        if (current.slot == slot) {
            logger.d(TAG, "Slot $slot already active")
            return false
        }
        verifyChecksums(targetBundle)
        val newState = ActiveBundleState(slot, targetBundle)
        activeStateRef.set(newState)
        activeStateFlowInternal.value = newState
        notifyListeners(targetBundle)
        logger.i(TAG, "Activated model bundle '${targetBundle.name}' (${slot.name.lowercase()})")
        return true
    }

    fun activateBundle(directory: String): Boolean {
        val targetBundle = bundlesByDirectory[directory]
            ?: run {
                logger.w(TAG, "No bundle found for directory $directory")
                return false
            }
        val current = activeStateRef.get()
        if (current.bundle.directory == directory) {
            logger.d(TAG, "Bundle at $directory already active")
            return false
        }
        verifyChecksums(targetBundle)
        val slot = targetBundle.slot
        val newState = ActiveBundleState(slot, targetBundle)
        activeStateRef.set(newState)
        activeStateFlowInternal.value = newState
        notifyListeners(targetBundle)
        logger.i(TAG, "Activated model bundle '${targetBundle.name}' (dir=$directory)")
        return true
    }

    fun getFilePath(file: ModelFile): String = active().resolveFile(file)

    fun getFilePath(bundle: ModelBundle, file: ModelFile): String = bundle.resolveFile(file)

    fun getOptionalFilePath(bundle: ModelBundle, file: ModelFile): String? = bundle.resolveFileOrNull(file)

    fun openFile(file: ModelFile): InputStream = assetSource.open(getFilePath(file))

    fun openFile(bundle: ModelBundle, file: ModelFile): InputStream = assetSource.open(bundle.resolveFile(file))

    fun hasFile(file: ModelFile): Boolean = active().hasFile(file)

    private fun discoverBundles(): List<ModelBundle> {
        val entries = assetSource.list(MODELS_ROOT)
        val result = mutableListOf<ModelBundle>()
        entries.forEach { directory ->
            val manifestPath = "$MODELS_ROOT/$directory/$MANIFEST_NAME"
            if (!assetSource.exists(manifestPath)) {
                return@forEach
            }
            try {
                val manifestJson = assetSource.open(manifestPath).bufferedReader().use { it.readText() }
                val manifest = JSONObject(manifestJson)

                val name = manifest.optString("name", directory)
                val version = SemVer.parse(manifest.getString("version"))
                val bundleId = manifest.optString("bundle_id", "").takeIf { it.isNotBlank() }
                val description = manifest.optString("description", "").takeIf { it.isNotBlank() }
                val minimumAppVersion = manifest.optString("minimum_app_version", "").takeIf { it.isNotBlank() }

                val filesJson = manifest.getJSONObject("files")
                val files = filesJson.keys().asSequence().associateWith { filesJson.getString(it) }

                val checksums = manifest.optJSONObject("checksums")?.let { checksumObj ->
                    checksumObj.keys().asSequence().associateWith { checksumObj.getString(it) }
                } ?: emptyMap()

                val bundle = ModelBundle(
                    directory = directory,
                    bundleId = bundleId,
                    name = name,
                    version = version,
                    basePath = "$MODELS_ROOT/$directory",
                    description = description,
                    minimumAppVersion = minimumAppVersion,
                    files = files,
                    checksums = checksums,
                    slot = BundleSlot.fromDirectory(directory)
                )
                result.add(bundle)
            } catch (ex: Exception) {
                logger.e(TAG, "Failed to read manifest for bundle $directory", ex)
            }
        }

        if (result.isEmpty()) {
            throw IllegalStateException("No valid model manifests found in assets/$MODELS_ROOT")
        }
        return result
    }

    private fun determineInitialState(bundles: List<ModelBundle>): ActiveBundleState {
        val activeSlotBundle = bundlesBySlot[BundleSlot.ACTIVE]
        if (activeSlotBundle != null) {
            return ActiveBundleState(BundleSlot.ACTIVE, activeSlotBundle)
        }
        val previousSlotBundle = bundlesBySlot[BundleSlot.PREVIOUS]
        if (previousSlotBundle != null) {
            return ActiveBundleState(BundleSlot.PREVIOUS, previousSlotBundle)
        }
        val fallback = bundles.maxByOrNull { it.version }
            ?: throw IllegalStateException("Unable to determine initial model bundle")
        return ActiveBundleState(fallback.slot, fallback)
    }

    private fun notifyListeners(bundle: ModelBundle) {
        listeners.forEach { listener ->
            try {
                listener.onBundleActivated(bundle)
            } catch (ex: Exception) {
                logger.e(TAG, "Listener threw during bundle activation", ex)
            }
        }
    }

    private fun verifyChecksums(bundle: ModelBundle) {
        if (bundle.checksums.isEmpty()) {
            logger.w(TAG, "No checksums defined for bundle ${bundle.name} ${bundle.version.raw}")
            return
        }
        bundle.checksums.forEach { (fileKey, expectedHash) ->
            val path = bundle.files[fileKey]?.let { "${bundle.basePath}/$it" }
                ?: throw IllegalStateException(
                    "Checksum provided for unknown file key '$fileKey' in bundle ${bundle.name} ${bundle.version.raw}"
                )
            val actualHash = computeSha256(path)
            if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                throw IllegalStateException(
                    "Checksum mismatch for $path. Expected $expectedHash, got $actualHash"
                )
            }
        }
    }

    private fun computeSha256(assetPath: String): String {
        assetSource.open(assetPath).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break
                digest.update(buffer, 0, bytesRead)
            }
            return digest.digest().joinToString(separator = "") { byte ->
                "%02x".format(byte)
            }
        }
    }

    interface Logger {
        fun d(tag: String, message: String)
        fun i(tag: String, message: String)
        fun w(tag: String, message: String)
        fun e(tag: String, message: String, error: Throwable? = null)
    }

    interface AssetSource {
        fun list(path: String): List<String>
        fun open(path: String): InputStream
        fun exists(path: String): Boolean
    }

    private class AndroidLogger : Logger {
        override fun d(tag: String, message: String) { Log.d(tag, message) }
        override fun i(tag: String, message: String) { Log.i(tag, message) }
        override fun w(tag: String, message: String) { Log.w(tag, message) }
        override fun e(tag: String, message: String, error: Throwable?) {
            if (error != null) {
                Log.e(tag, message, error)
            } else {
                Log.e(tag, message)
            }
        }
    }

    private class AndroidAssetSource(
        private val context: Context
    ) : AssetSource {
        override fun list(path: String): List<String> {
            return try {
                context.assets.list(path)?.toList() ?: emptyList()
            } catch (_: IOException) {
                emptyList()
            }
        }

        override fun open(path: String): InputStream = context.assets.open(path)

        override fun exists(path: String): Boolean {
            return try {
                context.assets.open(path).close()
                true
            } catch (ioe: IOException) {
                try {
                    context.assets.list(path)?.isNotEmpty() ?: false
                } catch (_: IOException) {
                    false
                }
            }
        }
    }

    companion object {
        private const val TAG = "ModelManager"
        private const val MODELS_ROOT = "models"
        private const val MANIFEST_NAME = "manifest.json"
        private val INSTANCE = AtomicReference<ModelManager?>()
        private object NoopLogger : Logger {
            override fun d(tag: String, message: String) {}
            override fun i(tag: String, message: String) {}
            override fun w(tag: String, message: String) {}
            override fun e(tag: String, message: String, error: Throwable?) {}
        }

        fun getInstance(context: Context): ModelManager {
            return INSTANCE.get() ?: synchronized(this) {
                INSTANCE.get() ?: ModelManager(AndroidAssetSource(context.applicationContext), AndroidLogger()).also {
                    INSTANCE.set(it)
                }
            }
        }

        fun initialize(context: Context) {
            getInstance(context)
        }

        internal fun createForTest(assetSource: AssetSource, logger: Logger = NoopLogger): ModelManager = ModelManager(assetSource, logger)

        internal fun resetForTests() {
            INSTANCE.set(null)
        }
    }
}

enum class ModelFile(val key: String, val optional: Boolean = false) {
    MODEL("model"),
    LABELS("labels"),
    PREPROCESS("preprocess"),
    POSTPROCESS("postprocess"),
    METADATA("metadata"),
    PATTERNS("patterns"),
    CONFIG("config", optional = true)
}

enum class BundleSlot(val directory: String) {
    ACTIVE("active"),
    PREVIOUS("previous");

    companion object {
        fun fromDirectory(directory: String): BundleSlot? {
            return values().firstOrNull { it.directory.equals(directory, ignoreCase = true) }
        }
    }
}
