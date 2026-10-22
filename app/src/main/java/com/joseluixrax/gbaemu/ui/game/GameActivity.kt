package com.joseluixrax.gbaemu.ui.game

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.joseluixrax.gbaemu.Prefs
import com.joseluixrax.gbaemu.R
import com.joseluixrax.gbaemu.cheats.Cheat
import com.joseluixrax.gbaemu.cheats.CheatStore
import com.joseluixrax.gbaemu.databinding.ActivityGameBinding
import com.joseluixrax.gbaemu.emulator.AudioOutput
import com.joseluixrax.gbaemu.emulator.EmulatorThread
import com.joseluixrax.gbaemu.emulator.FrameBuffer
import com.joseluixrax.gbaemu.emulator.GameFiles
import com.joseluixrax.gbaemu.emulator.GbaKeys
import com.joseluixrax.gbaemu.emulator.NativeBridge
import com.joseluixrax.gbaemu.emulator.RomLoader
import com.joseluixrax.gbaemu.ui.cheats.CheatsActivity
import com.joseluixrax.gbaemu.ui.library.RecentGames
import com.joseluixrax.gbaemu.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * The emulator screen: game picture (OpenGL), on-screen controller and the
 * in-game menu. Rotation is handled in place (see configChanges in the
 * manifest) so the game never restarts when the phone turns.
 */
class GameActivity : AppCompatActivity(), EmulatorThread.Callbacks, VirtualPadView.Listener {

    private lateinit var binding: ActivityGameBinding
    private lateinit var prefs: Prefs
    private lateinit var renderer: GameRenderer
    private lateinit var audio: AudioOutput
    private lateinit var emulator: EmulatorThread
    private lateinit var haptics: Haptics
    private lateinit var sensors: SensorController
    private val frameBuffer = FrameBuffer()

    private lateinit var files: GameFiles
    private lateinit var cheatStore: CheatStore
    private var gameName = ""

    /** The cheats currently active in the core, to detect edits made in CheatsActivity. */
    private var appliedCheats: List<Cheat> = emptyList()
    private var gameLoaded = false
    private var hardware = 0
    private var platform = NativeBridge.PLATFORM_GBA
    private var solarLevel = 5
    private var activityResumed = false

    /** True while the menu or one of its dialogs is on screen. */
    private var menuOpen = false

    private var touchKeys = 0
    private var physicalKeys = 0
    private var stickKeys = 0
    private var keyLookup: Map<Int, InputAction> = emptyMap()
    private var fastForwardToggleMode = true
    private var fastForwardOn = false
    private var showFps = false
    private var rewindConfig: Pair<Boolean, Int>? = null
    private var leftTriggerDown = false
    private var rightTriggerDown = false
    private var triggerKeysSeen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        haptics = Haptics(this)
        sensors = SensorController(this)
        audio = AudioOutput(this)
        renderer = GameRenderer(frameBuffer)

        setupWindow()

        binding.gameView.apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        binding.pad.listener = this
        setupEditBar()

        onBackPressedDispatcher.addCallback(this) {
            if (binding.pad.editMode) finishEditing(save = false) else openMenu()
        }

