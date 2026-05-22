package com.platform.intentcontroller.output

import android.util.Log
import com.platform.intentcontroller.models.ArbitrationResult
import com.platform.intentcontroller.models.TargetChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * PsRemotePlayOutput
 *
 * Routes arbitrated controller state to a PlayStation console over the network
 * using the chiaki-ng open source Remote Play protocol.
 *
 * This class is headless — it opens a chiaki-ng session purely to establish
 * an authenticated input channel. It does NOT set up video decoding or audio
 * output. Those paths in chiaki-jni.c can be stubbed/omitted entirely when
 * building libchiaki for this project.
 *
 * Usage:
 *   1. Call connect(host, registKey, morning, isPs5) once the user has paired.
 *   2. Call execute(arbitrationResult) on every input frame.
 *   3. Call disconnect() on session end / cleanup.
 *
 * JNI bridge:
 *   The native functions declared here map to functions in
 *   android/app/src/main/cpp/chiaki-jni.c from the chiaki-ng repo
 *   (github.com/streetpea/chiaki-ng). Only the session management and
 *   input-sending portions of libchiaki are needed — FFmpeg and all
 *   media codec dependencies can be dropped.
 *
 * Rumble:
 *   The PS5 sends haptic output reports back through the chiaki-ng session.
 *   Wire [onRumble] to GamepadManager.triggerRumble() in MappingService so the
 *   physical gamepad vibrates in sync with the console.
 *
 * Touchpad:
 *   Face tracking pitch/yaw axes are forwarded as PS5 touchpad touch events
 *   via ChiakiControllerState.touchId / touchX / touchY. This matches the
 *   approach used in chiakidroid (github.com/MikeCoder96/chiakidroid).
 */
