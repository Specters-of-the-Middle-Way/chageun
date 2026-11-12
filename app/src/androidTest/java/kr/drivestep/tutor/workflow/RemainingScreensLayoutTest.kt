package kr.drivestep.tutor.workflow

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.Button
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.R
import kr.drivestep.tutor.journey.DriverJourneyView
import kr.drivestep.tutor.journey.PreparationStyle
import kr.drivestep.tutor.design.PleosUi
import kr.drivestep.tutor.lessoncoach.LessonCoachView
import kr.drivestep.tutor.lessoncoach.LessonTopic
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemainingScreensLayoutTest {
    @Test fun selectedAndDisabledButtonsRenderReadableColorsAfterRestyling() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context=android.view.ContextThemeWrapper(instrumentation.targetContext,R.style.AppTheme)
            val button=Button(context)
            fun fill():Int {
                val bitmap=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888)
                button.background.setBounds(0,0,100,100); button.background.draw(Canvas(bitmap))
                val pixel=bitmap.getPixel(50,50); bitmap.recycle(); return pixel
            }
            PreparationStyle.button(button,false)
            button.isSelected=true; PreparationStyle.button(button,true)
            val child=(button.background as android.graphics.drawable.RippleDrawable).getDrawable(0) as android.graphics.drawable.GradientDrawable
            assertEquals("Selected fill must match the white label: enabled=${button.isEnabled}; button=${button.drawableState.toList()}; background=${button.background.state.toList()}; child=${child.state.toList()}; color=${child.color?.getColorForState(child.state,0)}",PreparationStyle.accent,fill())
            button.isEnabled=false
            assertEquals(PleosUi.RAISED,fill()); assertEquals(PleosUi.SECONDARY,button.currentTextColor)
            button.isEnabled=true; PreparationStyle.button(button,true)
            assertEquals(PreparationStyle.accent,fill())
        }
    }
    private fun views(root: View): List<View> = listOf(root)+if(root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun verify(width: Int,height: Int,scale: Float) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val target=instrumentation.targetContext
            val themed=target.createConfigurationContext(Configuration(target.resources.configuration).apply { fontScale=scale })
            themed.setTheme(R.style.AppTheme)
            val names=mutableSetOf<String>(); val prefix="remaining_layout_${System.nanoTime()}_"
            val context=object:ContextWrapper(themed) {
                override fun getSharedPreferences(name:String,mode:Int)=target.getSharedPreferences(prefix+name,mode).also { names+=prefix+name }
            }
            WeatherPreferences(context).apply { consent=false; startup=false; autoRead=false }
            val density=context.resources.displayMetrics.density
            fun measure(root:View) {
                root.measure(View.MeasureSpec.makeMeasureSpec((width*density).toInt(),View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((height*density).toInt(),View.MeasureSpec.EXACTLY))
                root.layout(0,0,root.measuredWidth,root.measuredHeight)
            }
            fun control(root:View,key:String)=views(root).first { it.contentDescription==key }
            fun click(root:View,key:String) { assertTrue(key,control(root,key).isEnabled); assertTrue(control(root,key).performClick()); measure(root) }
            fun shown(view:View):Boolean { var current:View?=view; while(current!=null) { if(current.visibility!=View.VISIBLE)return false; current=current.parent as? View }; return true }
            fun check(root:View) {
                measure(root)
                views(root).filterIsInstance<TextView>().filter(::shown).forEach { view ->
                    if(view is EditText && view.isSingleLine) return@forEach // Inputs intentionally pan while editing.
                    val detail="$width x $height / $scale: ${view.text}"
                    val layout=requireNotNull(view.layout) { detail }
                    assertTrue("Vertical clipping $detail",layout.height<=view.height-view.compoundPaddingTop-view.compoundPaddingBottom)
                    repeat(layout.lineCount) { line ->
                        assertEquals("Ellipsis $detail",0,layout.getEllipsisCount(line))
                        assertTrue("Horizontal clipping $detail",layout.getLineMax(line)<=layout.width+1)
                    }
                    if(view.isClickable) assertTrue("Small touch target $detail",view.height>=48*density)
                }
                for(key in listOf("workflow_home","workflow_weather_speak","workflow_delete","lesson_start","lesson_finish","lesson_exit")) {
                    views(root).firstOrNull { it.contentDescription==key }?.let { view ->
                        val rect=Rect(0,0,view.width,view.height); (root as ViewGroup).offsetDescendantRectToMyCoords(view,rect)
                        assertTrue("$key outside viewport $rect",rect.top>=0 && rect.bottom<=root.height)
                    }
                }
            }
            val workflow=WorkflowView(context,{},{},{},{},{false},{true},{false},{},{},{})
            val journey=DriverJourneyView(context,{}, {})
            try {
                workflow.setSpeechEnabled(false); workflow.setActive(true); measure(workflow)
                click(workflow,"workflow_connected"); check(workflow)
                click(workflow,"workflow_home"); click(workflow,"workflow_weather"); click(workflow,"workflow_weather_practice")
                for(condition in WeatherCondition.entries) { click(workflow,"workflow_weather_${condition.name.lowercase()}"); check(workflow) }
                click(workflow,"workflow_weather_retry"); check(workflow)
                click(workflow,"workflow_home"); click(workflow,"workflow_history"); check(workflow)
                context.getSharedPreferences("workflow_training_v1",Context.MODE_PRIVATE).edit().putString("records",
                    """[{"source":"SIMULATION","task":"D03","status":"COMPLETED","facts":"가속과 정지 순서를 확인했어요.","unknown":"주변 차량과 노면은 직접 확인해 주세요.","explanation":"차근차근 반복하며 익혔어요.","next":"D01"}]""").commit()
                click(workflow,"workflow_home"); click(workflow,"workflow_history"); check(workflow)
                assertTrue(views(workflow).any { it.contentDescription=="workflow_history_0" })
                journey.setSpeechEnabled(false); journey.setActive(true); measure(journey)
                click(journey,"journey_education"); check(journey)
                journey.openProfile(); click(journey,"journey_profile_continue")
                journey.beginVisit(); check(journey)
                assertTrue(views(journey).any { it.contentDescription=="journey_revisit" })
                for(topic in LessonTopic.entries) {
                    val lesson=LessonCoachView(context,{},{false},{false},{},{false})
                    try {
                        lesson.setSpeechEnabled(false); lesson.setActive(true); check(lesson)
                        click(lesson,"lesson_topic_${topic.name.lowercase()}")
                        click(lesson,"lesson_start"); click(lesson,"lesson_stop_listening"); check(lesson)
                        click(lesson,"lesson_example_traffic"); check(lesson)
                        click(lesson,"lesson_pause"); check(lesson)
                        lesson.openGleo(); check(lesson)
                    } finally { lesson.close() }
                }
            } finally { workflow.close(); journey.close(); names.forEach { target.deleteSharedPreferences(it) } }
        }
    }
    @Test fun remainingScreensFitPleos()=verify(1707,1018,1.2f)
    @Test fun remainingScreensSupportLargeTextAndNarrowWindows() { verify(1707,1018,1.6f); verify(1100,650,1.2f) }
}
