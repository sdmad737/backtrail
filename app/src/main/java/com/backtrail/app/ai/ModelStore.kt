package com.backtrail.app.ai

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A pinned model artifact and the accelerator it was built for. */
data class PinnedModel(val fileName: String, val size: Long, val sha256: String, val backend: InferenceBackend)

/**
 * Imports only the pinned native artifacts, without fetching anything at runtime.
 * The Tensor G6 build runs on the NPU (measured on Pixel 11 Pro: describe 7.7 s);
 * the generic build runs on the GPU (8.6 s) and is the fallback.
 */
class ModelStore(private val context: Context) {
    private val importMutex = Mutex()
    private val dir = File(context.noBackupFilesDir, "models")

    /** The generic GPU artifact, kept for existing callers. */
    val file = File(dir, FILE_NAME)

    /** True when either pinned artifact is present at its expected size. */
    val ready: Boolean get() = preferred != null

    /** Tensor G6 build on the NPU when present, else the generic build on the GPU, else null. */
    val preferred: Pair<File, InferenceBackend>?
        get() = PINNED.firstNotNullOfOrNull { pinned ->
            File(dir, pinned.fileName).takeIf { it.isFile && it.length() == pinned.size }?.let { it to pinned.backend }
        }

    suspend fun import(uri: Uri) = importMutex.withLock {
        dir.mkdirs()
        val declared = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        // Match by declared size when the provider reports one; otherwise accept up to the largest pin.
        val candidates = PINNED.filter { declared < 0 || it.size == declared }
        require(candidates.isNotEmpty()) { "This is not the expected Gemma model." }
        val maxSize = candidates.maxOf { it.size }
        require(dir.usableSpace > maxSize + 128 * 1024 * 1024) { "Not enough free space to import the model." }
        val partial = File(dir, "import.partial")
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            val coroutineContext = currentCoroutineContext()
            val source = requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open the selected file." }
            var count = 0L
            source.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        require(count <= maxSize) { "This is not the expected Gemma model." }
                        hash.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            val digest = hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val match = candidates.firstOrNull { it.size == count && it.sha256 == digest }
            requireNotNull(match) { "Model checksum mismatch. Use the exact file linked in docs/SETUP.md." }
            val target = File(dir, match.fileName)
            target.delete()
            check(partial.renameTo(target)) { "Could not finish importing the model." }
        } finally {
            partial.delete()
        }
    }

    companion object {
        const val FILE_NAME = "gemma-4-E2B-it.litertlm"
        const val SIZE = 2_588_147_712L
        const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

        const val G6_FILE_NAME = "gemma-4-E2B-it_Google_Tensor_G6.litertlm"
        const val G6_SIZE = 3_313_938_293L
        const val G6_SHA256 = "f86c7c19c736e9307267946edef58b0a45494a9127036d54d90dd6b7617c95b7"

        /** In preference order. */
        val PINNED = listOf(
            PinnedModel(G6_FILE_NAME, G6_SIZE, G6_SHA256, InferenceBackend.NPU),
            PinnedModel(FILE_NAME, SIZE, SHA256, InferenceBackend.GPU),
        )
    }
}
