package com.joseluixrax.gbaemu.ui.game

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.OpenableColumns
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.joseluixrax.gbaemu.Prefs
import com.joseluixrax.gbaemu.R
import com.joseluixrax.gbaemu.databinding.ActivityN3dsBinding
import com.joseluixrax.gbaemu.emulator.DsKeys
import com.joseluixrax.gbaemu.emulator.GameFiles
import com.joseluixrax.gbaemu.emulator.GbaKeys
import com.joseluixrax.gbaemu.emulator.N3dsBridge
import com.joseluixrax.gbaemu.emulator.N3dsKeys
import com.joseluixrax.gbaemu.emulator.RomLoader
import com.joseluixrax.gbaemu.emulator.TurboKeys
import com.joseluixrax.gbaemu.ui.settings.SettingsActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The 3DS game screen. Unlike [GameActivity] the picture isn't ours to draw:
 * the core's own thread renders straight into the SurfaceView (see
 * n3ds_android.cpp) and this class tells it where, and what is pressed.
 *
 * It runs in a process of its own (":n3ds" in the manifest) that is thrown
 * away when the game closes, so settings are read fresh from disk.
 */
class N3dsActivity : AppCompatActivity(), VirtualPadView.Listener {

    private lateinit var binding: ActivityN3dsBinding
    private lateinit var prefs: Prefs
    private lateinit var files: GameFiles
    private var gameName = ""

    /** Everything that blocks (loading, states, stopping) runs here, in order. */
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    /** The core is running the game (it may be paused). */
    private var started = false

    /** The core has been shut down: nothing more may be sent to it. */
    private var stopped = false
    private var exiting = false
    private var activityResumed = false
    private var menuOpen = false
    private var surfaceReady = false
    private var topInset = 0

    /** Where the game is drawn, in the SurfaceView's (and the pad's) pixels. */
    private val gameRect = RectF()
    private var frameAspect = DEFAULT_ASPECT

    private var touchKeys = 0
    private var physicalKeys = 0
    private var hatKeys = 0
    private var padStickX = 0f
    private var padStickY = 0f
    private var leftX = 0f
    private var leftY = 0f
    private var rightX = 0f
    private var rightY = 0f
    private var pointerX = 0
    private var pointerY = 0
    private var pointerDown = false
    private var keyLookup: Map<Int, InputAction> = emptyMap()
    private var leftTriggerDown = false
    private var rightTriggerDown = false
    private var triggerKeysSeen = false

    private var fastForwardToggleMode = true
    private var fastForwardOn = false
    private var fastForwardSpeed = 4f
    private var showFps = false
    private var swapped = false
    private var appliedResolution = ""

    /** Picture for the automatic state, taken when the game goes to the background. */
    @Volatile
    private var autoThumbnail: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityN3dsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs.fresh(this)
        swapped = layoutPrefs().getBoolean(KEY_SWAP, false)

