package io.github.pointandyshoot.scopecam

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.tan

internal fun IntArray?.orEmpty(): IntArray = this ?: intArrayOf()

/** Pure policy: camera IDs have no assumed meaning. Field of view normalises sensor sizes. */
data class LensSpec(val id: String, val focalMm: Double, val sensorWidthMm: Double) {
    val fieldOfView: Double get() = 2 * atan(sensorWidthMm / (2 * focalMm))
}
data class LensPair(val wide: LensSpec, val tele: LensSpec)
fun selectLenses(lenses: List<LensSpec>): LensPair? {
    val valid = lenses.filter { it.focalMm > 0 && it.sensorWidthMm > 0 }
    // Camera marketing FOV is usually diagonal; this calculation is horizontal.
    val wide = valid.minByOrNull { abs(it.fieldOfView - Math.toRadians(70.0)) } ?: return null
    val tele = valid.minByOrNull { it.fieldOfView } ?: return null
    if (tele.id == wide.id || tan(wide.fieldOfView / 2) / tan(tele.fieldOfView / 2) < 1.8) return null
    return LensPair(wide, tele)
}
data class VideoMode(val width: Int, val height: Int, val fps: Int) {
    val label: String get() = "${if (width == 3840) "4K" else "${height}p"} · $fps fps"
    val key: String get() = "${width}x${height}@$fps"
}
val preferredModes = listOf(VideoMode(1920, 1080, 30), VideoMode(3840, 2160, 30), VideoMode(1920, 1080, 60))
fun modeFits(mode: VideoMode, minimumFrameNs: Long, fpsRanges: List<IntRange>): Boolean =
    minimumFrameNs > 0 && minimumFrameNs <= 1_000_000_000L / mode.fps + 1 && fpsRanges.any { mode.fps in it }

data class Stabilisation(val video: Int, val ois: Int, val label: String)
/** PREVIEW_STABILIZATION lets the HAL coordinate OIS; never force competing OIS + EIS ON. */
fun chooseStabilisation(videoModes: Set<Int>, oisModes: Set<Int>, mode: VideoMode, opticalOnly: Boolean): Stabilisation {
    if (!opticalOnly && mode.fps <= 30 && mode.width <= 1920) {
        if (2 in videoModes) return Stabilisation(2, 0, "Platform preview + video")
        if (1 in videoModes) return Stabilisation(1, 0, "Electronic video")
    }
    return if (1 in oisModes) Stabilisation(0, 1, "Optical") else Stabilisation(0, 0, "Unavailable")
}
data class Calibration(val x: Float = .5f, val y: Float = .5f) {
    fun bounded() = Calibration(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}
fun rotatePoint(point: Calibration, clockwiseDegrees: Int): Calibration = when ((clockwiseDegrees + 360) % 360) {
    90 -> Calibration(1f - point.y, point.x)
    180 -> Calibration(1f - point.x, 1f - point.y)
    270 -> Calibration(point.y, 1f - point.x)
    else -> point
}
/** Convert a centre-cropped preview coordinate to/from the full sensor coordinate. */
fun sensorPoint(point: Calibration, sensorAspect: Float, streamAspect: Float): Calibration {
    val width = (streamAspect / sensorAspect).coerceAtMost(1f)
    val height = (sensorAspect / streamAspect).coerceAtMost(1f)
    return Calibration(.5f + (point.x - .5f) * width, .5f + (point.y - .5f) * height)
}
fun previewPoint(point: Calibration, sensorAspect: Float, streamAspect: Float): Calibration {
    val width = (streamAspect / sensorAspect).coerceAtMost(1f)
    val height = (sensorAspect / streamAspect).coerceAtMost(1f)
    return Calibration(.5f + (point.x - .5f) / width, .5f + (point.y - .5f) / height)
}
/** Width is approximate: eyepiece apparent FOV, parallax and EIS crop need device testing. */
fun finderFraction(wideFov: Double, teleFov: Double, monocular: Double = 10.0): Float =
    (tan(teleFov / 2) / (tan(wideFov / 2) * monocular)).toFloat().coerceIn(.005f, 1f)

enum class OutputRole { PRIMARY_PREVIEW, FINDER, RECORDING }
data class StreamBinding(val role: OutputRole, val physicalId: String)
fun safeBindings(teleId: String, wideId: String?, finder: Boolean, recording: Boolean): List<StreamBinding> {
    require(!finder || (wideId != null && wideId != teleId))
    return buildList {
        add(StreamBinding(OutputRole.PRIMARY_PREVIEW, teleId))
        if (finder) add(StreamBinding(OutputRole.FINDER, requireNotNull(wideId)))
        if (recording) add(StreamBinding(OutputRole.RECORDING, teleId))
    }
}
data class RegionBox(val left: Int, val top: Int, val right: Int, val bottom: Int)
fun centreRegion(left: Int, top: Int, width: Int, height: Int, fraction: Float = .2f): RegionBox {
    require(width > 0 && height > 0)
    val w = (width * fraction.coerceIn(.001f, 1f)).toInt().coerceAtLeast(1)
    val h = (height * fraction.coerceIn(.001f, 1f)).toInt().coerceAtLeast(1)
    val x = left + (width - w) / 2
    val y = top + (height - h) / 2
    return RegionBox(x, y, x + w, y + h)
}
