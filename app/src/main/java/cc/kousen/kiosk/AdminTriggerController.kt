package cc.kousen.kiosk

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

class AdminTriggerController(
    private val onAdminTrigger: () -> Unit,
) : View.OnTouchListener {
    private val handler = Handler(Looper.getMainLooper())
    private var topLeftHoldRunnable: Runnable? = null
    private var touchArmedUntilMs = 0L
    private var oppositeCornerTapCount = 0
    private var remoteSequenceIndex = 0
    private var remoteSequenceStartedAtMs = 0L

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleTouchDown(view, event)
            MotionEvent.ACTION_UP -> handleTouchUp(view, event)
            MotionEvent.ACTION_CANCEL -> cancelPendingHold()
        }
        return false
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_UP) return false

        val now = event.eventTime
        if (now - remoteSequenceStartedAtMs > REMOTE_SEQUENCE_WINDOW_MS) {
            remoteSequenceIndex = 0
            remoteSequenceStartedAtMs = now
        }

        val acceptedCodes = REMOTE_SEQUENCE[remoteSequenceIndex]
        if (event.keyCode in acceptedCodes) {
            remoteSequenceIndex += 1
            if (remoteSequenceIndex == REMOTE_SEQUENCE.size) {
                remoteSequenceIndex = 0
                onAdminTrigger()
                return true
            }
            return false
        }

        remoteSequenceIndex = if (event.keyCode in REMOTE_SEQUENCE.first()) 1 else 0
        remoteSequenceStartedAtMs = now
        return false
    }

    private fun handleTouchDown(view: View, event: MotionEvent) {
        cancelPendingHold()
        if (!isInTopLeftZone(view, event.x, event.y)) return

        val startedAtMs = event.eventTime
        topLeftHoldRunnable = Runnable {
            touchArmedUntilMs = startedAtMs + TOUCH_ARMED_WINDOW_MS
            oppositeCornerTapCount = 0
        }.also { runnable ->
            handler.postDelayed(runnable, TOP_LEFT_HOLD_MS)
        }
    }

    private fun handleTouchUp(view: View, event: MotionEvent) {
        cancelPendingHold()

        val now = event.eventTime
        if (now > touchArmedUntilMs) {
            oppositeCornerTapCount = 0
            return
        }
        if (!isInTopRightZone(view, event.x, event.y)) return

        oppositeCornerTapCount += 1
        if (oppositeCornerTapCount >= TOP_RIGHT_TAPS_REQUIRED) {
            oppositeCornerTapCount = 0
            touchArmedUntilMs = 0L
            onAdminTrigger()
        }
    }

    private fun cancelPendingHold() {
        topLeftHoldRunnable?.let(handler::removeCallbacks)
        topLeftHoldRunnable = null
    }

    private fun isInTopLeftZone(view: View, x: Float, y: Float): Boolean =
        x <= view.width * CORNER_ZONE_FRACTION && y <= view.height * CORNER_ZONE_FRACTION

    private fun isInTopRightZone(view: View, x: Float, y: Float): Boolean =
        x >= view.width * (1 - CORNER_ZONE_FRACTION) && y <= view.height * CORNER_ZONE_FRACTION

    companion object {
        private const val CORNER_ZONE_FRACTION = 0.18f
        private const val TOP_LEFT_HOLD_MS = 2_500L
        private const val TOUCH_ARMED_WINDOW_MS = 7_500L
        private const val TOP_RIGHT_TAPS_REQUIRED = 3
        private const val REMOTE_SEQUENCE_WINDOW_MS = 8_000L

        private val REMOTE_SEQUENCE = listOf(
            setOf(KeyEvent.KEYCODE_DPAD_UP),
            setOf(KeyEvent.KEYCODE_DPAD_UP),
            setOf(KeyEvent.KEYCODE_DPAD_DOWN),
            setOf(KeyEvent.KEYCODE_DPAD_DOWN),
            setOf(KeyEvent.KEYCODE_DPAD_LEFT),
            setOf(KeyEvent.KEYCODE_DPAD_RIGHT),
            setOf(KeyEvent.KEYCODE_DPAD_LEFT),
            setOf(KeyEvent.KEYCODE_DPAD_RIGHT),
            setOf(
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_SELECT,
            ),
            setOf(
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_SELECT,
            ),
        )
    }
}
