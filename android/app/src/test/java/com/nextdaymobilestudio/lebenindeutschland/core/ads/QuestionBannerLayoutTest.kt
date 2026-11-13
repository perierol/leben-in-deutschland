package com.nextdaymobilestudio.lebenindeutschland.core.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionBannerLayoutTest {
    @Test
    fun typicalPortraitPhonesShowTheBanner() {
        assertTrue(QuestionBannerLayout.fitsScreen(windowHeightDp = 640, fontScale = 1f))
        assertTrue(QuestionBannerLayout.fitsScreen(windowHeightDp = 915, fontScale = 1.3f))
    }

    @Test
    fun landscapePhonesAndShortScreensHideTheBanner() {
        assertFalse(QuestionBannerLayout.fitsScreen(windowHeightDp = 360, fontScale = 1f))
        assertFalse(QuestionBannerLayout.fitsScreen(windowHeightDp = 559, fontScale = 1f))
    }

    @Test
    fun largeTextNeedsATallerScreen() {
        assertFalse(QuestionBannerLayout.fitsScreen(windowHeightDp = 640, fontScale = 2f))
        assertTrue(QuestionBannerLayout.fitsScreen(windowHeightDp = 900, fontScale = 2f))
    }

    @Test
    fun smallTextDoesNotLowerTheRequirement() {
        assertFalse(QuestionBannerLayout.fitsScreen(windowHeightDp = 500, fontScale = 0.85f))
    }

    @Test
    fun adWidthLeavesTheSlotPadding() {
        assertEquals(372, QuestionBannerLayout.adWidthDp(windowWidthDp = 412))
        assertEquals(0, QuestionBannerLayout.adWidthDp(windowWidthDp = 20))
    }
}
