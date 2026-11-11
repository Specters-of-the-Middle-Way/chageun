package kr.drivestep.tutor.gleo

import android.content.Context
import android.view.View
import android.widget.TextView
import kr.drivestep.tutor.design.PleosUi

class GleoStatusView(context: Context) : TextView(context) {
    init {
        textSize = 18f; setTextColor(PleosUi.TEXT); setBackgroundColor(PleosUi.RAISED)
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2); contentDescription = "gleo_action_status"; visibility = View.GONE
    }
    fun show(message: String) { text = message; visibility = View.VISIBLE }
}
