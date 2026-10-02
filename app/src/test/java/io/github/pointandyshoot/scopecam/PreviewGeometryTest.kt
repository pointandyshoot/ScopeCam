package io.github.pointandyshoot.scopecam

import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

class PreviewGeometryTest {
    private fun map(matrix: FloatArray, x: Float, y: Float) =
        Pair(matrix[0] * x + matrix[1] * y + matrix[2],
            matrix[3] * x + matrix[4] * y + matrix[5])

    private fun distance(a: Pair<Float, Float>, b: Pair<Float, Float>) = hypot(a.first - b.first, a.second - b.second)

    @Test fun pixelPortraitDoesNotApplySensorRotationTwice() {
        val geometry = previewGeometry(1280, 720, 1080, 1920, 90, 0)
        // Camera2 has already rotated the landscape buffer into exactly this portrait view.
        val topLeft = map(geometry.textureMatrix, 0f, 0f)
        val bottomRight = map(geometry.textureMatrix, 1080f, 1920f)
        assertEquals(0f, topLeft.first, .001f); assertEquals(0f, topLeft.second, .001f)
        assertEquals(1080f, bottomRight.first, .001f); assertEquals(1920f, bottomRight.second, .001f)
        // Raw sensor top-left becomes portrait top-right, rather than texture bottom-right.
        assertEquals(1080f, map(geometry.rawMatrix, 0f, 0f).first, .001f)
        assertEquals(0f, map(geometry.rawMatrix, 0f, 0f).second, .001f)
    }

    @Test fun bothLandscapeDirectionsHaveKnownCornerOrientation() {
        val normal = previewGeometry(1280, 720, 1920, 1080, 90, 90)
        val reverse = previewGeometry(1280, 720, 1920, 1080, 90, 270)
        assertEquals(0f, map(normal.rawMatrix, 0f, 0f).first, .001f)
        assertEquals(0f, map(normal.rawMatrix, 0f, 0f).second, .001f)
        assertEquals(1920f, map(reverse.rawMatrix, 0f, 0f).first, .001f)
        assertEquals(1080f, map(reverse.rawMatrix, 0f, 0f).second, .001f)
    }

    @Test fun privateAndYuvAgreeAndKeepCirclesRoundInEveryOrientation() {
        for (sensor in listOf(0, 90, 180, 270)) for (display in listOf(0, 90, 180, 270)) {
            for ((vw, vh) in listOf(1080 to 1750, 1750 to 1080, 636 to 450, 800 to 800)) {
                for ((bw, bh) in listOf(1280 to 720, 640 to 480)) {
                    val geometry = previewGeometry(bw, bh, vw, vh, sensor, display)
                    // Independently model Camera2's producer rotation and TextureView stretch.
                    fun throughTexture(x: Float, y: Float): Pair<Float, Float> {
                        val native = rotatePoint(Calibration(x / bw, y / bh), sensor)
                        return map(geometry.textureMatrix, native.x * vw, native.y * vh)
                    }
                    val samples = listOf(0f to 0f, bw.toFloat() to bh.toFloat(), bw / 2f to bh / 2f)
                    for ((x, y) in samples) {
                        val raw = map(geometry.rawMatrix, x, y)
                        val private = throughTexture(x, y)
                        assertEquals(raw.first, private.first, .001f)
                        assertEquals(raw.second, private.second, .001f)
                    }
                    val centre = throughTexture(bw / 2f, bh / 2f)
                    assertEquals(vw / 2f, centre.first, .001f)
                    assertEquals(vh / 2f, centre.second, .001f)
                    val horizontal = throughTexture(bw / 2f + 100, bh / 2f)
                    val vertical = throughTexture(bw / 2f, bh / 2f + 100)
                    assertEquals(distance(centre, horizontal), distance(centre, vertical), .001f)
                    assertTrue(geometry.contentWidth <= vw + .001f)
                    assertTrue(geometry.contentHeight <= vh + .001f)
                    assertTrue(kotlin.math.abs(geometry.contentWidth - vw) < .001f ||
                        kotlin.math.abs(geometry.contentHeight - vh) < .001f)
                }
            }
        }
    }

    @Test fun finderLetterboxesInsteadOfStretching() {
        val geometry = previewGeometry(1280, 720, 636, 450, 90, 0)
        assertEquals(253.125f, geometry.contentWidth, .001f)
        assertEquals(450f, geometry.contentHeight, .001f)
    }
}
