package com.nextdaymobilestudio.lebenindeutschland.core.content.images

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import com.nextdaymobilestudio.lebenindeutschland.R
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.ContentImage
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.StudyAnswer
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.StudyQuestion
import com.nextdaymobilestudio.lebenindeutschland.feature.studysession.StudySessionUiState
import com.nextdaymobilestudio.lebenindeutschland.feature.studysession.ui.StudySessionScreen
import com.nextdaymobilestudio.lebenindeutschland.ui.theme.LebenInDeutschlandTheme
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for audit finding A2: an image that content sync installed must
 * render with no network, from durable app storage rather than a cache.
 */
@RunWith(AndroidJUnit4::class)
class OfflineContentImageTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = ContentImageStore.directory(context)
    private val pngBytes = solidPng(Color.MAGENTA)
    private val image = ContentImage(
        sourcePath = "images/offline-test.png",
        // The .invalid top-level domain never resolves, so this source is unreachable
        // exactly as it is on a device without network.
        sourceUrl = "https://offline.invalid/offline-test.png",
        storagePath = "question-images/offline-test.png",
        sha256 = sha256Hex(pngBytes),
        width = IMAGE_SIZE,
        height = IMAGE_SIZE,
        mimeType = "image/png",
    )

    @After
    fun deleteInstalledImage() {
        ContentImageStore.localFile(directory, image)?.delete()
    }

    @Test
    fun imageInstalledBySyncRendersWithoutNetwork() {
        // Content sync while online: the image source serves the bytes once.
        runBlocking {
            ContentImageStore(directory) { pngBytes }.install(listOf(image))
        }

        // Network gone. Coil's memory and disk caches are emptied so that neither a
        // temporary online cache nor an in-memory copy can satisfy the request.
        val imageLoader = SingletonImageLoader.get(context)
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()

        composeRule.setContent {
            LebenInDeutschlandTheme {
                StudySessionScreen(
                    uiState = StudySessionUiState.Ready(
                        question = questionWith(image),
                        currentIndex = 0,
                        totalCount = 1,
                    ),
                    onBack = {},
                    onAnswerSelected = {},
                    onSubmitAnswer = {},
                    onNextQuestion = {},
                    onRetry = {},
                    onFinished = {},
                )
            }
        }

        val description = context.getString(R.string.study_question_image_description)
        val rendered = runCatching {
            composeRule.waitUntil(timeoutMillis = 10_000) { imagePixels(description) > 0 }
        }.isSuccess
        assertTrue(
            "The installed question image did not render offline " +
                "(${imagePixels(description)} image pixels on screen)",
            rendered,
        )
    }

    private fun imagePixels(contentDescription: String): Int {
        val bitmap = composeRule.onNodeWithContentDescription(contentDescription)
            .captureToImage()
            .asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { pixel -> pixel == Color.MAGENTA }
    }

    private companion object {
        const val IMAGE_SIZE = 64

        fun solidPng(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            return ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        }

        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

        fun questionWith(image: ContentImage) = StudyQuestion(
            releaseId = "release-test",
            id = "general_021",
            officialNumber = 21,
            scope = "GENERAL",
            stateCode = null,
            category = null,
            correctAnswerId = "A",
            germanQuestionText = "Welches Bild zeigt das Wappen?",
            localizedQuestionText = "Which picture shows the coat of arms?",
            explanation = "Explanation.",
            languageCode = "en",
            questionImage = image,
            answers = listOf(
                StudyAnswer("A", 0, "Bild 1", "Picture 1", null),
                StudyAnswer("B", 1, "Bild 2", "Picture 2", null),
            ),
            explanationStatus = "AI_GENERATED_NEEDS_REVIEW",
            reviewed = false,
        )
    }
}
