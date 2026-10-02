package io.github.pointandyshoot.scopecam

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test fun choosesByFieldOfViewNotRawFocalLengthOrId() {
        val pair = selectLenses(listOf(LensSpec("main", 6.0, 10.0), LensSpec("ultra", 2.0, 7.0), LensSpec("scope", 8.0, 3.3)))!!
        assertEquals("main", pair.wide.id); assertEquals("scope", pair.tele.id)
    }
    @Test fun rejectsPhonesWithoutDistinctTelephoto() {
        assertNull(selectLenses(listOf(LensSpec("a", 4.0, 6.0), LensSpec("b", 2.0, 6.0))))
        assertNull(selectLenses(emptyList()))
    }
    @Test fun horizontalFieldOfViewDoesNotMistakeUltrawideForMain() {
        val pair = selectLenses(listOf(LensSpec("main", 5.38, 6.4), LensSpec("ultra", 2.13, 4.8), LensSpec("tele", 10.96, 4.1)))!!
        assertEquals("main", pair.wide.id); assertEquals("tele", pair.tele.id)
    }
    @Test fun allRecordingCombinationsKeepEncoderOnTelephoto() {
        for (finder in listOf(false, true)) {
            val bindings = safeBindings("tele", "wide", finder, true)
            assertEquals("tele", bindings.single { it.role == OutputRole.RECORDING }.physicalId)
            assertEquals("tele", bindings.single { it.role == OutputRole.PRIMARY_PREVIEW }.physicalId)
            assertEquals(if (finder) 1 else 0, bindings.count { it.physicalId == "wide" })
        }
    }
    @Test(expected = IllegalArgumentException::class) fun cannotUseTelephotoAsFinder() { safeBindings("t", "t", true, true) }
    @Test fun frameDurationAndRangeBothLimitModes() {
        val sixty = VideoMode(1920, 1080, 60)
        assertFalse(modeFits(sixty, 33_333_333, listOf(30..60)))
        assertFalse(modeFits(sixty, 16_666_666, listOf(30..30)))
        assertTrue(modeFits(sixty, 16_666_666, listOf(30..60)))
        assertFalse(modeFits(sixty, 0, listOf(30..60)))
    }
    @Test fun stabilisationDoesNotForceCompetingOisAndEis() {
        val hd = VideoMode(1920, 1080, 30)
        assertEquals(Stabilisation(2, 0, "Platform preview + video"), chooseStabilisation(setOf(0, 1, 2), setOf(0, 1), hd, false))
        assertEquals(1, chooseStabilisation(setOf(0, 1), setOf(0, 1), hd, false).video)
        for (mode in listOf(VideoMode(3840, 2160, 30), VideoMode(1920, 1080, 60))) {
            assertEquals(0, chooseStabilisation(setOf(0, 1, 2), setOf(0, 1), mode, false).video)
            assertEquals(1, chooseStabilisation(setOf(0, 1, 2), setOf(0, 1), mode, false).ois)
        }
        assertEquals(0, chooseStabilisation(setOf(0, 1, 2), setOf(0, 1), hd, true).video)
    }
    @Test fun meteringAvoidsBordersAndRespectsOffsetArrays() {
        assertEquals(RegionBox(810, 620, 1210, 920), centreRegion(10, 20, 2000, 1500))
        assertEquals(RegionBox(0, 0, 1, 1), centreRegion(0, 0, 1, 1))
    }
    @Test fun calibrationRotationRoundTripsAcrossOrientations() {
        val point = Calibration(.2f, .7f)
        for (rotation in listOf(0, 90, 180, 270)) {
            val actual = rotatePoint(rotatePoint(point, rotation), -rotation)
            assertEquals(point.x, actual.x, .00001f); assertEquals(point.y, actual.y, .00001f)
        }
        assertEquals(Calibration(0f, 1f), Calibration(-2f, 3f).bounded())
    }
    @Test fun monocularReticleAccountsForExtraTenTimesMagnification() {
        val full = finderFraction(Math.toRadians(80.0), Math.toRadians(23.0), 1.0)
        val scope = finderFraction(Math.toRadians(80.0), Math.toRadians(23.0), 10.0)
        assertEquals(full / 10, scope, .00001f)
    }
    @Test fun calibrationSurvivesPrivateToYuvAspectRatioChanges() {
        val sensor = sensorPoint(Calibration(.25f, 0f), 4f / 3f, 16f / 9f)
        assertEquals(.25f, sensor.x, .00001f)
        assertEquals(.125f, sensor.y, .00001f)
        val yuv = previewPoint(sensor, 4f / 3f, 4f / 3f)
        assertEquals(.125f, yuv.y, .00001f)
        val privateAgain = previewPoint(sensor, 4f / 3f, 16f / 9f)
        assertEquals(0f, privateAgain.y, .00001f)
    }
}