        audio.start()
        emulator = EmulatorThread(frameBuffer, audio, this).also { it.start() }
        loadGame()
    }

    // ------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        activityResumed = true
        applySettings()
        binding.gameView.onResume()
        if (gameLoaded) {
            reapplyCheatsIfChanged()
            sensors.start()
        }
        hideSystemBars()
        updatePaused()
    }

    override fun onPause() {
        activityResumed = false
        updatePaused()
        binding.gameView.onPause()
        sensors.stop()
        // Don't leave buttons stuck down while we're away.
        physicalKeys = 0
        stickKeys = 0
        pushKeys()
        emulator.rewinding = false
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (gameLoaded && prefs.autoSaveState) saveState(GameFiles.AUTO_SLOT, quiet = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        emulator.post { NativeBridge.unload() }
        emulator.quit()
        try {
            emulator.join(5000)
        } catch (_: InterruptedException) {
        }
        audio.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun updatePaused() {
        val running = gameLoaded && activityResumed && !menuOpen && !binding.pad.editMode
        emulator.setPaused(!running)
    }

    // ------------------------------------------------------------ game setup

    private fun loadGame() {
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: RomLoader.displayName(this, uri)
        gameName = GameFiles.keyFor(displayName)
        files = GameFiles(this, gameName).also { it.ensureDirs() }
        cheatStore = CheatStore(this, gameName)
        RecentGames.add(this, uri, displayName)
        binding.loading.isVisible = true

        lifecycleScope.launch {
            val rom = try {
                withContext(Dispatchers.IO) { RomLoader.load(this@GameActivity, uri, displayName) }
            } catch (e: Exception) {
                showFatalError(getString(R.string.error_reading_rom, e.message ?: e.javaClass.simpleName))
                return@launch
            }
            val bios = GameFiles.biosFile(this@GameActivity).takeIf { it.isFile }?.path
            val autoState = files.stateFile(GameFiles.AUTO_SLOT)
            val loadAuto = prefs.autoLoadState && autoState.isFile
            val cheats = withContext(Dispatchers.IO) { cheatStore.load() }
            appliedCheats = cheats.map { it.copy() }

            emulator.post {
                val ok = NativeBridge.loadRom(
                    rom.data, files.saveFile.path, bios, audio.sampleRate,
                    prefs.skipBios, prefs.frameskip, prefs.idleLoopDetect, prefs.gbColorize,
                )
                var restored = false
                var hw = 0
                var loadedPlatform = NativeBridge.PLATFORM_NONE
                if (ok) {
                    hw = NativeBridge.hardware()
                    loadedPlatform = NativeBridge.platform()
                    applyCheats(cheats)
                    if (loadAuto) restored = NativeBridge.loadState(autoState.path)
                }
                runOnUiThread { onGameLoaded(ok, hw, loadedPlatform, restored) }
            }
        }
    }

    private fun onGameLoaded(ok: Boolean, hw: Int, loadedPlatform: Int, restored: Boolean) {
        binding.loading.isVisible = false
        if (!ok) {
            showFatalError(getString(R.string.error_not_a_rom))
            return
        }
        gameLoaded = true
        hardware = hw
        platform = loadedPlatform
        sensors.configure(hw)
        if (activityResumed) sensors.start()
        NativeBridge.setSolarLevel(solarLevel)
        rewindConfig = null
        applySettings()
        if (restored) toast(getString(R.string.state_restored))
        updatePaused()
    }

    private fun showFatalError(message: String) {
        binding.loading.isVisible = false
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .show()
    }

    /** Reads settings that may have changed while we were in the settings screen. */
    private fun applySettings() {
        renderer.updateSettings(
            GameRenderer.Settings(
                filter = Shaders.filterFrom(prefs.videoFilter),
                effect = Shaders.effectFrom(prefs.videoEffect),
                colorCorrection = prefs.colorCorrection,
                aspect = when (prefs.aspectMode) {
                    "integer" -> GameRenderer.Aspect.INTEGER
                    "stretch" -> GameRenderer.Aspect.STRETCH
                    else -> GameRenderer.Aspect.KEEP
                },
            ),
        )
        binding.gameView.requestRender()

        showFps = prefs.showFps
        binding.fpsText.isVisible = showFps

        audio.setVolume(prefs.audioVolume / 100f)
        emulator.audioEnabled = prefs.audioEnabled

        binding.pad.apply {
            // The layout may have been reset from the settings screen.
            if (!editMode) reloadLayout()
            opacity = prefs.padOpacity / 100f
            globalScale = prefs.padScale / 100f
            hapticsEnabled = prefs.haptics
            diagonals = prefs.dpadDiagonals
            showRewind = prefs.rewindEnabled
        }
        fastForwardToggleMode = prefs.fastForwardToggle
        emulator.fastForwardSpeed = prefs.fastForwardSpeed
        emulator.fastForwardAudio = prefs.fastForwardAudio
        keyLookup = InputMapping.buildLookup(prefs.raw)
        sensors.gyroSensitivity = prefs.gyroSensitivity / 100f

        requestedOrientation = when (prefs.orientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        if (gameLoaded) {
            val frameskip = prefs.frameskip
            val newRewind = prefs.rewindEnabled to prefs.rewindSeconds
            val rewindChanged = newRewind != rewindConfig
            rewindConfig = newRewind
            emulator.post {
                NativeBridge.setFrameskip(frameskip)
                if (rewindChanged) {
                    NativeBridge.setRewind(newRewind.first, newRewind.second * 60 / REWIND_INTERVAL, REWIND_INTERVAL)
                }
            }
        }
    }

    // ---------------------------------------------------------------- window

    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            renderer.topInset = cutout.top
            binding.gameView.requestRender()
            insets
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ------------------------------------------------- emulator callbacks

    override fun onFrameReady() {
        binding.gameView.requestRender()
    }

    override fun onFpsUpdate(fps: Float) {
        if (showFps) binding.fpsText.post { binding.fpsText.text = getString(R.string.fps_format, fps) }
    }

    override fun onRumble() {
        haptics.rumble()
    }

    // ------------------------------------------------------- on-screen pad

    override fun onPadKeysChanged(keys: Int) {
        touchKeys = keys
        pushKeys()
    }

    override fun onMenuPressed() = openMenu()

    override fun onFastForwardButton(down: Boolean) {
        if (fastForwardToggleMode) {
            if (down) setFastForward(!fastForwardOn)
        } else {
            setFastForward(down)
        }
    }

    override fun onRewindButton(down: Boolean) {
        emulator.rewinding = down && prefs.rewindEnabled
    }

    private fun pushKeys() {
        emulator.keys = touchKeys or physicalKeys or stickKeys
    }

    private fun setFastForward(on: Boolean) {
        fastForwardOn = on
        emulator.fastForward = on
        binding.pad.fastForwardActive = on
    }

    // ---------------------------------------------------- physical buttons

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!gameLoaded || binding.pad.editMode) return super.dispatchKeyEvent(event)
        val action = keyLookup[event.keyCode] ?: return super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L2 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            triggerKeysSeen = true
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) handleAction(action, true)
            KeyEvent.ACTION_UP -> handleAction(action, false)
        }
        if (prefs.hidePadWithGamepad) binding.pad.padVisible = false
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (gameLoaded && !binding.pad.editMode &&
            event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.actionMasked == MotionEvent.ACTION_MOVE
        ) {
            val sx = centeredAxis(event, MotionEvent.AXIS_X)
            val sy = centeredAxis(event, MotionEvent.AXIS_Y)
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            var keys = 0
            if (hx < -0.5f || sx < -0.5f) keys = keys or GbaKeys.LEFT
            if (hx > 0.5f || sx > 0.5f) keys = keys or GbaKeys.RIGHT
            if (hy < -0.5f || sy < -0.5f) keys = keys or GbaKeys.UP
            if (hy > 0.5f || sy > 0.5f) keys = keys or GbaKeys.DOWN
            if (keys != stickKeys) {
                stickKeys = keys
                pushKeys()
            }
            // Analog triggers, for controllers that don't also send L2/R2 key events.
            if (!triggerKeysSeen) {
                val lt = max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
                val rt = max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
                if ((lt > 0.5f) != leftTriggerDown) {
                    leftTriggerDown = lt > 0.5f
                    keyLookup[KeyEvent.KEYCODE_BUTTON_L2]?.let { handleAction(it, leftTriggerDown) }
                }
                if ((rt > 0.5f) != rightTriggerDown) {
                    rightTriggerDown = rt > 0.5f
                    keyLookup[KeyEvent.KEYCODE_BUTTON_R2]?.let { handleAction(it, rightTriggerDown) }
                }
            }
            if (prefs.hidePadWithGamepad && keys != 0) binding.pad.padVisible = false
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun centeredAxis(event: MotionEvent, axis: Int): Float {
        val range = event.device?.getMotionRange(axis, event.source) ?: return 0f
        val value = event.getAxisValue(axis)
        return if (abs(value) > range.flat) value else 0f
    }

    private fun handleAction(action: InputAction, down: Boolean) {
        if (action.gbaKey != 0) {
            physicalKeys = if (down) physicalKeys or action.gbaKey else physicalKeys and action.gbaKey.inv()
            pushKeys()
            return
        }
        when (action) {
            InputAction.FAST_FORWARD -> onFastForwardButton(down)
            InputAction.REWIND -> onRewindButton(down)
            InputAction.MENU -> if (down) openMenu()
            InputAction.QUICK_SAVE -> if (down) saveState(QUICK_SLOT)
            InputAction.QUICK_LOAD -> if (down) loadState(QUICK_SLOT)
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ menu

    private fun openMenu() {
        if (!gameLoaded || menuOpen || binding.pad.editMode) return
        menuOpen = true
        updatePaused()

        val entries = mutableListOf<Pair<String, () -> Unit>>()
        entries += getString(R.string.save_state) to { showStateDialog(saving = true) }
        entries += getString(R.string.load_state) to { showStateDialog(saving = false) }
        entries += getString(if (fastForwardOn) R.string.fast_forward_off else R.string.fast_forward_on) to {
            setFastForward(!fastForwardOn)
            closeMenu()
        }
        entries += getString(R.string.cheats) to {
            closeMenu()
            startActivity(CheatsActivity.intent(this, gameName, platform))
        }
        entries += getString(R.string.screenshot) to {
            takeScreenshot()
            closeMenu()
        }
        if (hardware and NativeBridge.HW_LIGHT_SENSOR != 0) {
            entries += getString(R.string.solar_sensor) to { showSolarDialog() }
        }
        entries += getString(R.string.edit_controls) to {
            closeMenu()
            startEditing()
        }
        entries += getString(R.string.settings) to {
            closeMenu()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        entries += getString(R.string.reset_game) to { confirmReset() }
        entries += getString(R.string.exit_game) to { finish() }

        var handled = false
        MaterialAlertDialogBuilder(this)
            .setTitle(gameName)
            .setItems(entries.map { it.first }.toTypedArray()) { _, which ->
                handled = true
                entries[which].second()
            }
            .setOnDismissListener { if (!handled) closeMenu() }
            .show()
    }

    private fun closeMenu() {
        menuOpen = false
        updatePaused()
        hideSystemBars()
    }

    private fun showStateDialog(saving: Boolean) {
        SaveStateDialog(
            context = this,
            files = files,
            saving = saving,
            onSlotChosen = { slot ->
                if (saving) saveState(slot) else loadState(slot)
                closeMenu()
            },
            onDismiss = { closeMenu() },
        ).show()
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.reset_confirm)
            .setPositiveButton(R.string.reset_game) { _, _ -> emulator.post { NativeBridge.reset() } }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { closeMenu() }
            .show()
    }

    private fun showSolarDialog() {
        val slider = Slider(this).apply {
            valueFrom = 0f
            valueTo = 10f
            stepSize = 1f
            value = solarLevel.toFloat()
            addOnChangeListener { _, value, _ ->
                solarLevel = value.toInt()
                NativeBridge.setSolarLevel(solarLevel)
            }
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.solar_sensor)
            .setMessage(R.string.solar_sensor_hint)
            .setView(slider)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { closeMenu() }
            .show()
    }

    // ----------------------------------------------------- states & files

    private fun saveState(slot: Int, quiet: Boolean = false) {
        if (!gameLoaded) return
        val thumbnail = frameBuffer.toBitmap()
        val stateFile = files.stateFile(slot)
        val thumbFile = files.thumbnailFile(slot)
        emulator.post {
            val ok = NativeBridge.saveState(stateFile.path)
            if (ok && thumbnail != null) writePng(thumbnail, thumbFile)
            if (!quiet) {
                runOnUiThread {
                    toast(if (ok) getString(R.string.state_saved, slotName(slot)) else getString(R.string.state_save_failed))
                }
            }
        }
    }

    private fun loadState(slot: Int) {
        if (!gameLoaded) return
        val stateFile = files.stateFile(slot)
        if (!stateFile.isFile) {
            toast(getString(R.string.slot_empty))
            return
        }
        emulator.post {
            val ok = NativeBridge.loadState(stateFile.path)
            if (ok) frameBuffer.captureFromCore()
            runOnUiThread {
                binding.gameView.requestRender()
                toast(if (ok) getString(R.string.state_loaded, slotName(slot)) else getString(R.string.state_load_failed))
            }
        }
    }

    private fun slotName(slot: Int) =
        if (slot == GameFiles.AUTO_SLOT) getString(R.string.slot_auto) else getString(R.string.slot_number, slot)

    private fun writePng(bitmap: Bitmap, file: File) {
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) {
        }
    }

    private fun takeScreenshot() {
        val frame = frameBuffer.toBitmap() ?: return
        // Native resolution is tiny on a phone gallery: save it 4x, pixel-sharp.
        val big = Bitmap.createScaledBitmap(frame, frame.width * 4, frame.height * 4, false)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "${gameName}_$stamp.png"
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = saveToGallery(big, name)
            withContext(Dispatchers.Main) {
                toast(if (saved != null) getString(R.string.screenshot_saved, saved) else getString(R.string.screenshot_failed))
            }
        }
    }

    /** Returns a human readable location, or null on failure. */
    private fun saveToGallery(bitmap: Bitmap, name: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + getString(R.string.app_name))
            }
            val uri: Uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Environment.DIRECTORY_PICTURES + "/" + getString(R.string.app_name)
        } else {
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "screenshots").apply { mkdirs() }
            val file = File(dir, name)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file.path
        }
    } catch (_: Exception) {
        null
    }

    // ---------------------------------------------------------------- cheats

    private fun applyCheats(cheats: List<Cheat>) {
        NativeBridge.cheatsClear()
        for (cheat in cheats) NativeBridge.cheatsAdd(cheat.name, cheat.code, cheat.type, cheat.enabled)
    }

    private fun reapplyCheatsIfChanged() {
        val cheats = cheatStore.load()
        if (cheats == appliedCheats) return
        appliedCheats = cheats.map { it.copy() }
        emulator.post { applyCheats(cheats) }
    }

    // ------------------------------------------------------- layout editor

    private fun setupEditBar() {
        binding.editSmaller.setOnClickListener {
            if (!binding.pad.scaleSelected(0.9f)) toast(getString(R.string.edit_select_first))
        }
        binding.editBigger.setOnClickListener {
            if (!binding.pad.scaleSelected(1.1f)) toast(getString(R.string.edit_select_first))
        }
        binding.editReset.setOnClickListener { binding.pad.resetLayoutToDefaults() }
        binding.editCancel.setOnClickListener { finishEditing(save = false) }
        binding.editDone.setOnClickListener { finishEditing(save = true) }
    }

    private fun startEditing() {
        binding.pad.padVisible = true
        binding.pad.editMode = true
        binding.editBar.isVisible = true
        updatePaused()
    }

    private fun finishEditing(save: Boolean) {
        if (save) binding.pad.saveLayout() else binding.pad.reloadLayout()
        binding.pad.editMode = false
        binding.editBar.isVisible = false
        updatePaused()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_DISPLAY_NAME = "display_name"
        private const val QUICK_SLOT = 1
        private const val REWIND_INTERVAL = 2

        fun intent(context: Context, rom: Uri, displayName: String): Intent =
            Intent(context, GameActivity::class.java)
                .setData(rom)
                .putExtra(EXTRA_DISPLAY_NAME, displayName)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
