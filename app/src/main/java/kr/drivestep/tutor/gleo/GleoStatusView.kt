package kr.drivestep.tutor.gleo

import android.content.Context
import android.view.View
import android.widget.TextView
import kr.drivestep.tutor.design.PleosUi
import kr.drivestep.tutor.journey.PreparationStyle

class GleoStatusView(context: Context) : TextView(context) {
    init {
        textSize = 20f; setTextColor(PleosUi.TEXT); background=PreparationStyle.surface(context,PreparationStyle.softAccent)
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2); contentDescription = "gleo_action_status"; visibility = View.GONE
    }
    fun show(message: String) { text = message; visibility = View.VISIBLE }
}