        setupWindow()
        binding.surface.holder.addCallback(surfaceCallback)
        binding.pad.apply {
            listener = this@N3dsActivity
            layoutPrefs = this@N3dsActivity.layoutPrefs()
            padMode = PadMode.N3DS
            // The core takes the touch over its whole frame and keeps what
            // falls on the bottom screen, wherever that is.
            touchResolutionX = POINTER_RANGE
            touchResolutionY = POINTER_RANGE
            touchScreenArea = { gameRect.takeUnless { it.isEmpty } }
        }
        setupEditBar()
        onBackPressedDispatcher.addCallback(this) {
            if (binding.pad.editMode) finishEditing(save = false) else openMenu()
        }
        startGame()
    }

    // ------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        activityResumed = true
        applySettings()
        hideSystemBars()
        updatePaused()
        handler.post(poll)
    }

    override fun onPause() {
        activityResumed = false
        updatePaused()
        handler.removeCallbacks(poll)
        physicalKeys = 0
        hatKeys = 0
        leftX = 0f
        leftY = 0f
        rightX = 0f
        rightY = 0f
        pushInput()
        // The surface may be gone by onStop: take the state's picture now.
        if (started && !stopped && prefs.autoSaveState) captureGame(topHalf = true) { autoThumbnail = it }
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (started && !stopped && prefs.autoSaveState) saveAutoState()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        // The core keeps global state: a clean process for the next game.
        // Stop it first (it writes the game's save), then end the process.
        runOnWorker {
            if (!stopped) N3dsBridge.stop()
            Process.killProcess(Process.myPid())
        }
        Thread {
            // Should stopping ever hang, don't leave a zombie behind.
            Thread.sleep(STOP_TIMEOUT_MS)
            Process.killProcess(Process.myPid())
        }.apply { isDaemon = true }.start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun updatePaused() {
        if (!started || stopped) return
        val running = activityResumed && surfaceReady && !menuOpen && !binding.pad.editMode && !exiting
        N3dsBridge.setPaused(!running)
    }

    private fun runOnWorker(task: () -> Unit) {
        try {
            worker.execute(task)
        } catch (_: RejectedExecutionException) {
        }
    }

    // ------------------------------------------------------------ game setup

    private fun startGame() {
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: RomLoader.displayName(this, uri)
        gameName = GameFiles.keyFor(displayName)
        files = GameFiles(this, gameName).also { it.ensureDirs() }
        binding.loading.isVisible = true
        if (!N3dsBridge.available) {
            showFatalError(getString(R.string.error_3ds_unsupported))
            return
        }
        val options = coreOptions()
        appliedResolution = prefs.n3dsResolution
        val autoState = files.stateFile(GameFiles.AUTO_SLOT)
        val loadAuto = prefs.autoLoadState && autoState.isFile
        runOnWorker {
            val error = try {
                launchCore(uri, displayName, options)
            } catch (e: Exception) {
                getString(R.string.error_reading_rom, e.message ?: e.javaClass.simpleName)
            }
            val restored = error == null && loadAuto && N3dsBridge.loadState(autoState.path)
            runOnUiThread { onStarted(error, restored) }
        }
    }

    /** Worker thread. Returns null when the game is running, else what to tell the user. */
    private fun launchCore(uri: Uri, displayName: String, options: Array<String>): String? {
        val descriptor = contentResolver.openFileDescriptor(uri, "r")
            ?: return getString(R.string.error_reading_rom, uri.toString())
        descriptor.use { pfd ->
            val size = pfd.statSize.takeIf { it > 0 } ?: sizeFromProvider(uri)
                ?: return getString(R.string.error_reading_rom, displayName)
            val info = N3dsBridge.inspectRom(pfd.fd, size)
            when (info[1].toInt()) {
                N3dsBridge.PROBLEM_ENCRYPTED -> return getString(R.string.error_3ds_encrypted)
                N3dsBridge.PROBLEM_NOT_A_GAME -> return getString(R.string.error_3ds_not_a_game)
                N3dsBridge.PROBLEM_DAMAGED -> return getString(R.string.error_3ds_damaged)
            }
            // The name only tells the core what it's looking at: the bytes come
            // from the descriptor (for a CIA, just the game inside it).
            val extension = when (info[0].toInt()) {
                KIND_NCSD -> "3ds"
                KIND_NCCH, N3dsBridge.KIND_CIA -> "cxi"
                KIND_3DSX -> "3dsx"
                else -> RomLoader.extensionOf(displayName)
            }
            val gamePath = File(cacheDir, "n3ds/game.$extension").path
            val root = GameFiles.rootDir(this)
            val systemDir = File(root, "3ds/system").apply { mkdirs() }
            val saveDir = File(root, "3ds/saves").apply { mkdirs() }
            val error = N3dsBridge.start(
                systemDir.path, saveDir.path, gamePath, pfd.fd, info[2], info[3], options,
            )
            return when {
                error.isEmpty() -> null
                error == "gles32" || error.startsWith("EGL") -> getString(R.string.error_3ds_gles)
                else -> getString(R.string.error_3ds_start, error)
            }
        }
    }

    /** Some providers don't give the size through the descriptor. */
    private fun sizeFromProvider(uri: Uri): Long? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it > 0 } else null
        }
    } catch (_: Exception) {
        null
    }

    /** Core option keys and values, alternating (see the core's option list). */
    private fun coreOptions(): Array<String> = listOf(
        // The core's Vulkan path wants a frontend that speaks Vulkan; ours is GLES.
        "citra_graphics_api", "OpenGL",
        "citra_use_cpu_jit", "enabled",
        "citra_is_new_3ds", if (prefs.n3dsNewModel) "New 3DS" else "Old 3DS",
        "citra_region_value", "Auto",
        "citra_language_value", languageFor(Locale.getDefault()),
        "citra_resolution_factor", prefs.n3dsResolution,
        // Top screen above the bottom one; the activity places the whole frame.
        "citra_layout_option", "default",
        "citra_swap_screen", if (swapped) "Bottom" else "Top",
        "citra_swap_screen_mode", "Toggle",
        // The right stick is the C-stick, the finger is the stylus.
        "citra_analog_function", "c_stick",
        "citra_enable_mouse_touchscreen", "disabled",
        "citra_enable_touch_touchscreen", "enabled",
        "citra_enable_motion", "disabled",
        // No microphone for 3DS games (yet): silence instead.
        "citra_input_type", "none",
        "citra_use_libretro_save_path", "LibRetro Default",
    ).toTypedArray()

    private fun onStarted(error: String?, restored: Boolean) {
        binding.loading.isVisible = false
        if (error != null) {
            showFatalError(error)
            return
        }
        started = true
        if (surfaceReady) N3dsBridge.setSurface(binding.surface.holder.surface)
        applySettings()
        updateViewport()
        pushInput()
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
        prefs = Prefs.fresh(this)
        showFps = prefs.showFps
        binding.fpsText.isVisible = showFps
        binding.pad.apply {
            // The layout may have been reset from the settings screen.
            layoutPrefs = this@N3dsActivity.layoutPrefs()
            if (!editMode) reloadLayout()
            opacity = prefs.padOpacity / 100f
            globalScale = prefs.padScale / 100f
            hapticsEnabled = prefs.haptics
            diagonals = prefs.dpadDiagonals
            showTurbo = prefs.turboButtons
        }
        fastForwardToggleMode = prefs.fastForwardToggle
        // 0 means "as fast as it goes"; a 3DS rarely gets far past 2x anyway.
        fastForwardSpeed = prefs.fastForwardSpeed.let { if (it <= 0f) 8f else it }
        keyLookup = buildKeyLookup()
        requestedOrientation = when (prefs.orientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (started && !stopped) {
            N3dsBridge.setVolume(if (prefs.audioEnabled) prefs.audioVolume / 100f else 0f)
            N3dsBridge.setTurboSpeed(prefs.turboHalfPeriod)
            N3dsBridge.setSpeed(if (fastForwardOn) fastForwardSpeed else 1f)
            val resolution = prefs.n3dsResolution
            if (resolution != appliedResolution) {
                appliedResolution = resolution
                N3dsBridge.setOption("citra_resolution_factor", resolution)
            }
        }
    }

    /** The user's bindings, with ZL and ZR on the triggers unless they chose otherwise. */
    private fun buildKeyLookup(): Map<Int, InputAction> {
        val lookup = InputMapping.buildLookup(prefs.raw).toMutableMap()
        if (InputMapping.keysFor(prefs.raw, InputAction.ZL).isEmpty() &&
            lookup[KeyEvent.KEYCODE_BUTTON_L2].let { it == null || it == InputAction.REWIND }
        ) {
            lookup[KeyEvent.KEYCODE_BUTTON_L2] = InputAction.ZL
        }
        if (InputMapping.keysFor(prefs.raw, InputAction.ZR).isEmpty() &&
            lookup[KeyEvent.KEYCODE_BUTTON_R2].let { it == null || it == InputAction.FAST_FORWARD }
        ) {
            lookup[KeyEvent.KEYCODE_BUTTON_R2] = InputAction.ZR
        }
        return lookup
    }

    @Suppress("DEPRECATION")
    private fun layoutPrefs() = getSharedPreferences(PadLayout.N3DS_PREFS, Context.MODE_MULTI_PROCESS)

    // ---------------------------------------------------------------- window

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {}

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaceReady = true
            if (started && !stopped) N3dsBridge.setSurface(holder.surface)
            updateViewport()
            updatePaused()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            updatePaused()
            // Returns once the core's thread has let go of it.
            if (started && !stopped) N3dsBridge.setSurface(null)
        }
    }

    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            topInset = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
            updateViewport()
            insets
        }
        binding.surface.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateViewport() }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Portrait: both screens on top, as wide as they fit above the controls.
     * Landscape: centred, full height, controls on the sides.
     */
    private fun updateViewport() {
        val w = binding.surface.width.toFloat()
        val h = binding.surface.height.toFloat()
        if (w <= 0f || h <= 0f) return
        var gameW: Float
        var gameH: Float
        val top: Float
        if (w > h) {
            gameH = h
            gameW = gameH * frameAspect
            if (gameW > w) {
                gameW = w
                gameH = gameW / frameAspect
            }
            top = (h - gameH) / 2f
        } else {
            val room = max(h - topInset - PORTRAIT_CONTROLS * w, 0.4f * h)
            gameW = w
            gameH = gameW / frameAspect
            if (gameH > room) {
                gameH = room
                gameW = gameH * frameAspect
            }
            top = topInset.toFloat()
        }
        val left = (w - gameW) / 2f
        gameRect.set(left, top, left + gameW, top + gameH)
        if (started && !stopped) {
            N3dsBridge.setViewport(left.roundToInt(), top.roundToInt(), gameW.roundToInt(), gameH.roundToInt())
        }
    }

    /** FPS text, and the frame's shape (it changes if the core's layout does). */
    private val poll = object : Runnable {
        override fun run() {
            if (started && !stopped) {
                if (showFps) binding.fpsText.text = getString(R.string.fps_format, N3dsBridge.fps())
                val size = N3dsBridge.frameSize()
                val fw = size ushr 16
                val fh = size and 0xFFFF
                if (fw > 0 && fh > 0) {
                    val aspect = fw.toFloat() / fh
                    if (abs(aspect - frameAspect) > 0.001f) {
                        frameAspect = aspect
                        updateViewport()
                    }
                }
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    // ------------------------------------------------------- on-screen pad

    override fun onPadKeysChanged(keys: Int) {
        touchKeys = keys
        pushInput()
    }

    override fun onMenuPressed() = openMenu()

    override fun onFastForwardButton(down: Boolean) {
        if (fastForwardToggleMode) {
            if (down) setFastForward(!fastForwardOn)
        } else {
            setFastForward(down)
        }
    }

    // No rewind for 3DS games: the button isn't shown.
    override fun onRewindButton(down: Boolean) {}

    override fun onScreenTouch(x: Int, y: Int) {
        pointerX = x - POINTER_RANGE / 2
        pointerY = y - POINTER_RANGE / 2
        pointerDown = true
        pushInput()
    }

    override fun onScreenRelease() {
        pointerDown = false
        pushInput()
    }

    override fun onSwapScreens() = toggleSwap()

    override fun onStick(x: Float, y: Float) {
        padStickX = x
        padStickY = y
        pushInput()
    }

    /** Bottom screen first, or back. Remembered for the next game. */
    private fun toggleSwap() {
        if (!started || stopped) return
        swapped = !swapped
        layoutPrefs().edit().putBoolean(KEY_SWAP, swapped).apply()
        N3dsBridge.setOption("citra_swap_screen", if (swapped) "Bottom" else "Top")
    }

    private fun pushInput() {
        if (!started || stopped) return
        val all = touchKeys or physicalKeys or hatKeys
        val padStick = padStickX != 0f || padStickY != 0f
        N3dsBridge.setInput(
            toRetro(all and TurboKeys.NORMAL_MASK),
            toRetro(all ushr TurboKeys.SHIFT),
            axis(if (padStick) padStickX else leftX),
            axis(if (padStick) padStickY else leftY),
            axis(rightX),
            axis(rightY),
            pointerX,
            pointerY,
            pointerDown,
        )
    }

    /** The app's key bits ([GbaKeys], [DsKeys], [N3dsKeys]) as libretro joypad bits. */
    private fun toRetro(keys: Int): Int {
        var out = 0
        for (i in RETRO_KEYS.indices step 2) {
            if (keys and RETRO_KEYS[i] != 0) out = out or RETRO_KEYS[i + 1]
        }
        return out
    }

    private fun axis(value: Float) = (value.coerceIn(-1f, 1f) * 32767f).roundToInt()

    private fun setFastForward(on: Boolean) {
        fastForwardOn = on
        binding.pad.fastForwardActive = on
        if (started && !stopped) N3dsBridge.setSpeed(if (on) fastForwardSpeed else 1f)
    }

    // ---------------------------------------------------- physical buttons

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!started || binding.pad.editMode) return super.dispatchKeyEvent(event)
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
        if (started && !binding.pad.editMode &&
            event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.actionMasked == MotionEvent.ACTION_MOVE
        ) {
            // Left stick: circle pad. Right stick: C-stick. Hat: D-pad.
            leftX = centeredAxis(event, MotionEvent.AXIS_X)
            leftY = centeredAxis(event, MotionEvent.AXIS_Y)
            rightX = centeredAxis(event, MotionEvent.AXIS_Z)
            rightY = centeredAxis(event, MotionEvent.AXIS_RZ)
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            var keys = 0
            if (hx < -0.5f) keys = keys or GbaKeys.LEFT
            if (hx > 0.5f) keys = keys or GbaKeys.RIGHT
            if (hy < -0.5f) keys = keys or GbaKeys.UP
            if (hy > 0.5f) keys = keys or GbaKeys.DOWN
            hatKeys = keys
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
            pushInput()
            if (prefs.hidePadWithGamepad && (keys != 0 || leftX != 0f || leftY != 0f)) binding.pad.padVisible = false
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
        if (action.key != 0) {
            physicalKeys = if (down) physicalKeys or action.key else physicalKeys and action.key.inv()
            pushInput()
            return
        }
        when (action) {
            InputAction.FAST_FORWARD -> onFastForwardButton(down)
            InputAction.MENU -> if (down) openMenu()
            InputAction.QUICK_SAVE -> if (down) saveState(QUICK_SLOT)
            InputAction.QUICK_LOAD -> if (down) loadState(QUICK_SLOT)
            InputAction.SWAP_SCREENS -> if (down) toggleSwap()
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ menu

    private fun openMenu() {
        if (!started || stopped || menuOpen || exiting || binding.pad.editMode) return
        menuOpen = true
        updatePaused()

        val entries = mutableListOf<Pair<String, () -> Unit>>()
        entries += getString(R.string.save_state) to { showStateDialog(saving = true) }
        entries += getString(R.string.load_state) to { showStateDialog(saving = false) }
        val slowMotion = prefs.fastForwardSpeed.let { it > 0f && it < 1f }
        val speedLabel = when {
            slowMotion && fastForwardOn -> R.string.slow_motion_off
            slowMotion -> R.string.slow_motion_on
            fastForwardOn -> R.string.fast_forward_off
            else -> R.string.fast_forward_on
        }
        entries += getString(speedLabel) to {
            setFastForward(!fastForwardOn)
            closeMenu()
        }
        entries += getString(R.string.swap_screens) to {
            toggleSwap()
            closeMenu()
        }
        entries += getString(R.string.screenshot) to {
            takeScreenshot()
            closeMenu()
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
        entries += getString(R.string.exit_game) to { exitGame() }

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
            .setPositiveButton(R.string.reset_game) { _, _ -> if (started && !stopped) N3dsBridge.reset() }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { closeMenu() }
            .show()
    }

    /** Saves (if set to), shuts the core down and only then leaves. */
    private fun exitGame() {
        if (exiting) return
        exiting = true
        menuOpen = false
        updatePaused()
        binding.loading.isVisible = true
        val save = started && !stopped && prefs.autoSaveState
        val finishUp = {
            runOnWorker {
                if (save) writeAutoState()
                if (started && !stopped) N3dsBridge.stop()
                runOnUiThread {
                    stopped = true
                    finish()
                }
            }
        }
        if (save) {
            captureGame(topHalf = true) {
                autoThumbnail = it
                finishUp()
            }
        } else {
            finishUp()
        }
    }

    // ----------------------------------------------------- states & files

    private fun saveState(slot: Int) {
        if (!started || stopped) return
        val stateFile = files.stateFile(slot)
        val thumbFile = files.thumbnailFile(slot)
        captureGame(topHalf = true) { thumbnail ->
            runOnWorker {
                val ok = N3dsBridge.saveState(stateFile.path)
                if (ok && thumbnail != null) Screenshots.writePng(thumbnail, thumbFile)
                runOnUiThread {
                    toast(if (ok) getString(R.string.state_saved, slotName(slot)) else getString(R.string.state_save_failed))
                }
            }
        }
    }

    private fun saveAutoState() {
        runOnWorker { writeAutoState() }
    }

    /** Worker thread. */
    private fun writeAutoState() {
        val ok = N3dsBridge.saveState(files.stateFile(GameFiles.AUTO_SLOT).path)
        val thumbnail = autoThumbnail
        if (ok && thumbnail != null) Screenshots.writePng(thumbnail, files.thumbnailFile(GameFiles.AUTO_SLOT))
    }

    private fun loadState(slot: Int) {
        if (!started || stopped) return
        val stateFile = files.stateFile(slot)
        if (!stateFile.isFile) {
            toast(getString(R.string.slot_empty))
            return
        }
        runOnWorker {
            val ok = N3dsBridge.loadState(stateFile.path)
            runOnUiThread {
                toast(if (ok) getString(R.string.state_loaded, slotName(slot)) else getString(R.string.state_load_failed))
            }
        }
    }

    private fun slotName(slot: Int) =
        if (slot == GameFiles.AUTO_SLOT) getString(R.string.slot_auto) else getString(R.string.slot_number, slot)

    /**
     * Copies what's on screen inside the game's rectangle (the top screen
     * alone for state thumbnails). [done] runs on the main thread, with null
     * when there is nothing to copy.
     */
    private fun captureGame(topHalf: Boolean, done: (Bitmap?) -> Unit) {
        val area = Rect(
            gameRect.left.roundToInt(),
            gameRect.top.roundToInt(),
            gameRect.right.roundToInt(),
            (if (topHalf) gameRect.centerY() else gameRect.bottom).roundToInt(),
        )
        if (!surfaceReady || area.width() <= 0 || area.height() <= 0) {
            done(null)
            return
        }
        val bitmap = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(binding.surface, area, bitmap, { result ->
                if (result != PixelCopy.SUCCESS) {
                    done(null)
                } else if (topHalf && bitmap.width > THUMBNAIL_WIDTH) {
                    // Thumbnails are small on screen: no need for full size files.
                    val height = bitmap.height * THUMBNAIL_WIDTH / bitmap.width
                    done(Bitmap.createScaledBitmap(bitmap, THUMBNAIL_WIDTH, max(height, 1), true))
                } else {
                    done(bitmap)
                }
            }, handler)
        } catch (_: IllegalArgumentException) {
            done(null)
        }
    }

    private fun takeScreenshot() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "${gameName}_$stamp.png"
        captureGame(topHalf = false) { picture ->
            if (picture == null) {
                toast(getString(R.string.screenshot_failed))
                return@captureGame
            }
            runOnWorker {
                val saved = Screenshots.saveToGallery(this, picture, name)
                runOnUiThread {
                    toast(if (saved != null) getString(R.string.screenshot_saved, saved) else getString(R.string.screenshot_failed))
                }
            }
        }
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
        private const val KEY_SWAP = "3ds_swap"

        // n3ds_rom.h
        private const val KIND_NCSD = 1
        private const val KIND_NCCH = 2
        private const val KIND_3DSX = 3

        /** Both screens stacked: 400 x (240 + 240). */
        private const val DEFAULT_ASPECT = 400f / 480f

        /** Height of the portrait controls, in screen widths (see PadLayout). */
        private const val PORTRAIT_CONTROLS = 0.92f

        /** The pad reports touches as 0..POINTER_RANGE-1; libretro wants -32767..32767. */
        private const val POINTER_RANGE = 65535
        private const val POLL_MS = 500L
        private const val STOP_TIMEOUT_MS = 8000L
        private const val THUMBNAIL_WIDTH = 400

        /** Pairs: app key bit, libretro bit. */
        private val RETRO_KEYS = intArrayOf(
            GbaKeys.A, N3dsBridge.Retro.A,
            GbaKeys.B, N3dsBridge.Retro.B,
            DsKeys.X, N3dsBridge.Retro.X,
            DsKeys.Y, N3dsBridge.Retro.Y,
            GbaKeys.L, N3dsBridge.Retro.L,
            GbaKeys.R, N3dsBridge.Retro.R,
            N3dsKeys.ZL, N3dsBridge.Retro.ZL,
            N3dsKeys.ZR, N3dsBridge.Retro.ZR,
            GbaKeys.START, N3dsBridge.Retro.START,
            GbaKeys.SELECT, N3dsBridge.Retro.SELECT,
            GbaKeys.UP, N3dsBridge.Retro.UP,
            GbaKeys.DOWN, N3dsBridge.Retro.DOWN,
            GbaKeys.LEFT, N3dsBridge.Retro.LEFT,
            GbaKeys.RIGHT, N3dsBridge.Retro.RIGHT,
        )

        fun intent(context: Context, rom: Uri, displayName: String): Intent =
            Intent(context, N3dsActivity::class.java)
                .setData(rom)
                .putExtra(EXTRA_DISPLAY_NAME, displayName)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        /** The 3DS system language closest to the phone's. */
        fun languageFor(locale: Locale): String = when (locale.language) {
            "ja" -> "Japanese"
            "fr" -> "French"
            "es", "ca", "gl", "eu" -> "Spanish"
            "de" -> "German"
            "it" -> "Italian"
            "nl" -> "Dutch"
            "pt" -> "Portuguese"
            "ru" -> "Russian"
            "ko" -> "Korean"
            "zh" -> if (locale.country in setOf("TW", "HK", "MO") || locale.script == "Hant") {
                "Traditional Chinese"
            } else {
                "Simplified Chinese"
            }
            else -> "English"
        }
    }
}
