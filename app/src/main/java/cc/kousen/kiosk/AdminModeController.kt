package cc.kousen.kiosk

import android.view.MotionEvent
import android.view.View

class AdminModeController(
    private val onAdminGesture: () -> Unit,
) : View.OnTouchListener {
    private var tapCount = 0
    private var firstTapTimeMs = 0L

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return false

        val now = event.eventTime
        if (now - firstTapTimeMs > TAP_WINDOW_MS) {
            firstTapTimeMs = now
            tapCount = 0
        }

        tapCount += 1
        if (tapCount >= REQUIRED_TAPS) {
            tapCount = 0
            onAdminGesture()
        }
        return false
    }

    companion object {
        private const val REQUIRED_TAPS = 7
        private const val TAP_WINDOW_MS = 4_000L
    }
}
