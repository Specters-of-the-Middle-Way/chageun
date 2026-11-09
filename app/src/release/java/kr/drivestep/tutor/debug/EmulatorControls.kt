package kr.drivestep.tutor.debug

import android.content.Context
import android.view.View

/** Release APKs have neither an emulator panel nor console networking. */
@Suppress("UNUSED_PARAMETER")
class EmulatorControls(context: Context, onStatus: (String) -> Unit) {
    fun createView(): View? = null
    fun stop() = Unit
    fun close() = Unit
}
