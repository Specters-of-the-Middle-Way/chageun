package kr.drivestep.tutor.design

import android.content.Context
import android.widget.Button

/** Held-pedal touch handlers and accessibility actions share the normal click contract. */
class PracticeButton(context: Context) : Button(context) {
    override fun performClick(): Boolean = super.performClick()
}
