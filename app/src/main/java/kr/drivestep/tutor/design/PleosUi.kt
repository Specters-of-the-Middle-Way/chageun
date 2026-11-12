package kr.drivestep.tutor.design

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.widget.Button
import android.widget.ImageView
import androidx.core.content.ContextCompat
import kr.drivestep.tutor.R
import kotlin.math.roundToInt

/**
 * Native adapters for the publicly supplied Pleos Connect design-assets-215.fig.
 * Original vector paths: res/drawable/pleos_*.xml. Source IDs/hashes: third_party/pleos.
 * This is an app adapter to design assets, not an official Android component SDK.
 */
object PleosUi {
    const val TEXT_SCALE = 1.2f
    val BACKGROUND = Color.rgb(247, 248, 250) // background/primary, Light
    val SURFACE = Color.WHITE // surface/basic
    val RAISED = Color.rgb(237, 238, 242) // surface/high
    val TEXT = Color.rgb(19, 20, 23) // text/dark/primary
    val SECONDARY = Color.rgb(81, 84, 92) // Higher contrast for supporting copy on light surfaces.
    val LINE = Color.rgb(224, 225, 230) // surface/accent
    val SELECTED = Color.rgb(16, 87, 74)
    val PRESSED = Color.rgb(12, 65, 55)
    private val STYLE_TAG = R.id.pleos_style

    private val icons = mapOf(
        "profile" to R.drawable.pleos_profile, "profile_solid" to R.drawable.pleos_profile_solid,
        "seat" to R.drawable.pleos_seat, "steering" to R.drawable.pleos_steering_a,
        "steering_b" to R.drawable.pleos_steering_b, "vehicle" to R.drawable.pleos_vehicle,
        "check" to R.drawable.pleos_check, "check_round" to R.drawable.pleos_check_round,
        "back" to R.drawable.pleos_back, "arrow_left" to R.drawable.pleos_back,
        "next" to R.drawable.pleos_next, "arrow_right" to R.drawable.pleos_next,
        "close" to R.drawable.pleos_close, "menu" to R.drawable.pleos_menu,
        "eye" to R.drawable.pleos_eye, "info" to R.drawable.pleos_info,
        "warning" to R.drawable.pleos_warning, "driving" to R.drawable.pleos_driving,
        "navigation" to R.drawable.pleos_navigation, "sound" to R.drawable.pleos_sound,
        "sound_mute" to R.drawable.pleos_sound_mute, "delete" to R.drawable.pleos_delete,
        "plus" to R.drawable.pleos_plus, "minus" to R.drawable.pleos_minus,
    )

    fun icon(context: Context, key: String, sizeDp: Int = 32, tint: Int = TEXT): Drawable? {
        val id = icons[key] ?: return null
        return ContextCompat.getDrawable(context, id)?.mutate()?.apply {
            setTint(tint)
            val size = dp(context, sizeDp)
            setBounds(0, 0, size, size)
        }
    }

    fun image(context: Context, key: String, sizeDp: Int, tint: Int = TEXT) = ImageView(context).apply {
        setImageDrawable(icon(context, key, sizeDp, tint))
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** connect-s Small: radius 16, nominal height 64, source button group 67:22525. */
    fun buttonBackground(context: Context, selected: Boolean = false): Drawable {
        fun fill(color: Int) = GradientDrawable().apply {
            setColor(color); cornerRadius = dp(context, 20).toFloat()
            if (!selected) setStroke(dp(context, 1), LINE)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), fill(RAISED))
            addState(intArrayOf(android.R.attr.state_pressed), fill(if (selected) PRESSED else Color.rgb(233, 244, 239)))
            addState(intArrayOf(), fill(if (selected) SELECTED else SURFACE))
        }
    }

    fun decorate(button: Button, selected: Boolean = false) {
        if (button.getTag(STYLE_TAG) == selected && button.background is StateListDrawable) return
        button.setTag(STYLE_TAG, selected)
        button.isSelected = selected
        button.backgroundTintList = null
        button.background = buttonBackground(button.context, selected)
        button.setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(SECONDARY, if (selected) Color.WHITE else TEXT)))
        button.compoundDrawablesRelative.filterNotNull().forEach { it.setTint(if (selected) Color.WHITE else TEXT) }
        button.elevation = 0f
        button.stateListAnimator = null
    }

    fun checkIcon(context: Context, checked: Boolean, sizeDp: Int = 32): Drawable =
        ContextCompat.getDrawable(context, if (checked) R.drawable.pleos_checkbox_on else R.drawable.pleos_checkbox_off)!!.mutate().apply {
            val size = dp(context, sizeDp); setBounds(0, 0, size, size)
        }

    fun prefix(button: Button, key: String, sizeDp: Int = 24) {
        button.setCompoundDrawablesRelative(icon(button.context, key, sizeDp,
            if (button.isSelected) Color.WHITE else TEXT), null, null, null)
        button.compoundDrawablePadding = dp(button.context, 10)
    }

    /** Match Canvas labels to the same SP scaling used by TextViews. */
    fun canvasTextSize(context: Context, sizePx: Float): Float {
        val metrics = context.resources.displayMetrics
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizePx / metrics.density, metrics)
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}
