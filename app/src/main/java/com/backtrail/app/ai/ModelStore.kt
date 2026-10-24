package com.backtrail.app.ai

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Imports only the pinned native artifact, without fetching anything at runtime. */
class ModelStore(private val context: Context) {
    val file = File(context.noBackupFilesDir, "models/$FILE_NAME")
    val ready: Boolean get() = file.isFile && file.length() == SIZE

    suspend fun import(uri: Uri) {
        file.parentFile!!.mkdirs()
        require(file.parentFile!!.usableSpace > SIZE + 128 * 1024 * 1024) { "Not enough free space to import the model." }
        val partial = File(file.parentFile, "$FILE_NAME.partial")
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
                        require(count <= SIZE) { "This is not the expected Gemma model." }
                        hash.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            val digest = hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(count == SIZE && digest == SHA256) { "Model checksum mismatch. Use the exact file linked in docs/SETUP.md." }
            check(partial.renameTo(file)) { "Could not finish importing the model." }
        } finally {
            partial.delete()
        }
    }

    companion object {
        const val FILE_NAME = "gemma-4-E2B-it.litertlm"
        const val SIZE = 2_588_147_712L
        const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    }
}
