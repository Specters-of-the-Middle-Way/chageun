package kr.drivestep.tutor.design

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

/** Keep native dialog semantics while matching the app's readable text and actions. */
fun AlertDialog.Builder.showReadable(): AlertDialog = create().showReadable()

fun AlertDialog.showReadable(): AlertDialog {
    show()
    val density = context.resources.displayMetrics.density
    val minimumText = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,20f,context.resources.displayMetrics)
    fun style(view: View) {
        if (view is TextView && view !is Button) {
            if (view.textSize < minimumText) view.textSize = 20f
            view.setTextColor(PleosUi.TEXT)
            if(view.id!=View.NO_ID && runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()=="alertTitle") {
                view.textSize=26f; view.setTypeface(view.typeface,Typeface.BOLD)
            }
            if (view.isClickable) view.minimumHeight = (64 * density).toInt()
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) style(view.getChildAt(i))
    }
    window?.decorView?.let(::style)
    findViewById<TextView>(android.R.id.message)?.apply { textSize = 22f; setLineSpacing(4 * density, 1f) }
    listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { id ->
        getButton(id)?.apply {
            textSize = 20f; isAllCaps = false; minHeight = (64 * density).toInt()
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
            PleosUi.decorate(this, id == AlertDialog.BUTTON_POSITIVE)
        }
    }
    listView?.apply {
        setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) { child?.let(::style) }
            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        })
        post { style(this) }
    }
    fun activity(value: Context): Activity? = when (value) {
        is Activity -> value
        is ContextWrapper -> if (value.baseContext !== value) activity(value.baseContext) else null
        else -> null
    }
    val available = activity(context)?.window?.decorView?.width ?: 0
    if (available > 0) window?.setLayout(minOf((1000 * density).toInt(), (available * .92f).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
    return this
}
