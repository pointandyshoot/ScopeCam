package io.github.pointandyshoot.scopecam

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics as CC
import android.hardware.camera2.CameraManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Size

data class CameraInventory(
    val logicalId: String,
    val logical: CC,
    val lenses: Map<String, CC>,
    val pair: LensPair,
    val modes: List<VideoMode>,
    val preview: Size,
    val finderPreview: Size,
    val yuvPairSize: Size?,
    val report: String
) {
    val tele: CC get() = lenses.getValue(pair.tele.id)
    val wide: CC get() = lenses.getValue(pair.wide.id)
    val physicalKeys get() = logical.availablePhysicalCameraRequestKeys.orEmpty().toSet()
}

object CameraDiscovery {
    fun discover(manager: CameraManager): CameraInventory {
        val diagnostics = StringBuilder()
        val candidates = manager.cameraIdList.mapNotNull { id ->
            val logical = manager.getCameraCharacteristics(id)
            if (logical[CC.LENS_FACING] != CC.LENS_FACING_BACK) return@mapNotNull null
            val physical = logical.physicalCameraIds.associateWith { manager.getCameraCharacteristics(it) }
            diagnostics.append("Rear $id · physical ${physical.keys}\n")
            val specs = physical.mapNotNull { (pid, c) ->
                val focal = c[CC.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull()?.toDouble()
                val sensor = c[CC.SENSOR_INFO_PHYSICAL_SIZE]?.width?.toDouble()
                diagnostics.append("  $pid · focal ${c[CC.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.toList()} mm · sensor ${c[CC.SENSOR_INFO_PHYSICAL_SIZE]}\n")
                if (focal != null && sensor != null) LensSpec(pid, focal, sensor) else null
            }
            val pair = selectLenses(specs) ?: return@mapNotNull null
            Triple(id, logical, Pair(physical, pair))
        }
        val candidate = candidates.maxByOrNull { (_, _, values) ->
            values.second.wide.fieldOfView / values.second.tele.fieldOfView
        } ?: error("No selectable rear telephoto/main physical-camera pair was exposed. ScopeCam will not substitute a logical 1× stream.")
        val (id, logical, values) = candidate
        val (physical, pair) = values
        val tele = physical.getValue(pair.tele.id)
        val wide = physical.getValue(pair.wide.id)
        val map = tele[CC.SCALER_STREAM_CONFIGURATION_MAP] ?: error("Telephoto stream map unavailable")
        val wideMap = wide[CC.SCALER_STREAM_CONFIGURATION_MAP] ?: error("Main stream map unavailable")
        val sizes = map.getOutputSizes(MediaRecorder::class.java).orEmpty().toList()
        val ranges = tele[CC.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES].orEmpty().map { it.lower..it.upper }
        val logicalRanges = logical[CC.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES].orEmpty().map { it.lower..it.upper }
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && it.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC) }
        val modes = preferredModes.filter { mode ->
            val size = Size(mode.width, mode.height)
            size in sizes && modeFits(mode, map.getOutputMinFrameDuration(MediaRecorder::class.java, size), ranges) &&
                logicalRanges.any { mode.fps in it } && encoders.any { codec ->
                    runCatching { codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities?.areSizeAndRateSupported(mode.width, mode.height, mode.fps.toDouble()) == true }.getOrDefault(false)
                }
        }
        fun previewSize(c: CC): Size {
            val outputs = c[CC.SCALER_STREAM_CONFIGURATION_MAP]?.getOutputSizes(SurfaceTexture::class.java).orEmpty().toList()
            return outputs.filter { it.width <= 1920 && it.height <= 1080 }.minByOrNull {
                kotlin.math.abs(it.width.toFloat() / it.height - 16f / 9f) * 10000 + kotlin.math.abs(it.width - 1280)
            } ?: outputs.minByOrNull { it.width * it.height } ?: error("Physical preview output unavailable")
        }
        val commonYuv = map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty().toSet()
            .intersect(wideMap.getOutputSizes(ImageFormat.YUV_420_888).orEmpty().toSet())
            .filter { it.width <= 640 && it.height <= 480 }
            .maxByOrNull { it.width * it.height }
        diagnostics.append("Selected logical $id · main ${pair.wide.id} · tele ${pair.tele.id}\n")
        diagnostics.append("Tele FOV ≈ ${Math.toDegrees(pair.tele.fieldOfView).toInt()}° · main ≈ ${Math.toDegrees(pair.wide.fieldOfView).toInt()}°\n")
        diagnostics.append("Candidate H.264 modes: ${modes.joinToString { it.key }}\nThese pass stream-duration/FPS/encoder queries; session acceptance still needs testing.\n")
        diagnostics.append("Physical recorder sizes: $sizes\nTele FPS: $ranges · logical FPS: $logicalRanges\n")
        diagnostics.append("Tele OIS modes: ${tele[CC.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]?.toList()}\n")
        diagnostics.append("Tele video stabilisation modes: ${tele[CC.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES]?.toList()} (0 off, 1 video, 2 preview+video)\n")
        diagnostics.append("AF modes: ${tele[CC.CONTROL_AF_AVAILABLE_MODES]?.toList()} · minimum lens distance: ${tele[CC.LENS_INFO_MINIMUM_FOCUS_DISTANCE]}\n")
        diagnostics.append("Tele max AF/AE regions: ${tele[CC.CONTROL_MAX_REGIONS_AF]}/${tele[CC.CONTROL_MAX_REGIONS_AE]}\n")
        diagnostics.append("Physical request keys: ${logical.availablePhysicalCameraRequestKeys?.joinToString { it.name }}\n")
        diagnostics.append("YUV pair fallback: $commonYuv\nConcurrent independent camera sets: ${if (android.os.Build.VERSION.SDK_INT >= 30) manager.concurrentCameraIds else "API < 30"}\n")
        return CameraInventory(id, logical, physical, pair, modes, previewSize(tele), previewSize(wide), commonYuv, diagnostics.toString())
    }
}
