package kr.drivestep.tutor.design

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CompoundButton
import androidx.core.content.ContextCompat
import kr.drivestep.tutor.R
import kotlin.math.roundToInt

/** Original Figma switch state art with native CompoundButton input and accessibility. */
class PleosSwitch(context: Context) : CompoundButton(context) {
    private val on = ContextCompat.getDrawable(context, R.drawable.pleos_switch_on)!!
    private val off = ContextCompat.getDrawable(context, R.drawable.pleos_switch_off)!!
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        buttonDrawable = null
        background = null
        minimumWidth = dp(80); minimumHeight = dp(64)
        isClickable = true; isFocusable = true
        textSize = 18f
        setTextColor(PleosUi.TEXT)
    }
    override fun onDraw(canvas: Canvas) {
        val asset = if (isChecked) on else off
        val h = dp(48).coerceAtMost(height)
        val w = (h * 80f / 48f).roundToInt()
        asset.alpha = if (isEnabled) 255 else 82
        asset.setBounds(width - paddingRight - w, (height - h) / 2, width - paddingRight, (height + h) / 2)
        asset.draw(canvas)
        if (text.isNotEmpty()) {
            labelPaint.color = currentTextColor; labelPaint.textSize = textSize; labelPaint.typeface = typeface
            val baseline = height / 2f - (labelPaint.ascent() + labelPaint.descent()) / 2f
            canvas.drawText(text.toString(), paddingLeft.toFloat(), baseline, labelPaint)
        }
    }
    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"; info.isCheckable = true; info.isChecked = isChecked
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()
}
