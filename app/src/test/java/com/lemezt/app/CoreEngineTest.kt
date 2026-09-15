package com.lemezt.app

import com.lemezt.app.core.common.AppError
import com.lemezt.app.domain.model.OutputMediaType
import com.lemezt.app.domain.model.OutputPlan
import com.lemezt.app.engine.YouTubeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreEngineTest {

    @Test
    fun testYouTubeIdExtraction_standardUrl() {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val id = YouTubeParser.extractVideoId(url)
        assertEquals("dQw4w9WgXcQ", id)
    }

    @Test
    fun testYouTubeIdExtraction_shortUrl() {
        val url = "https://youtu.be/dQw4w9WgXcQ?si=abcdef12345"
        val id = YouTubeParser.extractVideoId(url)
        assertEquals("dQw4w9WgXcQ", id)
    }

    @Test
    fun testYouTubeIdExtraction_embedUrl() {
        val url = "https://www.youtube.com/embed/dQw4w9WgXcQ"
        val id = YouTubeParser.extractVideoId(url)
        assertEquals("dQw4w9WgXcQ", id)
    }

    @Test
    fun testYouTubeIdExtraction_shortsUrl() {
        val url = "https://www.youtube.com/shorts/dQw4w9WgXcQ"
        val id = YouTubeParser.extractVideoId(url)
        assertEquals("dQw4w9WgXcQ", id)
    }

    @Test
    fun testOutputPlanCreation_audioM4A() {
        val plan = OutputPlan(
            mediaType = OutputMediaType.AUDIO,
            container = "m4a",
            qualityLabel = "140 (128 kbps)",
            estimatedBytes = 5_000_000L,
            isProgressive = true,
            embedCoverArt = true,
            saveLyrics = true
        )

        assertEquals(OutputMediaType.AUDIO, plan.mediaType)
        assertEquals("m4a", plan.container)
        assertTrue(plan.embedCoverArt)
        assertTrue(plan.saveLyrics)
    }

    @Test
    fun testAppError_messages() {
        val networkErr = AppError.NetworkFailure()
        assertTrue(networkErr.userMessage.contains("Network error"))

        val invalidUrl = AppError.InvalidUrl()
        assertTrue(invalidUrl.userMessage.contains("valid YouTube link"))
    }
}
