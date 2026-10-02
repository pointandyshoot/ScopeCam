package io.github.pointandyshoot.scopecam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.CameraCharacteristics as CC
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor

enum class ScopeState { TARGET, ACQUIRE, CAPTURE }
data class CameraStatus(
    val ready: Boolean = false,
    val busy: Boolean = false,
    val recording: Boolean = false,
    val finderLive: Boolean = false,
    val state: ScopeState = ScopeState.TARGET,
    val message: String = "Opening telephoto…",
    val focus: String = "Waiting",
    val details: String = "",
    val saved: Uri? = null
)

/** All camera mutations run on one worker. Reconfiguration waits for CameraDevice.onClosed. */
class CameraController(
    private val context: Context,
    private val primary: PreviewPane,
    private val finder: PreviewPane,
    private val inventoryCallback: (CameraInventory) -> Unit,
    private val statusCallback: (CameraStatus) -> Unit
) {
    private val thread = HandlerThread("ScopeCamera").apply { start() }
    private val worker = Handler(thread.looper)
    private val ui = Handler(Looper.getMainLooper())
    private val executor = Executor { worker.post(it) }
    private val manager = context.getSystemService(CameraManager::class.java)
    private val recorder: VideoRecorder = VideoRecorder(context) { reason ->
        worker.post {
            if (recordWanted || recorder.started) {
                running = false; recordWanted = false; closeDevice(false)
                publish("$reason; invalid output removed. Retry.", false, false)
            }
        }
    }
    private var inventory: CameraInventory? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    private var opening = false
    private var closing = false
    @Volatile private var running = false
    private var destroyed = false
    @Volatile private var token = 0
    private var state = ScopeState.TARGET
    private var finderWanted = true
    private var recordWanted = false
    private var audioWanted = true
    private var orientation = 0
    private var mode = preferredModes.first()
    private var opticalOnly = false
    private var continuous = false
    private var exposure = 0
    private var distance: Float? = null
    private var afPending = false
    private var afTriggerSeen = false
    private var focusText = "Waiting"
    private var infinity = false
    private var attempts = mutableListOf<String>()
    private var fallback = 0 // 0 PRIVATE pair; 1 equal-sized YUV pair; 2 tele only
    private var forceOptical = false
    private var liveFinder = false
    private var yuv = false
    private var surfaces = mutableListOf<Surface>()
    private var readers = mutableListOf<ImageReader>()
    private var recordingSurface: Surface? = null
    private var lastStatus = CameraStatus()
    private var reported = "No capture results yet"
    private var frames = 0
    private var lastFrameMs = 0L
    private var lastUiMs = 0L
    private var captureFailures = 0
    private var stabilisation = Stabilisation(0, 0, "Unavailable")
    private var savedUri: Uri? = null

    fun start(modeKey: String?, finderOn: Boolean, oisOnly: Boolean, centreContinuous: Boolean, rotationDegrees: Int) = worker.post {
        if (destroyed) return@post
        running = true; orientation = rotationDegrees
        finderWanted = finderOn; opticalOnly = oisOnly; continuous = centreContinuous
        try {
            if (inventory == null) {
                recorder.clearAbandonedPending()
                val found = CameraDiscovery.discover(manager)
                inventory = found
                mode = found.modes.find { it.key == modeKey } ?: found.modes.firstOrNull() ?: preferredModes.first()
                ui.post { inventoryCallback(found) }
            }
            reopen()
        } catch (e: Exception) { publish("Camera discovery failed: ${e.message}", false, false) }
    }

    fun pause() = worker.post {
        running = false; recordWanted = false; state = ScopeState.ACQUIRE
        closeDevice(save = recorder.started)
        distance = null; infinity = false; focusText = "Waiting"
    }

    fun destroy() = worker.post {
        destroyed = true; running = false; recordWanted = false
        closeDevice(save = recorder.started)
        if (!closing && !opening) thread.quitSafely()
    }

    fun target(wanted: Boolean) = worker.post {
        if (recordWanted || recorder.started) return@post
        state = if (wanted) ScopeState.TARGET else ScopeState.ACQUIRE
        finderWanted = wanted
        reopen()
    }
    fun finder(wanted: Boolean) = worker.post {
        // Never interrupt an active file to probe another camera. Choose before Record.
        if (recordWanted || recorder.started) return@post
        finderWanted = wanted; reopen()
    }
    fun selectMode(value: VideoMode) = worker.post {
        if (recordWanted || value !in inventory?.modes.orEmpty()) return@post
        mode = value; reopen()
    }
    fun setFocusBehaviour(value: Boolean) = worker.post {
        continuous = value; infinity = false; distance = null; afPending = false
        if (session != null) refocusInternal()
    }
    fun setOpticalOnly(value: Boolean) = worker.post {
        if (!recordWanted) { opticalOnly = value; reopen() }
    }
    fun setExposure(value: Int) = worker.post {
        exposure = value; updateRepeating()
    }
    fun displayRotation(value: Int) = worker.post {
        orientation = value
        val info = inventory ?: return@post
        ui.post {
            primary.rotatePreview(((info.tele[CC.SENSOR_ORIENTATION] ?: 90) - value + 360) % 360)
            finder.rotatePreview(((info.wide[CC.SENSOR_ORIENTATION] ?: 90) - value + 360) % 360)
        }
    }
    fun refocus() = worker.post { refocusInternal() }
    fun infinity() = worker.post {
        val c = inventory?.tele ?: return@post
        if (CaptureRequest.CONTROL_AF_MODE_OFF !in c[CC.CONTROL_AF_AVAILABLE_MODES].orEmpty()) return@post
        if (inventory?.physicalKeys?.contains(CaptureRequest.LENS_FOCUS_DISTANCE) != true) {
            publish("Independent telephoto focus distance is unavailable; use Refocus.", session != null, false)
            return@post
        }
        infinity = true; distance = 0f; afPending = false
        focusText = "Infinity · Refocus to leave"; updateRepeating()
    }

    fun record(keepFinder: Boolean, audio: Boolean, rotationDegrees: Int) = worker.post {
        if (session == null || recordWanted || mode !in inventory?.modes.orEmpty()) return@post
        recordWanted = true; finderWanted = keepFinder; audioWanted = audio
        orientation = rotationDegrees
        reopen()
    }
    fun stop() = worker.post {
        if (!recordWanted && !recorder.started) return@post
        recordWanted = false; state = ScopeState.ACQUIRE; finderWanted = false
        closeDevice(save = true)
    }

    private fun reopen() {
        fallback = 0; forceOptical = false; attempts.clear()
        if (device != null || opening || closing) closeDevice(false) else openDevice()
    }

    private fun closeDevice(save: Boolean) {
        token++
        afPending = false
        runCatching { session?.stopRepeating() }
        runCatching { session?.abortCaptures() }
        session?.close(); session = null; request = null
        if (recorder.prepared) {
            savedUri = recorder.finish(save)
            recordingSurface = null
        }
        publish(if (savedUri != null) "Saved to Movies/ScopeCam" else "Reconfiguring…", false, running)
        val camera = device
        if (camera != null && !closing) { closing = true; camera.close() }
        else if (camera == null && !opening && !closing) {
            releaseOutputs()
            if (running && !destroyed) openDevice()
            else if (destroyed) thread.quitSafely()
        }
    }

    @Suppress("MissingPermission")
    private fun openDevice() {
        val info = inventory ?: return
        if (!running || destroyed || opening || closing) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            publish("Camera permission is required. Grant it in Settings or retry permissions.", false, false); return
        }
        opening = true
        val openToken = token
        publish("Opening physical telephoto ${info.pair.tele.id}…", false, true)
        try {
            manager.openCamera(info.logicalId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opening = false; device = camera
                    if (openToken != token || !running || destroyed) { closing = true; camera.close(); return }
                    configure()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    cameraFault(camera, "Camera disconnected")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    cameraFault(camera, "Camera error $error; resume or retry")
                }
                override fun onClosed(camera: CameraDevice) {
                    if (device == camera) device = null
                    opening = false; closing = false
                    releaseOutputs()
                    if (running && !destroyed) openDevice()
                    else if (destroyed) thread.quitSafely()
                }
            })
        } catch (e: Exception) { opening = false; publish("Open failed: ${e.message}", false, false) }
    }

    private fun cameraFault(camera: CameraDevice, reason: String) {
        opening = false; device = camera; running = false; recordWanted = false
        closeDevice(save = recorder.started)
        publish(reason, false, false)
    }

    private fun releaseOutputs() {
        readers.forEach { it.close() }; readers.clear()
        surfaces.forEach { it.release() }; surfaces.clear()
        // Recorder owns and releases its Surface; never put it in surfaces.
        recordingSurface = null
        ui.post { primary.clearYuv(); finder.clearYuv() }
    }

    private fun configure() {
        val info = inventory ?: return
        val camera = device ?: return
        val configToken = ++token
        liveFinder = finderWanted && fallback < 2
        yuv = liveFinder && fallback == 1
        frames = 0; captureFailures = 0; lastFrameMs = SystemClock.elapsedRealtime()
        stabilisation = chooseStabilisation(info.tele[CC.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES].orEmpty().toSet(),
            info.tele[CC.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION].orEmpty().toSet(), mode, opticalOnly || forceOptical)
        try {
            if (recordWanted) recorder.prepare(mode, ((info.tele[CC.SENSOR_ORIENTATION] ?: 90) - orientation + 360) % 360, audioWanted)
            val bindings = safeBindings(info.pair.tele.id, info.pair.wide.id, liveFinder, recordWanted)
            val outputs = bindings.map { binding ->
                val surface = when (binding.role) {
                    OutputRole.RECORDING -> recorder.surface.also { recordingSurface = it }
                    OutputRole.PRIMARY_PREVIEW -> previewSurface(primary, info.preview, info.tele, yuv)
                    OutputRole.FINDER -> previewSurface(finder, info.finderPreview, info.wide, yuv)
                }
                OutputConfiguration(surface).apply { setPhysicalCameraId(binding.physicalId) }
            }
            val ids = bindings.map { it.physicalId }.toSet()
            val builder = camera.createCaptureRequest(if (recordWanted) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW, ids)
            outputs.forEach { builder.addTarget(requireNotNull(it.surface)) }
            request = builder
            afPending = false
            applyControls(builder)
            val configuration = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(value: CameraCaptureSession) {
                        if (configToken != token || !running || destroyed || closing) { value.close(); return }
                        session = value
                        try {
                            updateRepeating()
                            if (recordWanted) { recorder.start(); state = ScopeState.CAPTURE }
                            attempts.add("Accepted ${if (recordWanted) mode.key else "preview"}: ${if (liveFinder) if (yuv) "YUV pair" else "PRIVATE pair" else "tele only"}")
                            publish(if (recordWanted) "Recording telephoto only" else if (finderWanted && !liveFinder) "Finder unavailable: using locked telephoto" else "Physical telephoto locked", true, false)
                            if (distance == null && !infinity) refocusInternal()
                            else focusText = if (infinity) "Infinity · Refocus to leave" else "Physical focus position held"
                            watchFrames(configToken)
                        } catch (e: Exception) { configurationFailed(configToken, "Start: ${e.message}") }
                    }
                    override fun onConfigureFailed(value: CameraCaptureSession) {
                        value.close(); configurationFailed(configToken, "HAL rejected stream combination")
                    }
                })
            configuration.sessionParameters = builder.build()
            val supported = runCatching { camera.isSessionConfigurationSupported(configuration) }.getOrNull()
            attempts.add("Probe: ${if (liveFinder) if (yuv) "YUV pair" else "PRIVATE pair" else "tele only"}, record=$recordWanted, $supported (null=unsupported query)")
            if (supported == false) configurationFailed(configToken, "Session support query rejected combination")
            else camera.createCaptureSession(configuration)
        } catch (e: Exception) { configurationFailed(configToken, "Configure: ${e.message}") }
    }

    private fun configurationFailed(configToken: Int, reason: String) {
        if (configToken != token || !running || destroyed) return
        attempts.add(reason)
        if (finderWanted && fallback == 0 && inventory?.yuvPairSize != null) fallback = 1
        else if (finderWanted && fallback < 2) fallback = 2
        else if (!forceOptical && stabilisation.video != 0) forceOptical = true
        else {
            recordWanted = false; running = false
            closeDevice(false)
            publish("Session failed: $reason. Try a different video mode or Retry.", false, false)
            return
        }
        closeDevice(false)
    }

    private fun previewSurface(pane: PreviewPane, size: Size, characteristics: CC, asYuv: Boolean): Surface {
        val sensor = characteristics[CC.SENSOR_ORIENTATION] ?: 90
        characteristics[CC.SENSOR_INFO_ACTIVE_ARRAY_SIZE]?.let { pane.sensorAspect(it.width().toFloat() / it.height()) }
        if (!asYuv) return pane.surface(size, (sensor - orientation + 360) % 360).also { surfaces.add(it) }
        val yuvSize = requireNotNull(inventory?.yuvPairSize)
        val reader = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 3)
        readers.add(reader)
        var lastImage = 0L
        reader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            image.use {
                val now = SystemClock.elapsedRealtime()
                if (now - lastImage < 100 || !running) return@use
                lastImage = now
                val bitmap = runCatching { YuvConverter.toBitmap(it) }.getOrNull() ?: return@use
                val imageToken = token
                ui.post { if (imageToken == token && running) pane.showYuv(bitmap, (sensor - orientation + 360) % 360) else bitmap.recycle() }
            }
        }, worker)
        return reader.surface
    }

    private fun <T> control(builder: CaptureRequest.Builder, key: CaptureRequest.Key<T>, value: T) {
        val info = inventory ?: return
        if (key in info.logical.availableCaptureRequestKeys.orEmpty()) builder.set(key, value)
        if (key in info.physicalKeys) builder.setPhysicalCameraKey(key, value, info.pair.tele.id)
    }

    private fun applyControls(builder: CaptureRequest.Builder) {
        val info = inventory ?: return
        val tele = info.tele
        control(builder, CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        control(builder, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        control(builder, CaptureRequest.CONTROL_AE_LOCK, false)
        val afModes = tele[CC.CONTROL_AF_AVAILABLE_MODES].orEmpty()
        val manual = infinity || (distance != null && CaptureRequest.LENS_FOCUS_DISTANCE in info.physicalKeys)
        val afMode = when {
            manual -> CaptureRequest.CONTROL_AF_MODE_OFF
            continuous && CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            CaptureRequest.CONTROL_AF_MODE_AUTO in afModes -> CaptureRequest.CONTROL_AF_MODE_AUTO
            else -> afModes.firstOrNull() ?: CaptureRequest.CONTROL_AF_MODE_OFF
        }
        control(builder, CaptureRequest.CONTROL_AF_MODE, afMode)
        if (manual) control(builder, CaptureRequest.LENS_FOCUS_DISTANCE, distance ?: 0f)
        control(builder, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        fun region(c: CC, fraction: Float = .2f): Array<MeteringRectangle> {
            val active = c[CC.SENSOR_INFO_ACTIVE_ARRAY_SIZE] ?: Rect(0, 0, 1, 1)
            val box = centreRegion(active.left, active.top, active.width(), active.height(), fraction)
            return arrayOf(MeteringRectangle(Rect(box.left, box.top, box.right, box.bottom), MeteringRectangle.METERING_WEIGHT_MAX))
        }
        // Global keys use logical coordinates. Physical overrides use the telephoto array.
        val logicalFocal = info.logical[CC.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull()?.toDouble()
        val logicalWidth = info.logical[CC.SENSOR_INFO_PHYSICAL_SIZE]?.width?.toDouble()
        val logicalFov = if (logicalFocal != null && logicalWidth != null && logicalFocal > 0)
            LensSpec(info.logicalId, logicalFocal, logicalWidth).fieldOfView else info.pair.wide.fieldOfView
        // A 20% region on the main/logical sensor would cover most of a 5× telephoto
        // image. Scale the central logical region down by the telephoto/main FOV ratio.
        val logicalFraction = (.2 * kotlin.math.tan(info.pair.tele.fieldOfView / 2) /
            kotlin.math.tan(logicalFov / 2)).toFloat().coerceIn(.001f, .2f)
        if ((info.logical[CC.CONTROL_MAX_REGIONS_AF] ?: 0) > 0) builder.set(CaptureRequest.CONTROL_AF_REGIONS, region(info.logical, logicalFraction))
        if ((info.logical[CC.CONTROL_MAX_REGIONS_AE] ?: 0) > 0) builder.set(CaptureRequest.CONTROL_AE_REGIONS, region(info.logical, logicalFraction))
        if ((tele[CC.CONTROL_MAX_REGIONS_AF] ?: 0) > 0 && CaptureRequest.CONTROL_AF_REGIONS in info.physicalKeys)
            builder.setPhysicalCameraKey(CaptureRequest.CONTROL_AF_REGIONS, region(tele), info.pair.tele.id)
        if ((tele[CC.CONTROL_MAX_REGIONS_AE] ?: 0) > 0 && CaptureRequest.CONTROL_AE_REGIONS in info.physicalKeys)
            builder.setPhysicalCameraKey(CaptureRequest.CONTROL_AE_REGIONS, region(tele), info.pair.tele.id)
        val exposureRange = tele[CC.CONTROL_AE_COMPENSATION_RANGE]
        if (exposureRange != null) control(builder, CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposure.coerceIn(exposureRange.lower, exposureRange.upper))
        val requestedFps = if (recordWanted) mode.fps else 30
        val fps = info.logical[CC.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES].orEmpty()
            .filter { it.contains(requestedFps) }.minByOrNull { it.upper - it.lower }
        if (fps != null) builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
        if (CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE in info.physicalKeys) {
            tele[CC.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES].orEmpty().filter { it.contains(requestedFps) }
                .minByOrNull { it.upper - it.lower }?.let {
                    builder.setPhysicalCameraKey(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it, info.pair.tele.id)
                }
        }
        control(builder, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stabilisation.video)
        control(builder, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, stabilisation.ois)
        // No logical zoom/crop requests; explicit physical outputs select lenses, not zoom.
        if (CaptureRequest.SCALER_CROP_REGION in info.physicalKeys)
            tele[CC.SENSOR_INFO_ACTIVE_ARRAY_SIZE]?.let { builder.setPhysicalCameraKey(CaptureRequest.SCALER_CROP_REGION, it, info.pair.tele.id) }
    }

    private fun updateRepeating() {
        val s = session ?: return
        val builder = request ?: return
        try { applyControls(builder); s.setRepeatingRequest(builder.build(), captureCallback, worker) }
        catch (e: Exception) { publish("Control update failed: ${e.message}", true, false) }
    }

    private fun refocusInternal() {
        val s = session ?: return
        val builder = request ?: return
        val info = inventory ?: return
        distance = null; infinity = false; afPending = false
        try {
            applyControls(builder)
            if (continuous) { focusText = "Continuous centre AF"; updateRepeating(); return }
            if (CaptureRequest.CONTROL_AF_MODE_AUTO !in info.tele[CC.CONTROL_AF_AVAILABLE_MODES].orEmpty()) {
                focusText = "One-shot AF unavailable"; updateRepeating(); return
            }
            control(builder, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            s.capture(builder.build(), null, worker)
            control(builder, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            s.capture(builder.build(), captureCallback, worker)
            control(builder, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            afPending = true; afTriggerSeen = false; focusText = "Focusing centre…"; updateRepeating()
            val focusToken = token
            worker.postDelayed({
                if (focusToken == token && afPending) {
                    afPending = false; focusText = "AF timed out · tap Refocus"
                    publish(lastStatus.message, session != null, false)
                }
            }, 4500)
        } catch (e: Exception) { afPending = false; focusText = "AF failed: ${e.message}"; publish(lastStatus.message, true, false) }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(current: CameraCaptureSession, capture: CaptureRequest, result: TotalCaptureResult) {
            if (current != session) return
            val info = inventory ?: return
            frames++; captureFailures = 0; lastFrameMs = SystemClock.elapsedRealtime()
            val physical = result.physicalCameraResults[info.pair.tele.id]
            val metadata = physical ?: result
            val af = metadata[CaptureResult.CONTROL_AF_STATE]
            val lens = physical?.get(CaptureResult.LENS_FOCUS_DISTANCE)
            if (afPending && capture[CaptureRequest.CONTROL_AF_TRIGGER] == CaptureRequest.CONTROL_AF_TRIGGER_START) afTriggerSeen = true
            if (afPending && afTriggerSeen && af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED) {
                afPending = false
                focusText = "Focus held${if (physical == null) " (logical AF result)" else ""}"
                if (lens != null && CaptureRequest.LENS_FOCUS_DISTANCE in info.physicalKeys) { distance = lens; updateRepeating() }
            } else if (afPending && afTriggerSeen && af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                afPending = false; focusText = "AF missed · tap Refocus"
            }
            reported = "Frames: $frames · physical results: ${result.physicalCameraResults.keys}\n" +
                "Output bindings: primary/encoder=${info.pair.tele.id}, finder=${if (liveFinder) info.pair.wide.id else "off"}\n" +
                "Logical active ID (not output binding): ${result[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID]}\n" +
                "Tele metadata: ${if (physical == null) "unavailable; below is logical except lens" else "available"}\n" +
                "AF=$af · lens=$lens dioptres · AE=${metadata[CaptureResult.CONTROL_AE_STATE]}\n" +
                "Returned OIS=${metadata[CaptureResult.LENS_OPTICAL_STABILIZATION_MODE]}, video stab=${metadata[CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE]}\n" +
                "Returned crop=${physical?.get(CaptureResult.SCALER_CROP_REGION)} · fps=${metadata[CaptureResult.CONTROL_AE_TARGET_FPS_RANGE]}\n" +
                "AF region=${metadata[CaptureResult.CONTROL_AF_REGIONS]?.toList()}\nAE region=${metadata[CaptureResult.CONTROL_AE_REGIONS]?.toList()}"
            if (lastFrameMs - lastUiMs > 750) { lastUiMs = lastFrameMs; publish(lastStatus.message, true, false) }
        }
        override fun onCaptureFailed(current: CameraCaptureSession, capture: CaptureRequest, failure: CaptureFailure) {
            if (current != session) return
            if (++captureFailures >= 5) {
                running = false; recordWanted = false; closeDevice(false)
                publish("Repeated camera frame failures (${failure.reason}); Retry.", false, false)
            }
        }
    }

    private fun watchFrames(configToken: Int) {
        worker.postDelayed({
            if (configToken != token || session == null || !running) return@postDelayed
            if (SystemClock.elapsedRealtime() - lastFrameMs > 5000) {
                running = false; recordWanted = false; closeDevice(false)
                publish("Camera produced no frames for 5 seconds; Retry.", false, false)
            } else watchFrames(configToken)
        }, 2000)
    }

    private fun publish(message: String, ready: Boolean, busy: Boolean) {
        val info = inventory
        val details = "ScopeCam ${BuildConfig.VERSION_NAME} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · API ${android.os.Build.VERSION.SDK_INT}\n\n" +
            (info?.report ?: "No camera inventory") + "\nSession attempts:\n${attempts.joinToString("\n")}\n\n" +
            "Requested stabilisation: ${stabilisation.label} · OIS=${stabilisation.ois} · video=${stabilisation.video}\n" +
            "Central AF/AE: 20% tele width/height; physical overrides where supported; otherwise FOV-scaled logical coordinates (HAL mapping needs verification).\n" +
            "Digital zoom: no added zoom. Recording mode: ${mode.key}\n" + reported
        lastStatus = CameraStatus(ready, busy, recorder.started, liveFinder && ready, state, message, focusText, details, savedUri)
        val snapshot = lastStatus
        ui.post { statusCallback(snapshot) }
    }
}

/** Only used for the lower-bandwidth fallback. Handles padded rows and interleaved UV planes. */
object YuvConverter {
    fun toBitmap(image: android.media.Image): Bitmap {
        val width = image.width; val height = image.height
        val planes = image.planes
        val pixels = IntArray(width * height)
        val y = planes[0]; val u = planes[1]; val v = planes[2]
        val yBase = y.buffer.position(); val uBase = u.buffer.position(); val vBase = v.buffer.position()
        for (row in 0 until height) for (col in 0 until width) {
            val luma = (y.buffer.get(yBase + row * y.rowStride + col * y.pixelStride).toInt() and 255) - 16
            val cb = (u.buffer.get(uBase + row / 2 * u.rowStride + col / 2 * u.pixelStride).toInt() and 255) - 128
            val cr = (v.buffer.get(vBase + row / 2 * v.rowStride + col / 2 * v.pixelStride).toInt() and 255) - 128
            val r = ((298 * luma + 409 * cr + 128) shr 8).coerceIn(0, 255)
            val g = ((298 * luma - 100 * cb - 208 * cr + 128) shr 8).coerceIn(0, 255)
            val b = ((298 * luma + 516 * cb + 128) shr 8).coerceIn(0, 255)
            pixels[row * width + col] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
