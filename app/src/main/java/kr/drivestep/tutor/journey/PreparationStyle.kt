package kr.drivestep.tutor.journey

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import android.widget.LinearLayout
import kr.drivestep.tutor.design.PleosUi

/** Shared presentation for the profile, posture, and departure preparation pages. */
internal object PreparationStyle {
    val accent = Color.rgb(16, 87, 74)
    val softAccent = Color.rgb(233, 244, 239)
    fun surface(context: Context, fill: Int = PleosUi.SURFACE, border: Boolean = false) = GradientDrawable().apply {
        setColor(fill); cornerRadius = 20 * context.resources.displayMetrics.density
        if (border) setStroke(context.resources.displayMetrics.density.toInt().coerceAtLeast(1), PleosUi.LINE)
    }
    fun button(button: Button, selected: Boolean, soft: Boolean = false) = with(button) {
        backgroundTintList = null
        val fill = if (selected) { if (soft) softAccent else accent } else PleosUi.SURFACE
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(35, 110, 145, 134)),
            surface(context, fill, !selected), surface(context, Color.WHITE))
        setTextColor(if (selected && !soft) Color.WHITE else if (selected) accent else PleosUi.TEXT)
        setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        elevation = 0f; stateListAnimator = null
    }
}

/** Reflows existing controls without replacing them or changing form state during measurement. */
@SuppressLint("ViewConstructor")
internal class PreparationColumns(context: Context, private val firstWeight: Float = 1f,
    private val secondWeight: Float = 1f, private val breakpoint: Int = 1_000
) : LinearLayout(context) {
    private var stacked: Boolean? = null
    init { isBaselineAligned = false }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val nextStacked = MeasureSpec.getSize(widthMeasureSpec) / density < breakpoint || resources.configuration.fontScale > 1.6f
        if (nextStacked != stacked) {
            stacked = nextStacked; orientation = if (nextStacked) VERTICAL else HORIZONTAL
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                val params = child.layoutParams as LayoutParams
                params.width = if (nextStacked) -1 else 0
                params.height = -2
                params.weight = if (nextStacked) 0f else if (index == 0) firstWeight else secondWeight
                params.bottomMargin = if (nextStacked && index == 0) (20 * density).toInt() else 0
                params.marginEnd = if (!nextStacked && index == 0) (24 * density).toInt() else 0
                child.layoutParams = params
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
