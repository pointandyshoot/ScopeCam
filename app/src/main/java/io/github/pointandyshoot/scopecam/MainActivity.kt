package io.github.pointandyshoot.scopecam

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.*

/** Native views keep the camera UI small and avoid a second rendering/compositing pipeline. */
class MainActivity : Activity() {
    private lateinit var primary: PreviewPane
    private lateinit var finderPane: PreviewPane
    private lateinit var controller: CameraController
    private lateinit var statusText: TextView
    private lateinit var focusText: TextView
    private lateinit var lensText: TextView
    private lateinit var targetButton: Button
    private lateinit var recordButton: Button
    private lateinit var finderButton: Button
    private lateinit var refocusButton: Button
    private lateinit var modeButton: Button
    private lateinit var calibrationButton: Button
    private lateinit var retryButton: Button
    private var inventory: CameraInventory? = null
    private var status = CameraStatus()
    private var active = false
    private var started = false
    private var requestingPermissions = false
    private var finderOn = true
    private var selectedMode: VideoMode? = null
    private var lastVideo: Uri? = null
    private val prefs by lazy { getSharedPreferences("scope", MODE_PRIVATE) }
    private var opticalOnly: Boolean
        get() = prefs.getBoolean("opticalOnly", false)
        set(value) { prefs.edit().putBoolean("opticalOnly", value).apply() }
    private var continuous: Boolean
        get() = prefs.getBoolean("continuous", false)
        set(value) { prefs.edit().putBoolean("continuous", value).apply() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        finderOn = prefs.getBoolean("finder", true)
        buildUi()
        controller = CameraController(this, primary, finderPane, { info ->
            inventory = info
            selectedMode = info.modes.find { it.key == prefs.getString("mode", null) } ?: info.modes.firstOrNull()
            modeButton.text = selectedMode?.label ?: "No video mode"
            finderPane.finderFraction = finderFraction(info.pair.wide.fieldOfView, info.pair.tele.fieldOfView)
        }, { snapshot -> updateUi(snapshot) })
        primary.onReady = { maybeStart() }; finderPane.onReady = { maybeStart() }
        primary.onLost = { if (started) { started = false; controller.pause() } }
        finderPane.onLost = { if (started) { started = false; controller.pause() } }
        finderPane.calibration = Calibration(prefs.getFloat("calibrationX", .5f), prefs.getFloat("calibrationY", .5f))
        finderPane.onCalibrate = { point ->
            finderPane.calibration = point
            prefs.edit().putFloat("calibrationX", point.x).putFloat("calibrationY", point.y).apply()
            finderPane.calibrating = false
            toast("Finder calibration saved")
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(8, 12, 16)) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(4)) }
        lensText = label("ScopeCam ${BuildConfig.VERSION_NAME} · physical telephoto", 14)
        statusText = label("Camera permission required", 13)
        focusText = label("", 12)
        header.addView(lensText); header.addView(statusText); header.addView(focusText)
        root.addView(header)
        val stage = FrameLayout(this)
        primary = PreviewPane(this)
        finderPane = PreviewPane(this).apply { showReticle = true }
        stage.addView(primary, FrameLayout.LayoutParams(-1, -1))
        // Keep TextureView attached and visible, even when finder is off. Alpha hides it
        // without destroying a surface required by the next asynchronous session.
        val finderBox = FrameLayout.LayoutParams(dp(212), dp(150), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(8); marginEnd = dp(8)
        }
        stage.addView(finderPane, finderBox)
        finderPane.alpha = 0f
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        fun row(): LinearLayout {
            val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            scroll.addView(content); root.addView(scroll); return content
        }
        val mainRow = row()
        targetButton = button("Acquire", mainRow) {
            val target = status.state != ScopeState.TARGET
            finderOn = target; prefs.edit().putBoolean("finder", target).apply()
            controller.target(target)
        }
        recordButton = button("● Record", mainRow) {
            if (status.recording) controller.stop()
            else {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    AlertDialog.Builder(this).setTitle("Record without sound?")
                        .setMessage("Microphone permission is off. You can record silent video or grant microphone access in Android Settings.")
                        .setPositiveButton("Silent video") { _, _ -> beginRecording(false) }
                        .setNeutralButton("Settings") { _, _ -> appSettings() }.setNegativeButton("Cancel", null).show()
                } else beginRecording(true)
            }
        }
        finderButton = button("1× finder", mainRow) {
            finderOn = !finderOn; prefs.edit().putBoolean("finder", finderOn).apply(); controller.finder(finderOn)
        }
        refocusButton = button("Refocus", mainRow) { controller.refocus() }
        val extraRow = row()
        modeButton = button("1080p · 30 fps", extraRow) { chooseMode() }
        calibrationButton = button("Calibrate", extraRow) {
            if (!status.finderLive) { toast("Turn on the finder first"); return@button }
            AlertDialog.Builder(this).setTitle("Calibrate finder")
                .setMessage("Centre a distant stationary object in the large monocular view. Then tap that same object in the 1× inset. Calibration is approximate and may need repeating after remounting.")
                .setPositiveButton("Tap object") { _, _ -> finderPane.calibrating = true }
                .setNeutralButton("Reset") { _, _ ->
                    finderPane.calibration = Calibration()
                    prefs.edit().remove("calibrationX").remove("calibrationY").apply()
                }.setNegativeButton("Cancel", null).show()
        }
        button("Options", extraRow) { options() }
        button("Diagnostics", extraRow) { diagnostics() }
        button("Last video", extraRow) {
            val uri = lastVideo
            if (uri == null) toast("No video saved in this session")
            else runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                .onFailure { toast("No video viewer available; find it in Movies/ScopeCam") }
        }
        retryButton = button("Retry / permissions", extraRow) { started = false; ensurePermissions(); maybeStart() }
        setContentView(root)
    }

    private fun beginRecording(audio: Boolean) {
        finderPane.calibrating = false
        controller.record(prefs.getBoolean("recordFinder", false), audio, displayDegrees())
    }
    private fun updateUi(value: CameraStatus) {
        status = value
        statusText.text = value.message
        focusText.text = value.focus
        lensText.text = getString(R.string.lens_status, BuildConfig.VERSION_NAME, inventory?.pair?.tele?.id ?: "—", value.state.name)
        val allow = value.ready && !value.busy
        recordButton.text = if (value.recording) "■ Stop" else "● Record"
        recordButton.setTextColor(if (value.recording) Color.rgb(255, 118, 118) else Color.WHITE)
        recordButton.isEnabled = allow && (value.recording || selectedMode != null)
        targetButton.text = if (value.state == ScopeState.TARGET) "Acquire" else "Target"
        targetButton.isEnabled = allow && !value.recording
        finderButton.text = if (value.finderLive) "1× finder ON" else "1× finder OFF"
        finderButton.isEnabled = allow && !value.recording
        refocusButton.isEnabled = allow
        modeButton.isEnabled = allow && !value.recording && inventory?.modes?.isNotEmpty() == true
        calibrationButton.isEnabled = allow && !value.recording && value.finderLive
        finderPane.alpha = if (value.finderLive) 1f else 0f
        finderPane.isEnabled = value.finderLive
        if (!value.finderLive) finderPane.calibrating = false
        value.saved?.let { lastVideo = it }
        retryButton.isEnabled = !value.busy && !value.recording
    }
    private fun chooseMode() {
        val modes = inventory?.modes.orEmpty()
        AlertDialog.Builder(this).setTitle("Telephoto video modes")
            .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), modes.indexOf(selectedMode)) { dialog, index ->
                val choice = modes[index]; selectedMode = choice; modeButton.text = choice.label
                prefs.edit().putString("mode", choice.key).apply(); controller.selectMode(choice); dialog.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }
    private fun options() {
        if (status.recording || status.busy) { toast("Stop recording before changing options"); return }
        val options = arrayOf("Keep 1× finder while recording (experimental)", "Continuous centre AF", "Optical stabilisation only")
        val checked = booleanArrayOf(prefs.getBoolean("recordFinder", false), continuous, opticalOnly)
        AlertDialog.Builder(this).setTitle("Scope options")
            .setMultiChoiceItems(options, checked) { _, index, value -> checked[index] = value }
            .setPositiveButton("Apply") { _, _ ->
                prefs.edit().putBoolean("recordFinder", checked[0]).apply()
                val newFocus = checked[1]; val newOptical = checked[2]
                if (continuous != newFocus) { continuous = newFocus; controller.setFocusBehaviour(newFocus) }
                if (opticalOnly != newOptical) { opticalOnly = newOptical; controller.setOpticalOnly(newOptical) }
            }.setNeutralButton("Focus / exposure") { _, _ -> focusExposure() }
            .setNegativeButton("Cancel", null).show()
    }
    private fun focusExposure() {
        val range = inventory?.tele?.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)) }
        box.addView(label("Exposure compensation", 14))
        if (range != null && range.upper > range.lower) {
            val seek = SeekBar(this).apply { max = range.upper - range.lower; progress = prefs.getInt("exposure", 0) - range.lower }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) { val value = progress + range.lower; prefs.edit().putInt("exposure", value).apply(); controller.setExposure(value) }
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
            box.addView(seek)
        }
        AlertDialog.Builder(this).setTitle("Centre focus and exposure").setView(box)
            .setPositiveButton("Refocus") { _, _ -> controller.refocus() }
            .setNeutralButton("Infinity") { _, _ -> controller.infinity() }
            .setNegativeButton("Close", null).show()
    }
    private fun diagnostics() {
        val content = status.details
        val scroll = ScrollView(this)
        scroll.addView(label(content, 12).apply { setPadding(dp(16), dp(8), dp(16), dp(8)); setTextIsSelectable(true) })
        AlertDialog.Builder(this).setTitle("ScopeCam diagnostics").setView(scroll)
            .setPositiveButton("Close", null).setNeutralButton("Copy") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ScopeCam diagnostics", content))
                toast("Diagnostics copied")
            }.show()
    }
    override fun onResume() { super.onResume(); active = true; ensurePermissions(); maybeStart() }
    override fun onConfigurationChanged(config: android.content.res.Configuration) {
        super.onConfigurationChanged(config)
        controller.displayRotation(displayDegrees())
    }
    override fun onPause() { active = false; started = false; controller.pause(); super.onPause() }
    override fun onDestroy() { controller.destroy(); super.onDestroy() }
    private fun ensurePermissions() {
        if (requestingPermissions || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return
        requestingPermissions = true
        requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 10)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        requestingPermissions = false
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            statusText.setText(R.string.camera_permission_denied)
            AlertDialog.Builder(this).setTitle("Camera access needed").setMessage("ScopeCam needs camera access for preview and recording.")
                .setPositiveButton("Settings") { _, _ -> appSettings() }.setNegativeButton("Close", null).show()
        } else maybeStart()
    }
    private fun maybeStart() {
        if (!active || started || !primary.ready || !finderPane.ready || requestingPermissions ||
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        started = true
        controller.start(prefs.getString("mode", null), finderOn, opticalOnly, continuous, displayDegrees())
        controller.setExposure(prefs.getInt("exposure", 0))
    }
    @Suppress("DEPRECATION")
    private fun displayDegrees(): Int = when (windowManager.defaultDisplay.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
    private fun appSettings() { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    private fun label(text: String, size: Int) = TextView(this).apply { this.text = text; textSize = size.toFloat(); setTextColor(Color.rgb(211, 225, 229)) }
    private fun button(text: String, row: LinearLayout, action: () -> Unit): Button = Button(this).apply {
        this.text = text; textSize = 13f; isAllCaps = false; minWidth = dp(76)
        setOnClickListener { action() }; row.addView(this)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show() }
}
