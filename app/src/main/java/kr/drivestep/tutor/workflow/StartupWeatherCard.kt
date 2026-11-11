package kr.drivestep.tutor.workflow

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import kr.drivestep.tutor.design.PleosUi
import kr.drivestep.tutor.design.PracticeButton

@SuppressLint("ViewConstructor")
class StartupWeatherCard(context: Context, onDetails: () -> Unit) : LinearLayout(context) {
    private val message = TextView(context).apply {
        text = "앱을 열면 현재 날씨와 출발 전 조언을 알려드려요."
        textSize = 18f; setTextColor(PleosUi.TEXT); contentDescription = "startup_weather_message"
    }
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(10), dp(20), dp(10)); setBackgroundColor(PleosUi.RAISED)
        val copy = LinearLayout(context).apply { orientation = VERTICAL }
        copy.addView(TextView(context).apply { text = "출발 전 날씨"; textSize = 22f; setTextColor(PleosUi.TEXT) })
        copy.addView(message)
        addView(copy, LayoutParams(0, -2, 1f))
        addView(PracticeButton(context).apply {
            text = "날씨 자세히"; textSize = 18f; isAllCaps = false
            contentDescription = "startup_weather_details"; PleosUi.decorate(this)
            setOnClickListener { onDetails() }
        }, LayoutParams(dp(160), dp(60)).apply { marginStart = dp(16) })
    }
    fun show(value: String) { message.text = value }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
