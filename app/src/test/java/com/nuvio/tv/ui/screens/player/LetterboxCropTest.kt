package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterboxCropTest {

    private val tv = 16f / 9f
    private val uhdFrame = 3840f / 2160f

    // A 2.39:1 film in a 16:9 UHD frame: bars of (1 - 1.778 / 2.39) / 2 of the height.
    private val scopeBar = (1f - uhdFrame / 2.39f) / 2f
    private val scopePicture = letterboxPictureAspect(uhdFrame, scopeBar)!!
    private val cropZoom = scopePicture / tv

    @Test
    fun pictureAspect_isTheFrameWithoutItsBars() {
        assertEquals(2.39f, scopePicture, 0.001f)
        assertNull(letterboxPictureAspect(uhdFrame, 0f))
        assertNull(letterboxPictureAspect(0f, scopeBar))
    }

    @Test
    fun crop_zoomsTheFrameUntilThePictureFillsTheScreen() {
        val fraction = letterboxFrameFraction(AspectMode.FULL_SCREEN, uhdFrame, scopePicture, tv)
        assertEquals(cropZoom, fraction.scaleX, 0.001f)
        assertEquals(cropZoom, fraction.scaleY, 0.001f)
    }

    @Test
    fun fitHeight_matchesCrop_forAWidePicture() {
        val fraction = letterboxFrameFraction(AspectMode.VERTICAL_STRETCH, uhdFrame, scopePicture, tv)
        assertEquals(cropZoom, fraction.scaleX, 0.001f)
        assertEquals(cropZoom, fraction.scaleY, 0.001f)
    }

    @Test
    fun stretch_fillsTheScreenWithThePictureVertically() {
        val fraction = letterboxFrameFraction(AspectMode.STRETCH, uhdFrame, scopePicture, tv)
        assertEquals(1f, fraction.scaleX, 0.001f)
        assertEquals(cropZoom, fraction.scaleY, 0.001f)
    }

    @Test
    fun fitWidth_keepsTheFrameFitted_forAWidePicture() {
        val fraction = letterboxFrameFraction(AspectMode.HORIZONTAL_STRETCH, uhdFrame, scopePicture, tv)
        assertEquals(1f, fraction.scaleX, 0.001f)
        assertEquals(1f, fraction.scaleY, 0.001f)
    }

    @Test
    fun picture_staysUndistorted_inEveryUniformMode() {
        listOf(AspectMode.FULL_SCREEN, AspectMode.VERTICAL_STRETCH, AspectMode.HORIZONTAL_STRETCH).forEach { mode ->
            val fraction = letterboxFrameFraction(mode, uhdFrame, scopePicture, tv)
            val shownAspect = fraction.scaleX * tv / fraction.scaleY
            assertEquals("$mode", uhdFrame, shownAspect, 0.001f)
        }
    }

    @Test
    fun onlyFrameRelativeModesCropTheLetterbox() {
        assertTrue(aspectModeCropsLetterbox(AspectMode.FULL_SCREEN))
        assertTrue(aspectModeCropsLetterbox(AspectMode.STRETCH))
        assertTrue(aspectModeCropsLetterbox(AspectMode.VERTICAL_STRETCH))
        assertTrue(aspectModeCropsLetterbox(AspectMode.HORIZONTAL_STRETCH))
        assertFalse(aspectModeCropsLetterbox(AspectMode.ORIGINAL))
        assertFalse(aspectModeCropsLetterbox(AspectMode.FORCE_STRETCH))
        assertFalse(aspectModeCropsLetterbox(AspectMode.SLIGHT_ZOOM))
        assertFalse(aspectModeCropsLetterbox(AspectMode.CINEMA_ZOOM))
    }

    @Test
    fun libassFitGeometry_coversTheWholeScreen_forA16x9Frame() {
        val subtitle = aspectModeVideoFraction(AspectMode.ORIGINAL, uhdFrame, tv)
        assertEquals(1f, subtitle.scaleX, 0.001f)
        assertEquals(1f, subtitle.scaleY, 0.001f)
    }
}
