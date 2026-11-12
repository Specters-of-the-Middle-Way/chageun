package kr.drivestep.tutor.workflow

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kr.drivestep.tutor.design.PleosUi
import kr.drivestep.tutor.journey.PreparationStyle

/** Built with the selected teaching situation in code; never inflated from XML. */
@SuppressLint("ViewConstructor")
class DoubleParkingDiagramView(context:Context,private val situation:DoubleParkingSituation,
    private val choice:ParkingChoice?):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds=RectF()
    private val exitPath=Path()
    private val arrow=Path()
    private val placement=choice?.placement ?: if(situation.id=="departure") ParkingPlacement(.29f,.47f,.32f,.13f) else null
    init { contentDescription="가상 배치 그림: 위쪽 정규 주차 공간, 가운데 출차 경로, 오른쪽 보행·비상 통로. ${choice?.explanation ?: situation.prompt}" }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        fun rect(x:Float,y:Float,w:Float,h:Float,color:Int) {
            paint.color=color; paint.style=Paint.Style.FILL
            bounds.set(width*x,height*y,width*(x+w),height*(y+h))
            canvas.drawRoundRect(bounds,10f,10f,paint)
        }
        fun label(text:String,x:Float,y:Float,color:Int=PleosUi.TEXT) {
            paint.color=color; paint.textSize=PleosUi.canvasTextSize(context,18*resources.displayMetrics.density)
            paint.textAlign=Paint.Align.CENTER; canvas.drawText(text,width*x,height*y,paint)
        }
        rect(0f,0f,1f,1f,PleosUi.RAISED)
        rect(.77f,.05f,.19f,.9f,0xffffe0b2.toInt())
        repeat(3) { n ->
            val x=.1f+n*.25f
            rect(x,.08f,.17f,.32f,android.graphics.Color.WHITE)
            if(n<2 || !situation.freeBay) rect(x+.02f,.12f,.13f,.25f,PleosUi.SECONDARY)
        }
        paint.color=PreparationStyle.accent; paint.strokeWidth=10*resources.displayMetrics.density; paint.style=Paint.Style.STROKE
        exitPath.rewind()
        repeat(2) { n -> val x=.185f+n*.25f; exitPath.moveTo(width*x,height*.38f); exitPath.lineTo(width*x,height*.75f); exitPath.lineTo(width*.69f,height*.75f) }
        canvas.drawPath(exitPath,paint); paint.style=Paint.Style.FILL
        arrow.rewind(); arrow.moveTo(width*.69f,height*.75f); arrow.lineTo(width*.65f,height*.70f); arrow.lineTo(width*.65f,height*.80f); arrow.close()
        canvas.drawPath(arrow,paint)
        placement?.let { rect(it.x,it.y,it.width,it.height,if(choice?.correct==true) PreparationStyle.accent else 0xffa64836.toInt()) }
        label("주차 차량",.29f,.46f)
        if(situation.freeBay) {
            val selectedBay=choice?.correct==true && placement!=null
            label(if(selectedBay) "내 차" else "빈 공간",.685f,.27f,if(selectedBay) android.graphics.Color.WHITE else PleosUi.TEXT)
        }
        label("출차 경로",.43f,.90f)
        // Detailed route names and choice explanations are ordinary wrapping TextViews outside this diagram.
    }
}
