package cn.tabletzoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AspectRatioZoomTest {
    @Test
    fun panCenterMapsPreviewOffsetToDisplayCenter() {
        val center = MagnificationPan.center(1000f, 2000f, 2f, 0f, -0.1f)
        assertEquals(500f, center.x, 0.001f)
        assertEquals(1100f, center.y, 0.001f)
    }

    @Test
    fun panCenterClampsToDisplayBounds() {
        val center = MagnificationPan.center(1000f, 2000f, 1.17f, 0f, -1f)
        assertEquals(2000f, center.y, 0.001f)
    }

    @Test
    fun threeByTwoScreenFillsFourByThreeVideo() {
        assertEquals(1.125, AspectRatioZoom.coverScale(3.0 / 2.0, 4.0 / 3.0), 0.000001)
    }

    @Test
    fun threeByTwoAndFourByThreeUseSampleCalibratedCrop() {
        assertEquals(1.243, AspectRatioZoom.defaultScale(3.0 / 2.0, 4.0 / 3.0), 0.000001)
        assertEquals(-0.0167, AspectRatioZoom.defaultPanY(3.0 / 2.0, 4.0 / 3.0), 0.000001)
    }

    @Test
    fun otherRatiosKeepCenteredPan() {
        assertEquals(0.0, AspectRatioZoom.defaultPanY(1.5, 16.0 / 9.0), 0.000001)
    }

    @Test
    fun otherDefaultsUseCoverScale() {
        assertEquals(16.0 / 9.0 / 1.5, AspectRatioZoom.defaultScale(1.5, 16.0 / 9.0), 0.000001)
    }

    @Test
    fun widerTargetUsesSameCoverRule() {
        assertEquals(4.0 / 3.0, AspectRatioZoom.coverScale(4.0 / 3.0, 16.0 / 9.0), 0.000001)
    }

    @Test
    fun acceptsFractionsAndDecimals() {
        assertEquals(1.5, AspectRatioZoom.parse("3:2")!!, 0.000001)
        assertEquals(4.0 / 3.0, AspectRatioZoom.parse("4/3")!!, 0.000001)
        assertEquals(7.0 / 5.0, AspectRatioZoom.parse("7比5")!!, 0.000001)
        assertEquals(1.5, AspectRatioZoom.parse("1.500")!!, 0.000001)
    }

    @Test
    fun rejectsInvalidOrUnboundedRatios() {
        assertNull(AspectRatioZoom.parse("3:0"))
        assertNull(AspectRatioZoom.parse("abc"))
        assertNull(AspectRatioZoom.parse("20:1"))
    }
}