class PsRemotePlayOutput(
    /** Called when the console sends a rumble report — forward to GamepadManager.triggerRumble(). */
    private val onRumble: (weak: Int, strong: Int) -> Unit = { _, _ -> }
) {

    companion object {
        private const val TAG = "PsRemotePlayOutput"

        init {
            try {
                System.loadLibrary("chiaki")
                Log.i(TAG, "✅ libchiaki loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "❌ libchiaki not found — PS Remote Play will not function", e)
            }
        }

        // ── ChiakiControllerState button bitmask constants ────────────────────
        // These match the CHIAKI_CONTROLLER_BUTTON_* defines in chiaki/controller.h.
        // Verified against chiaki-ng HEAD (streetpea/chiaki-ng).
        const val BTN_CROSS      = 1 shl 0    // 0x0001
        const val BTN_MOON       = 1 shl 1    // 0x0002  Circle
        const val BTN_BOX        = 1 shl 2    // 0x0004  Square
        const val BTN_PYRAMID    = 1 shl 3    // 0x0008  Triangle
        const val BTN_DPAD_LEFT  = 1 shl 4    // 0x0010
        const val BTN_DPAD_RIGHT = 1 shl 5    // 0x0020
        const val BTN_DPAD_UP    = 1 shl 6    // 0x0040
        const val BTN_DPAD_DOWN  = 1 shl 7    // 0x0080
        const val BTN_L1         = 1 shl 8    // 0x0100
        const val BTN_R1         = 1 shl 9    // 0x0200
        const val BTN_L3         = 1 shl 10   // 0x0400
        const val BTN_R3         = 1 shl 11   // 0x0800
        const val BTN_OPTIONS    = 1 shl 12   // 0x1000
        const val BTN_SHARE      = 1 shl 13   // 0x2000
        const val BTN_TOUCHPAD   = 1 shl 14   // 0x4000
        const val BTN_PS         = 1 shl 15   // 0x8000

        // Touchpad physical dimensions (PS5 DualSense)
        private const val TOUCHPAD_WIDTH    = 1920
        private const val TOUCHPAD_HEIGHT   = 942
        private const val TOUCHPAD_CENTER_X = TOUCHPAD_WIDTH  / 2   // 960
        private const val TOUCHPAD_CENTER_Y = TOUCHPAD_HEIGHT / 2   // 471

        // Keep-alive: resend last known state if no real input in this window.
        // 14 ms = one frame at ~70 Hz — tight enough that a held stick never
        // gets zeroed out by the heartbeat, but long enough that the heartbeat
        // never races with a real execute() call on the same tick.
        private const val IDLE_THRESHOLD_MS = 14L

        // Heartbeat tick rate — ~60 Hz resend of last state while held.
        private const val HEARTBEAT_INTERVAL_MS = 16L

        // Minimum face-tracking deviation to activate the touchpad touch.
        private const val TOUCH_ACTIVATION_THRESHOLD = 0.05f

        // Reachability pre-flight timeout (ms).
        private const val REACH_TIMEOUT_MS = 2000
    }

    // ── Native session handle ─────────────────────────────────────────────────
    @Volatile private var nativeSession: Long = 0

    private var commandsSent = 0
    private var connectJob:   Job? = null
    private var heartbeatJob: Job? = null

    /** Timestamp (ms) of the last real input frame sent — used by keep-alive. */
    @Volatile private var lastInputSentMs: Long = 0L

    /**
     * Last arbitration result received via [execute].
     *
     * Stored atomically so the heartbeat coroutine (IO thread) can read the
     * latest snapshot without locking, while [execute] writes from whatever
     * thread the upstream pipeline uses.
     *
     * This is the core fix for joystick "pausing": Android stops generating
     * MotionEvents once a stick is held still, so [execute] stops being
     * called. Without this field the heartbeat would send a neutral/zero
     * frame after IDLE_THRESHOLD_MS, resetting the stick position on the
     * console. With it, the heartbeat resends the last real state instead,
     * keeping the held stick alive at ~60 Hz.
     *
     * panicStop() safety is preserved: panicStop() calls execute() with an
     * empty ArbitrationResult, which updates this field to all-zeros and
     * resets lastInputSentMs. The heartbeat will then resend the zeroed
     * state — which is exactly the desired behaviour after a panic stop.
     */
    private val latestArbitrationResult = AtomicReference<ArbitrationResult?>(null)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Connection lifecycle ──────────────────────────────────────────────────

    /**
     * Opens a chiaki-ng session to the given PlayStation console.
     *
     * @param host       IP address of the console on the local network.
     * @param registKey  16-byte registration key from pairing (hex-decoded).
     * @param morning    16-byte morning secret from pairing (hex-decoded).
     * @param isPs5      true for PS5, false for PS4.
     */
    fun connect(host: String, registKey: ByteArray, morning: ByteArray, isPs5: Boolean) {
        if (nativeSession != 0L) {
            Log.w(TAG, "connect() called while session already active — disconnecting first")
            disconnect()
        }

        if (registKey.size != 16) {
            Log.e(TAG, "❌ registKey must be 16 bytes, got ${registKey.size} — aborting connect")
            return
        }
        if (morning.size != 16) {
            Log.e(TAG, "❌ morning must be 16 bytes, got ${morning.size} — aborting connect")
            return
        }

        val consoleName = if (isPs5) "PS5" else "PS4"
        Log.i(TAG, "🎮 Connecting to $consoleName at $host")

        connectJob?.cancel()
        connectJob = scope.launch {
            val reachable = try {
                java.net.InetAddress.getByName(host).isReachable(REACH_TIMEOUT_MS)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Reachability check threw: ${e.message}")
                false
            }

            if (!isActive) return@launch

            if (!reachable) {
                Log.e(TAG, "❌ $consoleName at $host is not reachable — check IP and that " +
                        "Remote Play is enabled in Settings → System → Remote Play.")
                return@launch
            }

            Log.i(TAG, "✅ $consoleName at $host is reachable — opening chiaki-ng session")

            val handle = nativeConnect(host, registKey, morning, isPs5)

            // Guard: disconnect() may have been called while nativeConnect() was blocking.
            // Without this check, nativeSession gets re-written to a non-zero value after
            // disconnect() cleared it, causing a leaked native session and a stale heartbeat.
            if (!isActive) {
                if (handle != 0L) try { nativeDisconnect(handle) } catch (_: Throwable) {}
                return@launch
            }

            nativeSession = handle

            if (handle == 0L) {
                Log.e(TAG, "❌ nativeConnect failed — check libchiaki logs")
            } else {
                Log.i(TAG, "✅ Chiaki session started (handle=$handle)")
                startHeartbeat()
            }
        }
    }

    /**
     * Tears down the chiaki-ng session cleanly.
     */
    fun disconnect() {
        // Cancel the connect coroutine first — it may be mid-nativeConnect() and could
        // otherwise re-write nativeSession after we clear it below.
        connectJob?.cancel()
        connectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        latestArbitrationResult.set(null)
        val handle = nativeSession
        if (handle == 0L) return
        nativeSession = 0
        Log.i(TAG, "Disconnecting chiaki session. Total commands sent: $commandsSent")
        try {
            nativeDisconnect(handle)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeDisconnect threw: ${e.message}", e)
        }
        commandsSent = 0
    }

    // ── Input execution ───────────────────────────────────────────────────────

    /**
     * Converts an [ArbitrationResult] into a ChiakiControllerState and sends
     * it to the console via the native session.
     */
    fun execute(result: ArbitrationResult) {
        // Always snapshot the latest result so the heartbeat can resend it
        // while the stick is held still (no new MotionEvents arriving).
        latestArbitrationResult.set(result)

        val handle = nativeSession
        if (handle == 0L) return

        val state = buildChiakiState(result)

        try {
            nativeSendInput(
                session  = handle,
                buttons  = state.buttons,
                l2       = state.l2,
                r2       = state.r2,
                leftX    = state.leftX,
                leftY    = state.leftY,
                rightX   = state.rightX,
                rightY   = state.rightY,
                touchId  = state.touchId,
                touchX   = state.touchX,
                touchY   = state.touchY
            )
        } catch (e: Throwable) {
            Log.e(TAG, "nativeSendInput threw: ${e.message}", e)
            return
        }

        lastInputSentMs = System.currentTimeMillis()
        commandsSent++
        if (commandsSent <= 3 || commandsSent % 500 == 0) {
            Log.d(TAG, "✅ Chiaki input sent (#$commandsSent) " +
                    "buttons=0x${state.buttons.toString(16)} " +
                    "touch=${state.touchId}@(${state.touchX},${state.touchY})")
        }
    }

    // ── Keep-alive / held-stick resend ────────────────────────────────────────

    /**
     * Periodically resends the last known controller state while no new
     * real input is arriving from the upstream pipeline.
     *
     * ### Why this is needed
     * Android stops firing MotionEvents once a joystick is held still.
     * The upstream pipeline is purely event-driven, so [execute] stops being
     * called. Without a resend the console sees no input after ~1 frame and
     * returns the stick back to neutral, causing the "joystick pauses when
     * held" symptom observed over PS Remote Play.
     *
     * ### Why resend last state, not neutral
     * The previous implementation sent a neutral (zero) frame after
     * IDLE_THRESHOLD_MS. That was correct for pure idle periods (no
     * controller connected at all) but wrong for a held stick — it actively
     * cancelled the input the user was applying.
     *
     * ### panicStop() safety
     * panicStop() calls execute() with an empty ArbitrationResult (all zeros,
     * empty winners). This updates [latestArbitrationResult] to the zeroed
     * state and resets [lastInputSentMs], so the heartbeat will resend the
     * zeroed state on the next tick — identical to the old neutral frame
     * behaviour for that case.
     *
     * ### Race-condition safety
     * The tick interval (16 ms) is deliberately longer than IDLE_THRESHOLD_MS
     * (14 ms). A real [execute] call resets [lastInputSentMs], so the
     * heartbeat skips its send on the very next tick. This means the
     * heartbeat never fires on the same millisecond as a real frame,
     * avoiding the interleaving that caused dropped inputs in the previous
     * fixed-rate design.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            Log.d(TAG, "Keep-alive started (held-stick resend mode, " +
                    "idle threshold=${IDLE_THRESHOLD_MS}ms, tick=${HEARTBEAT_INTERVAL_MS}ms)")
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val handle = nativeSession
                if (handle == 0L) break
                val now = System.currentTimeMillis()
                if (now - lastInputSentMs < IDLE_THRESHOLD_MS) continue

                // Resend the last real state so a held stick stays held.
                // Falls back to a neutral frame only before the first execute()
                // call (e.g. session just opened, no controller input yet).
                val last = latestArbitrationResult.get()
                try {
                    if (last != null) {
                        val state = buildChiakiState(last)
                        nativeSendInput(
                            session = handle,
                            buttons = state.buttons,
                            l2      = state.l2,
                            r2      = state.r2,
                            leftX   = state.leftX,
                            leftY   = state.leftY,
                            rightX  = state.rightX,
                            rightY  = state.rightY,
                            touchId = state.touchId,
                            touchX  = state.touchX,
                            touchY  = state.touchY
                        )
                    } else {
                        // No input received yet — send neutral to keep session alive.
                        nativeSendInput(
                            session = handle,
                            buttons = 0, l2 = 0, r2 = 0,
                            leftX   = 0, leftY = 0, rightX = 0, rightY = 0,
                            touchId = -1,
                            touchX  = TOUCHPAD_CENTER_X,
                            touchY  = TOUCHPAD_CENTER_Y
                        )
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Keep-alive nativeSendInput threw: ${e.message}", e)
                }
            }
            Log.d(TAG, "Keep-alive coroutine exited")
        }
    }

    // ── State mapping ─────────────────────────────────────────────────────────

    private fun buildChiakiState(result: ArbitrationResult): ChiakiControllerState {
        var buttons = 0
        var l2 = 0
        var r2 = 0
        var leftX:  Short = 0
        var leftY:  Short = 0
        var rightX: Short = 0
        var rightY: Short = 0

        var touchId: Int = -1
        var touchX:  Int = TOUCHPAD_CENTER_X
        var touchY:  Int = TOUCHPAD_CENTER_Y
        var pitchValue = 0f
        var yawValue   = 0f
        var hasPitch   = false
        var hasYaw     = false

        for ((_, winner) in result.winners) {
            when (val ch = winner.channel) {
                is TargetChannel.Button -> {
                    val pressed = winner.value > 0.5f
                    if (!pressed) continue
                    buttons = buttons or mapButtonToChiaki(ch.name)
                }

                is TargetChannel.Axis -> {
                    val v = winner.value
                    when {
                        ch.name.contains("L2", ignoreCase = true) ->
                            l2 = (v * 255f).toInt().coerceIn(0, 255)

                        ch.name.contains("R2", ignoreCase = true) ->
                            r2 = (v * 255f).toInt().coerceIn(0, 255)

                        ch.name.contains("Pitch", ignoreCase = true) -> {
                            pitchValue = v
                            hasPitch   = true
                            touchY = ((0.5f - v * 0.5f) * TOUCHPAD_HEIGHT).toInt()
                                .coerceIn(0, TOUCHPAD_HEIGHT)
                        }

                        ch.name.contains("Yaw", ignoreCase = true) -> {
                            yawValue = v
                            hasYaw   = true
                            touchX = ((v * 0.5f + 0.5f) * TOUCHPAD_WIDTH).toInt()
                                .coerceIn(0, TOUCHPAD_WIDTH)
                        }

                        ch.name.contains("DPad X", ignoreCase = true) -> {
                            if      (v >  0.5f) buttons = buttons or BTN_DPAD_RIGHT
                            else if (v < -0.5f) buttons = buttons or BTN_DPAD_LEFT
                        }

                        ch.name.contains("DPad Y", ignoreCase = true) -> {
                            if      (v >  0.5f) buttons = buttons or BTN_DPAD_DOWN
                            else if (v < -0.5f) buttons = buttons or BTN_DPAD_UP
                        }
                    }
                }

                is TargetChannel.Stick -> {
                    val s = (winner.value * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    when {
                        ch.stick.contains("Left",  ignoreCase = true) ->
                            if (ch.axis == "X") leftX = s else leftY = s
                        ch.stick.contains("Right", ignoreCase = true) ->
                            if (ch.axis == "X") rightX = s else rightY = s
                    }
                }
            }
        }

        val pitchActive = hasPitch && abs(pitchValue) > TOUCH_ACTIVATION_THRESHOLD
        val yawActive   = hasYaw   && abs(yawValue)   > TOUCH_ACTIVATION_THRESHOLD
        if (pitchActive || yawActive) touchId = 0

        return ChiakiControllerState(
            buttons, l2, r2,
            leftX, leftY, rightX, rightY,
            touchId, touchX, touchY
        )
    }

    private fun mapButtonToChiaki(name: String): Int = when (name.uppercase()) {
        "SOUTH", "CROSS",     "A"      -> BTN_CROSS
        "EAST",  "CIRCLE",    "B"      -> BTN_MOON
        "WEST",  "SQUARE",    "X"      -> BTN_BOX
        "NORTH", "TRIANGLE",  "Y"      -> BTN_PYRAMID
        "L1", "LB"                     -> BTN_L1
        "R1", "RB"                     -> BTN_R1
        "L3", "LSB"                    -> BTN_L3
        "R3", "RSB"                    -> BTN_R3
        "START",   "OPTIONS",  "MENU"  -> BTN_OPTIONS
        "SELECT",  "SHARE",    "BACK"  -> BTN_SHARE
        "HOME",    "PS",       "GUIDE" -> BTN_PS
        "TOUCHPAD"                     -> BTN_TOUCHPAD
        "DPAD UP",    "D-PAD UP"       -> BTN_DPAD_UP
        "DPAD DOWN",  "D-PAD DOWN"     -> BTN_DPAD_DOWN
        "DPAD LEFT",  "D-PAD LEFT"     -> BTN_DPAD_LEFT
        "DPAD RIGHT", "D-PAD RIGHT"    -> BTN_DPAD_RIGHT
        else -> 0
    }

    // ── Compact state holder ──────────────────────────────────────────────────

    private data class ChiakiControllerState(
        val buttons: Int,
        val l2: Int,
        val r2: Int,
        val leftX:   Short,
        val leftY:   Short,
        val rightX:  Short,
        val rightY:  Short,
        val touchId: Int,
        val touchX:  Int,
        val touchY:  Int
    )

    // ── JNI declarations ──────────────────────────────────────────────────────

    /** Creates and starts a headless chiaki-ng session. Returns 0 on failure. */
    private external fun nativeConnect(
        host:      String,
        registKey: ByteArray,
        morning:   ByteArray,
        isPs5:     Boolean
    ): Long

    /** Sends one controller state frame to the console. */
    private external fun nativeSendInput(
        session:  Long,
        buttons:  Int,
        l2:       Int,
        r2:       Int,
        leftX:    Short,
        leftY:    Short,
        rightX:   Short,
        rightY:   Short,
        touchId:  Int,
        touchX:   Int,
        touchY:   Int
    )

    /** Stops the session and frees all native resources. */
    private external fun nativeDisconnect(session: Long)
}
