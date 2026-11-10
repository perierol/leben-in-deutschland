package com.nextdaymobilestudio.lebenindeutschland.core.content.images

import com.nextdaymobilestudio.lebenindeutschland.core.content.sync.ContentSyncException
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.ContentImage
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContentImageStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val bytes = "answer-image-bytes".toByteArray()
    private val image = ContentImage(
        sourcePath = "images/answer-D.png",
        sourceUrl = "https://example.com/answer-D.png",
        storagePath = "question-images/answer-D.png",
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) },
        width = 375,
        height = 488,
        mimeType = "image/png",
    )

    @Test
    fun installedImageStaysAvailableWhenTheNetworkIsGone() = runBlocking {
        val directory = folder.newFolder("content-images")
        ContentImageStore(directory) { bytes }.install(listOf(image))

        // Next launch, offline: a verified image is not downloaded again and is still on disk.
        val offline = ContentImageStore(directory) { throw IOException("offline") }
        offline.install(listOf(image))

        val file = ContentImageStore.localFile(directory, image)
        assertNotNull(file)
        assertArrayEquals(bytes, file!!.readBytes())
    }

    @Test
    fun imageWithWrongHashIsRejectedAndNotStored() {
        val directory = folder.newFolder("content-images")

        assertThrows(ContentSyncException::class.java) {
            runBlocking {
                ContentImageStore(directory) { "tampered".toByteArray() }.install(listOf(image))
            }
        }

        assertNull(ContentImageStore.localFile(directory, image))
        assertEquals(emptyList<String>(), directory.list().orEmpty().toList())
    }
}
