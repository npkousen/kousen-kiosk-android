package cc.kousen.kiosk

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class KioskNavigationGestureController(
    private val onHomeGesture: () -> Unit,
) : View.OnTouchListener {
    private var downX = 0f
    private var downY = 0f
    private var active = false
    private var consumed = false

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                active = isInLeftEdge(view, downX) || isInBottomEdge(view, downY)
                consumed = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (active && !consumed && isHomeGesture(view, event.x, event.y)) {
                    consumed = true
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onHomeGesture()
                    return true
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                val wasConsumed = consumed
                active = false
                consumed = false
                return wasConsumed
            }
        }

        return consumed
    }

    private fun isHomeGesture(view: View, x: Float, y: Float): Boolean {
        val dx = x - downX
        val dy = y - downY
        val leftEdgeHome = isInLeftEdge(view, downX) &&
            dx >= view.width * LEFT_EDGE_DISTANCE_FRACTION &&
            abs(dx) >= abs(dy) * DIRECTION_DOMINANCE
        val bottomEdgeHome = isInBottomEdge(view, downY) &&
            -dy >= view.height * BOTTOM_EDGE_DISTANCE_FRACTION &&
            abs(dy) >= abs(dx) * DIRECTION_DOMINANCE

        return leftEdgeHome || bottomEdgeHome
    }

    private fun isInLeftEdge(view: View, x: Float): Boolean =
        x <= view.width * LEFT_EDGE_ZONE_FRACTION

    private fun isInBottomEdge(view: View, y: Float): Boolean =
        y >= view.height * (1 - BOTTOM_EDGE_ZONE_FRACTION)

    companion object {
        private const val LEFT_EDGE_ZONE_FRACTION = 0.08f
        private const val BOTTOM_EDGE_ZONE_FRACTION = 0.08f
        private const val LEFT_EDGE_DISTANCE_FRACTION = 0.28f
        private const val BOTTOM_EDGE_DISTANCE_FRACTION = 0.20f
        private const val DIRECTION_DOMINANCE = 1.5f
    }
}
