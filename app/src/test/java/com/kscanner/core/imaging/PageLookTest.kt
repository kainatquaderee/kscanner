package com.kscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The look value type: the mapping every render of a saved page goes through, and the outline
 * scaling that lets one stored outline be rendered at two decode resolutions.
 *
 * Both are load-bearing for this change. A mapping that dropped strength would repaint a page on
 * a rotate; a mapping that passed an out-of-range stored value straight through would put a
 * number from the database into the pixel pipeline unchecked.
 */
class PageLookTest {

    @Test
    fun `the whole look reaches the processor settings`() {
        val look = PageLook(
            filter = ScanFilter.BLACK_WHITE,
            strength = 0.4f,
            brightness = -0.2f,
            contrast = 0.3f,
            sharpness = 0.6f,
        )
        val quad = Quad.rect(10.0, 20.0)

        val settings = look.toSettings(
            quad = quad,
            rotationDegrees = 90,
            deskewDegrees = -1.5,
            maxOutputEdge = 2600,
        )

        assertEquals(ScanFilter.BLACK_WHITE, settings.filter)
        assertEquals(0.4f, settings.strength, 0f)
        assertEquals(-0.2f, settings.brightness, 0f)
        assertEquals(0.3f, settings.contrast, 0f)
        assertEquals(0.6f, settings.sharpness, 0f)
        assertEquals(quad, settings.quad)
        assertEquals(90, settings.rotationDegrees)
        assertEquals(-1.5, settings.deskewDegrees, 0.0)
        assertEquals(2600, settings.maxOutputEdge)
    }

    @Test
    fun `a page with no skew and no adjustments maps to the preset alone`() {
        val settings = PageLook(filter = ScanFilter.MAGIC_COLOR).toSettings(
            quad = null,
            rotationDegrees = 0,
            deskewDegrees = 0.0,
            maxOutputEdge = 2600,
        )

        assertNull(settings.quad)
        assertEquals(ScanFilter.MAGIC_COLOR, settings.filter)
        assertEquals(1f, settings.strength, 0f)
        assertEquals(0f, settings.brightness, 0f)
        assertEquals(0f, settings.contrast, 0f)
        assertEquals(0f, settings.sharpness, 0f)
    }

    @Test
    fun `a stored value outside the slider range is clamped, not passed through`() {
        val settings = PageLook(
            strength = 7f,
            brightness = -4f,
            contrast = 9f,
            sharpness = -2f,
        ).toSettings(quad = null, rotationDegrees = 0, deskewDegrees = 0.0, maxOutputEdge = 1200)

        assertEquals(1f, settings.strength, 0f)
        assertEquals(-1f, settings.brightness, 0f)
        assertEquals(1f, settings.contrast, 0f)
        assertEquals(0f, settings.sharpness, 0f)
    }

    @Test
    fun `settings read back as the look they were rendered at`() {
        val look = PageLook(
            filter = ScanFilter.GRAYSCALE,
            strength = 0.8f,
            brightness = 0.1f,
            contrast = -0.25f,
            sharpness = 0.5f,
        )

        val roundTrip = look.toSettings(null, 0, 0.0, 2600).toLook()

        assertEquals(look, roundTrip)
    }

    @Test
    fun `no outline stays no outline`() {
        val missing: Quad? = null

        assertNull(missing.inFrame(100, 200))
    }

    @Test
    fun `a frame-sized outline is dropped rather than resampled`() {
        assertNull(Quad.rect(1.0, 1.0).inFrame(1200, 1600))
        assertNull(Quad.rect(1.0, 0.9999).inFrame(1200, 1600))
    }

    @Test
    fun `an outline in fractions is scaled into the frame it is rendered at`() {
        val half = Quad(
            Pt(0.0, 0.0),
            Pt(0.5, 0.0),
            Pt(0.5, 0.5),
            Pt(0.0, 0.5),
        )

        val scaled = half.inFrame(1000, 2000)

        assertNotNull(scaled)
        assertEquals(Pt(0.0, 0.0), scaled!!.tl)
        assertEquals(Pt(500.0, 1000.0), scaled.br)
        assertEquals(Pt(0.0, 1000.0), scaled.bl)
    }

    @Test
    fun `the same stored outline lands on the same fraction of two decode sizes`() {
        val stored = Quad(
            Pt(0.1, 0.2),
            Pt(0.9, 0.2),
            Pt(0.9, 0.8),
            Pt(0.1, 0.8),
        )

        val small = stored.inFrame(2400, 1800)!!.scaled(1.0 / 2400, 1.0 / 1800)
        val large = stored.inFrame(2600, 1950)!!.scaled(1.0 / 2600, 1.0 / 1950)

        assertEquals(stored.tl.x, small.tl.x, 1e-9)
        assertEquals(stored.tl.x, large.tl.x, 1e-9)
        assertEquals(stored.br.y, small.br.y, 1e-9)
        assertEquals(stored.br.y, large.br.y, 1e-9)
    }
}