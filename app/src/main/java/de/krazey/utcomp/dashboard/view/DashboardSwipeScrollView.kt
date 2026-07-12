package de.krazey.utcomp.dashboard.view

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView
import kotlin.math.abs

/**
 * ScrollView that leaves taps and vertical scrolling to its children, but
 * intercepts a clearly horizontal drag for dashboard page navigation.
 *
 * Keeping gesture arbitration here avoids installing competing touch listeners
 * on every dashboard card and edit cell.
 */
internal class DashboardSwipeScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {
    var isPageSwipeEnabled: () -> Boolean = { true }
    var onTouchStateChanged: (Boolean) -> Unit = {}
    var onHorizontalGestureStarted: () -> Unit = {}
    var onPageSwipe: (pageDelta: Int) -> Unit = {}

    private enum class GestureAxis {
        UNDECIDED,
        HORIZONTAL,
        VERTICAL,
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val swipeDistance = maxOf(touchSlop * 2.5f, 36f * resources.displayMetrics.density)

    private var downX = 0f
    private var downY = 0f
    private var axis = GestureAxis.UNDECIDED
    private var touchActive = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                axis = GestureAxis.UNDECIDED
                setTouchActive(true)

                // ScrollView needs to see DOWN so it can take over later when the
                // gesture resolves vertically.
                super.onInterceptTouchEvent(event)
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isPageSwipeEnabled()) {
                    return super.onInterceptTouchEvent(event)
                }
                resolveAxis(event)
                return when (axis) {
                    GestureAxis.HORIZONTAL -> true
                    GestureAxis.VERTICAL -> super.onInterceptTouchEvent(event)
                    GestureAxis.UNDECIDED -> false
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (axis == GestureAxis.HORIZONTAL) {
                    // Keep ownership through UP/CANCEL. onTouchEvent performs the
                    // page change and final cleanup.
                    return true
                }
                finishGesture()
                return false
            }
        }

        return axis == GestureAxis.HORIZONTAL || super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (axis != GestureAxis.HORIZONTAL) {
            val handled = super.onTouchEvent(event)
            if (
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                finishGesture()
            }
            return handled
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> return true

            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val pageDelta = if (
                    abs(dx) >= swipeDistance && abs(dx) > abs(dy) * DIRECTION_BIAS
                ) {
                    // Left drag advances, right drag goes back.
                    if (dx < 0f) 1 else -1
                } else {
                    0
                }
                finishGesture()
                if (pageDelta != 0) onPageSwipe(pageDelta)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                finishGesture()
                return true
            }
        }

        return true
    }

    private fun resolveAxis(event: MotionEvent) {
        if (axis != GestureAxis.UNDECIDED) return

        val dx = event.x - downX
        val dy = event.y - downY
        val absDx = abs(dx)
        val absDy = abs(dy)
        if (maxOf(absDx, absDy) < touchSlop) return

        when {
            absDx > absDy * DIRECTION_BIAS -> {
                axis = GestureAxis.HORIZONTAL
                parent?.requestDisallowInterceptTouchEvent(true)
                onHorizontalGestureStarted()
            }

            absDy > absDx * DIRECTION_BIAS -> {
                axis = GestureAxis.VERTICAL
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }

    private fun finishGesture() {
        parent?.requestDisallowInterceptTouchEvent(false)
        axis = GestureAxis.UNDECIDED
        setTouchActive(false)
    }

    private fun setTouchActive(active: Boolean) {
        if (touchActive == active) return
        touchActive = active
        onTouchStateChanged(active)
    }

    private companion object {
        const val DIRECTION_BIAS = 1.05f
    }
}
