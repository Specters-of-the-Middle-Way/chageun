package kr.drivestep.tutor.journey

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.PopupWindow
import android.widget.TextView

/** A transient, non-interactive toast confined to the foreground preparation window. */
internal class PreparationToast(private val anchor: View) {
    private val handler = Handler(Looper.getMainLooper())
    private var popup: PopupWindow? = null
    private val dismiss = Runnable { cancel() }
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = cancel()
    }

    fun show(message: String) {
        cancel()
        if (!anchor.isAttachedToWindow || anchor.width <= dp(48)) return
        val context = anchor.context
        val text = TextView(context).apply {
            this.text = message
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
            contentDescription = "journey_vehicle_status_toast"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        popup = PopupWindow(text, minOf(dp(680), anchor.width - dp(48)), ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isTouchable = false
            isOutsideTouchable = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            elevation = dp(8).toFloat()
            setBackgroundDrawable(PreparationStyle.surface(context, PreparationStyle.accent))
            showAtLocation(anchor, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(112))
        }
        anchor.addOnAttachStateChangeListener(attachment)
        val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val duration = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            accessibility.getRecommendedTimeoutMillis(4_000, AccessibilityManager.FLAG_CONTENT_TEXT) else 4_000
        handler.postDelayed(dismiss, duration.toLong())
    }

    fun cancel() {
        handler.removeCallbacks(dismiss)
        anchor.removeOnAttachStateChangeListener(attachment)
        popup?.dismiss()
        popup = null
    }

    private fun dp(value: Int) = (value * anchor.resources.displayMetrics.density).toInt()
}
