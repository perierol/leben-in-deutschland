package com.nextdaymobilestudio.lebenindeutschland.core.content.images

import android.content.Context
import com.nextdaymobilestudio.lebenindeutschland.core.content.sync.ContentSyncException
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.ContentImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Makes the images a content release references available before it is activated. */
fun interface ContentImageInstaller {
    suspend fun install(images: Collection<ContentImage>)

    companion object {
        /** Installs nothing. Used by tests that do not exercise images. */
        val None: ContentImageInstaller = ContentImageInstaller { }
    }
}

/**
 * Durable offline copies of question and answer images (ADR 0002).
 *
 * Each image is stored under its SHA-256 in app storage. A file is only moved into
 * place after its hash has been verified, so an existing file is a verified image and
 * is never downloaded again, whichever language package references it.
 */
class ContentImageStore(
    private val directory: File,
    private val download: suspend (url: String) -> ByteArray = { url -> downloadHttps(url) },
) : ContentImageInstaller {
    override suspend fun install(images: Collection<ContentImage>) = withContext(Dispatchers.IO) {
        images.distinctBy(ContentImage::sha256).forEach { image -> installImage(image) }
    }

    private suspend fun installImage(image: ContentImage) {
        val target = fileFor(directory, image.sha256)
            ?: throw ContentSyncException("Invalid image hash for ${image.storagePath}")
        if (target.isFile) return

        val bytes = try {
            download(image.sourceUrl)
        } catch (cause: IOException) {
            throw ContentSyncException("Image download failed for ${image.storagePath}", cause)
        }
        if (sha256Hex(bytes) != image.sha256) {
            throw ContentSyncException("Image hash mismatch for ${image.storagePath}")
        }

        directory.mkdirs()
        val partial = File(directory, "${image.sha256}$PARTIAL_SUFFIX")
        try {
            partial.writeBytes(bytes)
            if (!partial.renameTo(target)) {
                throw ContentSyncException("Could not store image ${image.storagePath}")
            }
        } finally {
            partial.delete()
        }
    }

    companion object {
        private const val DIRECTORY_NAME = "content-images"
        private const val PARTIAL_SUFFIX = ".partial"
        private const val TIMEOUT_MILLIS = 15_000
        private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        fun directory(context: Context): File = File(context.filesDir, DIRECTORY_NAME)

        /** The verified local copy of [image], or null when it is not installed. */
        fun localFile(directory: File, image: ContentImage): File? =
            fileFor(directory, image.sha256)?.takeIf(File::isFile)

        // The hash becomes a file name, so only a well-formed hash may reach the file system.
        private fun fileFor(directory: File, sha256: String): File? =
            sha256.takeIf(SHA256::matches)?.let { File(directory, it) }

        private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

        private fun downloadHttps(url: String): ByteArray {
            val parsed = URL(url)
            if (parsed.protocol != "https") {
                throw ContentSyncException("Image URL must use HTTPS: $url")
            }
            val connection = parsed.openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP ${connection.responseCode} for $url")
                }
                return connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        if (output.size() > MAX_IMAGE_BYTES) {
                            throw IOException("Image larger than $MAX_IMAGE_BYTES bytes: $url")
                        }
                    }
                    output.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}

/** What Compose should load for [this] image: the installed copy, else its source URL. */
fun ContentImage.displayModel(context: Context): Any =
    ContentImageStore.localFile(ContentImageStore.directory(context), this)
        ?: sourceUrl.ifBlank { sourcePath }
