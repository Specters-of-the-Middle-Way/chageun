package kr.drivestep.tutor.parking

import android.app.AlertDialog
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import kr.drivestep.tutor.design.PracticeButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kr.drivestep.tutor.coach.CoachCue
import kr.drivestep.tutor.design.PleosUi
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Parked-only educational simulator. Host owns actual vehicle UX eligibility; all controls are virtual. */
// Created in code with required host/session arguments; never inflated from XML.
@SuppressLint("ViewConstructor")
class ParkingSimulatorView(
    context: Context,
    private val onCue: (CoachCue) -> Unit,
    private val stopVoice: () -> Unit,
    private val onExit: () -> Unit,
    private val recordEnabled: () -> Boolean = { true },
) : LinearLayout(context) {
    private enum class Page { COURSES, DRIVE, REFLECTION }
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("drivestep_parking_simulator_v1", Context.MODE_PRIVATE)
    private val bg = PleosUi.BACKGROUND
    private val panel = PleosUi.SURFACE
    private val raised = PleosUi.RAISED
    private val line = PleosUi.LINE
    private val ink = PleosUi.TEXT
    private val muted = PleosUi.SECONDARY
    private val accent = PleosUi.SELECTED
    private val amber = Color.rgb(132, 83, 18)
    private var page = Page.COURSES
    private var selected = runCatching { ParkingCourse.valueOf(prefs.getString("course", "REVERSE_BAY")!!) }.getOrDefault(ParkingCourse.REVERSE_BAY)
    private var session: ParkingSimulationSession? = null
    private var snapshot: ParkingSnapshot? = null
    private var active = false
    private var running = false
    private var closed = false
    private var compact = false
    private var gasTouch = false
    private var brakeTouch = false
    private var brakeHeld = false
    private var suppressClick = false
    private var syncing = false
    private var generation = 0L
    private var lastTick = 0L
    private var lastCue = -1L
    private var lastPhase: ParkingPhase? = null
    private var lastGear: ParkingGear? = null
    private var lastX = Double.NaN
    private var lastY = Double.NaN
    private var startedAt = 0L
    private var phaseEvents = JSONArray()
    private var records = readRecords()
    private var review: JSONObject? = null
    private var assessment = "미응답"
    private var stored = false
    private var historyReview = false
    private var historyIndex = 0
    private var statusMessage: String? = null
    private var deleteDialog: AlertDialog? = null

    private var scene: ParkingSceneView? = null
    private var phaseText: TextView? = null
    private var cueText: TextView? = null
    private var speedText: TextView? = null
    private var alignmentText: TextView? = null
    private var distanceText: TextView? = null
    private var steeringText: TextView? = null
    private var steeringSlider: SeekBar? = null
    private var brakeHold: Button? = null
    private var gasButton: Button? = null
    private var brakeButton: Button? = null
    private var pauseButton: Button? = null
    private var resumeButton: Button? = null
    private var finishButton: Button? = null
    private var repeatButton: Button? = null
    private var startButton: Button? = null
    private val inputs = mutableListOf<View>()
    private val gearButtons = mutableMapOf<ParkingGear,Button>()

    private val ticker = object : Runnable {
        override fun run() {
            if (!canInput() || !isAttachedToWindow) return
            val now = SystemClock.elapsedRealtime()
            val dt = ((now-lastTick)/1000.0).coerceIn(0.0,.1)
            lastTick = now
            session?.tick(dt)?.let(::consume)
            if (canInput()) main.postDelayed(this,33)
        }
    }

    init {
        orientation=VERTICAL
        setBackgroundColor(bg)
        setPadding(dp(12),dp(6),dp(12),dp(8))
        render()
    }

    fun setActive(value:Boolean) {
        if(!value)dismissDeleteDialog()
        if (closed || active==value) return
        active=value
        if (!value) pause() else {
            records=readRecords()
            if(historyReview||stored){
                val retained=review
                val index=records.indexOfFirst{it.optLong("startedAt")==retained?.optLong("startedAt")&&it.optString("course")==retained?.optString("course")}
                if(index<0){review=null;historyReview=false;stored=false;page=Page.COURSES}
                else if(historyReview){historyIndex=index;review=records[index]}
            }
            if(page!=Page.DRIVE)render()
        }
        enabled()
    }

    fun goBack():Boolean {
        if (page==Page.COURSES) return false
        if(page==Page.DRIVE) endAttempt("중간에 마침") else courses()
        return true
    }

    fun close() {
        dismissDeleteDialog()
        if(closed)return
        pause();closed=true;main.removeCallbacksAndMessages(null)
    }

    override fun onDetachedFromWindow() { dismissDeleteDialog();pause();super.onDetachedFromWindow() }
    override fun onAttachedToWindow() { super.onAttachedToWindow();enabled() }

    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        val next = h in 1 until dp(650)
        if(next!=compact){
            compact=next
            main.post { if(!closed){if(page==Page.DRIVE)pause();render()} }
        }
    }

    private fun render() {
        scene=null;phaseText=null;cueText=null;speedText=null;alignmentText=null;distanceText=null
        steeringText=null;steeringSlider=null;brakeHold=null;gasButton=null;brakeButton=null
        pauseButton=null;resumeButton=null;finishButton=null;repeatButton=null;startButton=null
        inputs.clear();gearButtons.clear();removeAllViews()
        when(page) { Page.COURSES->coursePage();Page.DRIVE->drivePage();Page.REFLECTION->reflectionPage() }
        enabled()
    }

    private fun coursePage() {
        records=readRecords()
        val head=row(this,if(compact)48 else 56)
        head.addView(label("주차 연습",26,ink,true),weight())
        head.addView(label("IONIQ 3  ·  4,155 × 1,800 mm",14,muted),fixed(290,48))
        head.addView(button("닫기","park_exit",true){exit()},fixed(76,48))
        val courses=row(this,if(compact)54 else 64)
        courses.addView(button("후진 주차","park_course_reverse",selected!=ParkingCourse.REVERSE_BAY){ select(ParkingCourse.REVERSE_BAY) },weight(if(compact)48 else 56))
        courses.addView(button("평행 주차","park_course_parallel",selected!=ParkingCourse.PARALLEL){ select(ParkingCourse.PARALLEL) },weight(if(compact)48 else 56))

        val body=LinearLayout(context).apply { orientation=HORIZONTAL }
        addView(body,LayoutParams(-1,0,1f).apply { topMargin=dp(8);bottomMargin=dp(10) })
        val preview=ParkingSceneView(context).apply { update(ParkingSimulationSession(selected).snapshot(),false) }
        body.addView(preview,LayoutParams(0,-1,1f).apply { marginEnd=dp(14) })
        val info=column(body,if(compact)270 else 298)
        info.addView(label(if(selected==ParkingCourse.REVERSE_BAY)"후진 주차" else "평행 주차",12,accent,true),full(if(compact)24 else 30))
        info.addView(label(if(selected==ParkingCourse.REVERSE_BAY)"기어와 속도 조절" else "조향과 위치 조절",if(compact)21 else 25,ink,true),full(if(compact)58 else 84))
        info.addView(label(if(selected==ParkingCourse.REVERSE_BAY)"브레이크로 멈추고 R을 선택합니다. 후진하며 위치를 맞춘 뒤 P로 마무리해요." else "R·D와 핸들을 직접 조작하며 두 차량 사이의 목표 구역에 위치를 맞춰요.",if(compact)14 else 16,muted).apply { maxLines=5 },full(if(compact)68 else 106))
        info.addView(divider(),full(1))
        if(!compact)listOf("기어 변경은 정지 + 브레이크","핸들 입력은 중앙 버튼으로 복귀","속도는 화면 속 최대 5 km/h").forEachIndexed { i,text ->
            val r=row(info,46);r.addView(label("0${i+1}",13,accent,true),fixed(30,40));r.addView(label(text,14,ink),weight())
        }
        info.addView(label("차량 제원은 공개 자료 기준입니다.\n조향·노면 반응은 교육용 가상 모델입니다.",12,muted),full(if(compact)38 else 46))

        val bottom=row(this,if(compact)54 else 62)
        startButton=button("${courseName(selected)} 시작","park_start"){begin()}.also { bottom.addView(it,weight(if(compact)48 else 56,1.5f)) }
        if(records.isNotEmpty()) {
            bottom.addView(button("지난 연습","park_history",true){historyIndex=0;review=records.first();historyReview=true;page=Page.REFLECTION;render()},weight(if(compact)48 else 56))
            bottom.addView(button("주차 기록 삭제","park_delete_history",true){confirmDeleteHistory()},fixed(132,if(compact)48 else 56))
        }
        addView(label(statusMessage?:if(recordEnabled())"정차 중 가상 연습  ·  연습 후 저장 여부를 선택할 수 있어요" else "정차 중 가상 연습  ·  현재 기록 저장 동의가 꺼져 있어요",12,muted),full(24))
    }

    private fun drivePage() {
        val head=row(this,if(compact)48 else 54)
        head.addView(label(courseName(selected),22,ink,true),weight())
        head.addView(label("가상 주차  ·  실제 차량 P·정차",12,muted),fixed(225,48))
        pauseButton=button("일시정지","park_pause",true){pause()}.also { head.addView(it,fixed(98,48)) }
        resumeButton=button("이어하기","park_resume"){resume()}.also {head.addView(it,fixed(98,48))}
        finishButton=button("주차 마침","park_finish"){if(canInput())session?.finish()?.let(::consume)}.also {head.addView(it,fixed(105,48))}
        head.addView(button("종료","park_exit",true){endAttempt("중간에 마침")},fixed(76,48))

        val body=LinearLayout(context).apply{orientation=HORIZONTAL}
        addView(body,LayoutParams(-1,0,1f).apply{topMargin=dp(6);bottomMargin=dp(10)})
        scene=ParkingSceneView(context).also{body.addView(it,LayoutParams(0,-1,1f).apply{marginEnd=dp(14)})}
        val aside=column(body,if(compact)238 else 262)
        phaseText=label("기어를 준비하세요",if(compact)14 else 16,ink,true).also{aside.addView(it,full(if(compact)24 else 32))}
        if(!compact)aside.addView(label("가상 기어",12,muted),full(22))
        val gears=row(aside,if(compact)52 else 64)
        for(gear in listOf(ParkingGear.P,ParkingGear.R,ParkingGear.D)) {
            val b=button(gear.name,"park_gear_${gear.name.lowercase()}",true){if(canInput())session?.setGear(gear)?.let(::consume)}.apply{textSize=23f}
            gears.addView(b,weight(if(compact)48 else 58));gearButtons[gear]=b;inputs.add(b)
        }
        brakeHold=button("브레이크 유지","park_brake_hold",true){if(canInput()){brakeHeld=!brakeHeld;applyPedals()}}.also{aside.addView(it,full(if(compact)48 else 52));inputs.add(it)}
        if(!compact)aside.addView(label("마우스 하나로 기어를 바꿀 때 사용",11,muted),full(24))
        aside.addView(divider(),full(1))
        val speed=row(aside,if(compact)44 else 70)
        speedText=label("0.0",if(compact)29 else 42,ink,true).also{speed.addView(it,weight())}
        speed.addView(label("km/h",14,muted),fixed(55,48))
        alignmentText=label("목표 구역 밖",if(compact)13 else 15,muted).also{aside.addView(it,full(if(compact)22 else 30))}
        if(!compact){
            distanceText=label("",13,muted).also{aside.addView(it,full(28))}
            aside.addView(label("완료 순서\n정지 → 브레이크 → P → 주차 마침",13,muted),full(42))
        }

        val coaching=row(this,if(compact)54 else 68).apply{background=shape(panel,10);setPadding(dp(16),0,dp(8),0)}
        coaching.addView(label("안내",16,ink,true),fixed(62,48))
        cueText=label("",if(compact)16 else 18,ink,true).apply{maxLines=2}.also{coaching.addView(it,weight())}
        repeatButton=button("다시 듣기","park_repeat",true){if(canInput())snapshot?.coachCue?.let{stopVoice();onCue(it)}}.also{coaching.addView(it,fixed(92,48))}

        val controls=row(this,if(compact)90 else 108)
        val wheel=LinearLayout(context).apply{orientation=VERTICAL;setPadding(0,dp(6),dp(12),0)}
        controls.addView(wheel,weight(-1,1.25f))
        val wheeltop=row(wheel,if(compact)28 else 36)
        wheeltop.addView(PleosUi.image(context,"steering",28),fixed(36,32))
        steeringText=label("가상 핸들  /  중앙",16,ink,true).also{wheeltop.addView(it,weight())}
        val wheelline=row(wheel,56)
        steeringSlider=SeekBar(context).apply{
            max=200;progress=100;contentDescription="park_steering";tag="park_steering"
            progressTintList=ColorStateList.valueOf(accent);thumbTintList=ColorStateList.valueOf(accent)
            setPadding(dp(16),0,dp(16),0)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onStartTrackingTouch(v:SeekBar?)=Unit
                override fun onStopTrackingTouch(v:SeekBar?)=Unit
                override fun onProgressChanged(v:SeekBar?,p:Int,fromUser:Boolean){if(fromUser&&!syncing&&canInput())session?.setSteering((p-100)/100.0)?.let(::consume)}
            })
        }.also{wheelline.addView(it,weight(54));inputs.add(it)}
        val center=button("중앙","park_center",true){if(canInput())session?.setSteering(0.0)?.let(::consume)}
        wheelline.addView(center,fixed(76,52));inputs.add(center)
        val pedals=LinearLayout(context).apply{orientation=VERTICAL;setPadding(dp(6),dp(6),0,0)}
        controls.addView(pedals,weight(-1,1f))
        pedals.addView(label("가상 페달  /  누르는 동안 조작",12,muted),full(if(compact)28 else 36))
        val pedalrow=row(pedals,56)
        brakeButton=pedal("브레이크","park_brake",false).also{pedalrow.addView(it,weight(54))}
        gasButton=pedal("가속","park_gas",true).also{pedalrow.addView(it,weight(54))}
        addView(label("공개 전장·전폭·축거를 반영한 가상 공간  ·  실제 주변 거리나 주차 성공을 판정하지 않습니다",11,muted),full(if(compact)16 else 20))
        snapshot?.let(::updateScene)
    }

    private fun reflectionPage() {
        val record=review
        val head=row(this,if(compact)48 else 56)
        head.addView(label("주차 연습 돌아보기",24,ink,true),weight())
        head.addView(button("닫기","park_exit",true){exit()},fixed(76,48))
        if(record==null){addView(label("다음 연습에서 한 동작씩 확인해요.",20,ink),full(90));return}
        addView(label("${record.optString("courseTitle")}  ·  ${record.optString("outcome")}",16,accent,true),full(if(compact)32 else 38))
        addView(label("${date(record.optLong("startedAt"))}  ·  가상 주행 ${record.optDouble("elapsedSeconds").roundToInt()}초",12,muted),full(if(compact)24 else 28))
        val body=LinearLayout(context).apply{orientation=HORIZONTAL}
        addView(body,LayoutParams(-1,0,1f).apply{topMargin=dp(12);bottomMargin=dp(12)})
        val left=LinearLayout(context).apply{orientation=VERTICAL;background=shape(panel,12);setPadding(dp(22),dp(if(compact)8 else 18),dp(22),dp(if(compact)8 else 18))}
        body.addView(left,LayoutParams(0,-1,1.25f).apply{marginEnd=dp(14)})
        left.addView(label("조작 기록",17,ink,true),full(if(compact)28 else 40))
        val notes=record.optJSONArray("reflections")?:JSONArray()
        val noteScroll=android.widget.ScrollView(context).apply{isFillViewport=true}
        left.addView(noteScroll,LayoutParams(-1,0,1f))
        val notesColumn=LinearLayout(context).apply{orientation=VERTICAL};noteScroll.addView(notesColumn)
        for(i in 0 until minOf(notes.length(),4)) {
            val row=row(notesColumn,if(compact)56 else 70)
            row.addView(label("0${i+1}",14,accent,true),fixed(34,48))
            row.addView(label(notes.optString(i),if(compact)14 else 16,ink).apply{maxLines=3},weight())
        }
        left.addView(label("화면 속 조작의 결과입니다. 실차 숙련도는 평가하지 않습니다.",12,muted),full(if(compact)32 else 58))
        val right=LinearLayout(context).apply{orientation=VERTICAL;background=shape(panel,12);setPadding(dp(22),dp(if(compact)8 else 18),dp(22),dp(if(compact)8 else 18))}
        body.addView(right,LayoutParams(0,-1,1f))
        right.addView(label("어땠나요?",20,ink,true),full(if(compact)28 else 44))
        right.addView(label("내 느낌은 조작 기록과 따로 남겨요.",13,muted),full(if(compact)24 else 36))
        if(!historyReview&&!stored){
            right.addView(button("조금 편해졌어요","park_feel_good",assessment!="조금 편해졌어요"){assessment="조금 편해졌어요";render()},full(if(compact)48 else 58))
            gap(right,if(compact)4 else 8)
            right.addView(button("아직 어려웠어요","park_feel_hard",assessment!="아직 어려웠어요"){assessment="아직 어려웠어요";render()},full(if(compact)48 else 58))
            gap(right,if(compact)4 else 14)
            right.addView(button(if(recordEnabled())"이번 연습 저장" else "저장 없이 마침","park_save"){
                if(assessment=="미응답"){statusMessage="먼저 오늘의 느낌을 골라주세요.";render()}
                else {storeReview();render()}
            },full(if(compact)48 else 56))
        }else{
            right.addView(label(record.optString("selfAssessment","응답 없음"),21,ink,true),full(60))
            right.addView(label(if(historyReview||stored&&recordEnabled())"이 기기에 저장한 연습이에요." else "동의 범위에 따라 기록을 저장하지 않았어요.",14,muted),full(66))
        }
        statusMessage?.let{right.addView(label(it,13,amber),full(56))}
        if(historyReview&&records.size>1){
            val history=row(this,52)
            history.addView(label("기록 ${historyIndex+1} / ${records.size}",13,muted),weight())
            history.addView(button("이전 기록","park_history_older",true){if(historyIndex+1<records.size){historyIndex++;review=records[historyIndex];render()}},fixed(110,48))
            history.addView(button("다음 기록","park_history_newer",true){if(historyIndex>0){historyIndex--;review=records[historyIndex];render()}},fixed(110,48))
        }
        val actions=row(this,if(compact)56 else 62)
        startButton=button("같은 코스 다시 연습","park_retry"){
            selected=runCatching{ParkingCourse.valueOf(record.optString("course"))}.getOrDefault(selected);begin()
        }.also{actions.addView(it,weight(if(compact)48 else 56))}
        actions.addView(button("코스 선택","park_courses",true){courses()},weight(if(compact)48 else 56))
    }

    private fun select(course:ParkingCourse){selected=course;if(recordEnabled())prefs.edit().putString("course",course.name).apply();render()}
    private fun confirmDeleteHistory(){
        if(!active||closed||deleteDialog!=null)return
        val dialog=AlertDialog.Builder(context)
            .setTitle("주차 연습 기록을 삭제할까요?")
            .setMessage("이 기기에 저장된 주차 시뮬레이션 기록을 모두 삭제합니다. 삭제한 기록은 복원할 수 없어요.")
            .setNegativeButton("취소",null)
            .setPositiveButton("삭제"){_,_->
                if(active&&!closed){
                    val ok=runCatching{prefs.edit().remove("records").remove("lastStatus").commit()}.getOrDefault(false)
                    if(ok){records=emptyList();review=null;historyReview=false;historyIndex=0;stored=false;assessment="미응답"}
                    statusMessage=if(ok)"주차 연습 기록을 삭제했어요." else "기록을 삭제하지 못했어요. 다시 시도해 주세요."
                    Log.i("ParkingSim","history_deleted success=$ok")
                    render()
                }
            }.create()
        deleteDialog=dialog
        dialog.setOnDismissListener{if(deleteDialog===dialog)deleteDialog=null}
        dialog.show()
    }
    private fun dismissDeleteDialog(){deleteDialog?.dismiss();deleteDialog=null}
    private fun begin(){
        if(!active||closed)return
        stopVoice();main.removeCallbacks(ticker)
        releaseInputs();session=ParkingSimulationSession(selected);snapshot=session!!.snapshot()
        startedAt=System.currentTimeMillis();phaseEvents=JSONArray();lastPhase=null;lastGear=null
        lastX=Double.NaN;lastY=Double.NaN;lastCue=-1;stored=false;historyReview=false;review=null
        assessment="미응답";statusMessage=null;page=Page.DRIVE;running=true
        if(recordEnabled())prefs.edit().putString("lastStatus","running").apply()
        render();consume(snapshot!!);lastTick=SystemClock.elapsedRealtime();main.postDelayed(ticker,33)
    }
    private fun consume(value:ParkingSnapshot){
        snapshot=value;observe(value);updateScene(value)
        if(canInput()&&value.coachRevision!=lastCue){lastCue=value.coachRevision;stopVoice();onCue(value.coachCue)}
        if(page==Page.DRIVE&&value.isTerminal){
            running=false;main.removeCallbacks(ticker);releaseInputs()
            makeReview(value,if(value.completed)"가상 주차 마침" else "다시 연습할 부분이 있어요")
            page=Page.REFLECTION;render()
        }
    }
    private fun updateScene(value:ParkingSnapshot){
        scene?.update(value,!running||!active||value.paused)
        phaseText?.setTextIfChanged(if(!running||!active)"일시정지" else when(value.phase){
            ParkingPhase.PREPARE->"기어를 준비하세요"
            ParkingPhase.MANEUVER->"천천히 위치를 맞춰요"
            ParkingPhase.ALIGNED->"정지 후 P로 마무리"
            ParkingPhase.COMPLETE->"가상 주차 마침"
            ParkingPhase.FAILED->"연습을 멈췄어요"
        })
        cueText?.setTextIfChanged(value.coachCue.text)
        speedText?.setTextIfChanged(String.format(Locale.ROOT,"%.1f",value.speedKmh))
        alignmentText?.setTextIfChanged(if(value.insideTarget)"가상 목표 구역 안" else "가상 목표 구역 밖")
        alignmentText?.setTextColor(if(value.insideTarget)accent else muted)
        distanceText?.setTextIfChanged(String.format(Locale.KOREA,"목표와 방향 차이  %.0f°",value.angleErrorDegrees))
        val percent=(abs(value.steering)*100).roundToInt()
        steeringText?.setTextIfChanged(if(percent<2)"가상 핸들  /  중앙" else "가상 핸들  /  ${if(value.steering<0)"왼쪽" else "오른쪽"} $percent%")
        syncing=true;steeringSlider?.progress=(100+value.steering*100).roundToInt().coerceIn(0,200);syncing=false
        gearButtons.forEach{(gear,b)->style(b,value.gear==gear)}
        brakeHold?.apply{setTextIfChanged(if(brakeHeld)"브레이크 유지 중" else "브레이크 유지");style(this,brakeHeld)}
        gasButton?.let{style(it,gasTouch)};brakeButton?.let{style(it,brakeTouch||brakeHeld)}
        enabled()
    }
    private fun pause(){
        running=false;main.removeCallbacks(ticker);releaseInputs()
        session?.pause()?.let{snapshot=it;updateScene(it)}
        stopVoice()
        if(page==Page.DRIVE){if(recordEnabled())prefs.edit().putString("lastStatus","paused").apply();Log.i("ParkingSim","paused course=$selected")}
        enabled()
    }
    private fun resume(){
        if(!active||closed||page!=Page.DRIVE||running)return
        releaseInputs();running=true;session?.resume()?.let(::consume)
        lastTick=SystemClock.elapsedRealtime();main.removeCallbacks(ticker);main.postDelayed(ticker,33)
        Log.i("ParkingSim","resumed course=$selected")
    }
    private fun releaseInputs(){generation++;gasTouch=false;brakeTouch=false;brakeHeld=false;session?.releaseInputs()?.let{snapshot=it}}
    private fun endAttempt(outcome:String){pause();snapshot?.let{makeReview(it,outcome)};page=Page.REFLECTION;render()}
    private fun courses(){pause();session=null;snapshot=null;page=Page.COURSES;statusMessage=null;render()}
    private fun exit(){pause();session=null;snapshot=null;page=Page.COURSES;render();onExit()}
    private fun canInput()=active&&running&&!closed&&page==Page.DRIVE
    private fun enabled(){
        inputs.forEach{it.isEnabled=canInput();it.alpha=if(it.isEnabled)1f else .4f}
        pauseButton?.apply{visibility=if(running)VISIBLE else GONE;isEnabled=canInput()}
        resumeButton?.apply{visibility=if(running)GONE else VISIBLE;isEnabled=active&&!closed}
        finishButton?.isEnabled=canInput()&&snapshot?.let{it.gear==ParkingGear.P&&it.speedKmh<.05}==true
        repeatButton?.isEnabled=canInput();startButton?.isEnabled=active&&!closed
    }

    private fun pedal(title:String,key:String,gas:Boolean):Button {
        val b=button(title,key,true){
            if(!suppressClick&&canInput()){
                val token=generation
                if(gas)gasTouch=true else brakeTouch=true
                applyPedals()
                main.postDelayed({if(token==generation){if(gas)gasTouch=false else brakeTouch=false;applyPedals()}},250)
            }
        }
        b.setOnTouchListener{v,e->
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{if(canInput()){v.parent?.requestDisallowInterceptTouchEvent(true);if(gas)gasTouch=true else brakeTouch=true;v.isPressed=true;applyPedals()};true}
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{if(gas)gasTouch=false else brakeTouch=false;v.isPressed=false;applyPedals();v.parent?.requestDisallowInterceptTouchEvent(false)
                    if(e.actionMasked==MotionEvent.ACTION_UP){suppressClick=true;v.performClick();suppressClick=false};true}
                else->true
            }
        }
        inputs.add(b);return b
    }
    private fun applyPedals(){if(canInput())session?.setPedals(if(gasTouch)1.0 else 0.0,if(brakeTouch||brakeHeld)1.0 else 0.0)?.let(::consume)}
    private fun observe(v:ParkingSnapshot){
        if(v.phase!=lastPhase){phaseEvents.put(JSONObject().put("phase",v.phase.name).put("seconds",v.elapsedSeconds).put("x",v.pose.x).put("y",v.pose.y));lastPhase=v.phase}
        if(v.gear!=lastGear||lastX.isNaN()||abs(v.pose.x-lastX)>=.2||abs(v.pose.y-lastY)>=.2||v.isTerminal){
            Log.i("ParkingSim",String.format(Locale.ROOT,"course=%s phase=%s gear=%s x=%.2f y=%.2f yaw=%.1f speed=%.2f steering=%.2f inside=%s cue=%d",
                v.course,v.phase,v.gear,v.pose.x,v.pose.y,Math.toDegrees(v.pose.headingRadians),v.speedKmh,v.steering,v.insideTarget,v.coachRevision))
            lastGear=v.gear;lastX=v.pose.x;lastY=v.pose.y
        }
    }
    private fun makeReview(v:ParkingSnapshot,outcome:String){
        val notes=v.reflections.filter{it.isNotBlank()}.distinct().take(4).toMutableList()
        if(notes.isEmpty())notes.add("${courseName(v.course)}에서 ${v.gear.name} 기어와 핸들 조작을 연습했어요.")
        if(notes.size<2)notes.add(if(v.insideTarget)"화면 속 차량이 가상 목표 구역에 들어왔어요." else "다음에는 화면 속 목표 구역과 차량 방향을 함께 살펴봐요.")
        val incidents=JSONArray();v.interventions.forEach{incidents.put(JSONObject().put("kind",it.kind.name).put("at",it.atElapsedSeconds).put("cue",it.cue.text))}
        review=JSONObject().put("source","SIMULATION").put("course",v.course.name).put("courseTitle",courseName(v.course))
            .put("startedAt",startedAt).put("outcome",outcome).put("elapsedSeconds",v.elapsedSeconds)
            .put("distanceM",v.distanceM).put("insideTarget",v.insideTarget).put("angleErrorDegrees",v.angleErrorDegrees)
            .put("x",v.pose.x).put("y",v.pose.y).put("headingRadians",v.pose.headingRadians)
            .put("reflections",JSONArray(notes)).put("interventions",incidents).put("phaseEvents",phaseEvents)
        assessment="미응답";stored=false;historyReview=false;statusMessage=null
    }
    private fun storeReview(){
        val record=review?:return
        if(stored)return
        record.put("selfAssessment",assessment)
        if(recordEnabled()){
            // The host can erase history while this retained view is off screen.
            records=(listOf(record)+readRecords()).take(20)
            val ok=runCatching{prefs.edit().putString("records",JSONArray(records).toString()).putString("lastStatus","finished").commit()}.getOrDefault(false)
            statusMessage=if(ok)"이번 연습을 저장했어요." else "기기에 저장하지 못했어요. 현재 화면에서는 확인할 수 있어요."
            Log.i("ParkingSim","saved course=${record.optString("course")} source=SIMULATION count=${records.size} success=$ok")
            stored=ok
        }else{statusMessage="기록을 저장하지 않았어요.";Log.i("ParkingSim","not_saved consent=false");stored=true}
    }
    private fun readRecords():List<JSONObject> =runCatching{val a=JSONArray(prefs.getString("records","[]"));(0 until minOf(a.length(),20)).mapNotNull{a.optJSONObject(it)}}.getOrDefault(emptyList())

    private fun column(parent:LinearLayout,width:Int)=LinearLayout(context).apply{
        orientation=VERTICAL;background=shape(panel,12);setPadding(dp(18),dp(if(compact)8 else 12),dp(18),dp(if(compact)8 else 12));parent.addView(this,LayoutParams(dp(width),-1))
    }
    private fun label(text:String,size:Int,color:Int,bold:Boolean=false)=TextView(context).apply{
        this.text=text;textSize=size.toFloat();setTextColor(color);includeFontPadding=false;gravity=Gravity.CENTER_VERTICAL
        if(bold)setTypeface(typeface,Typeface.BOLD)
    }
    private fun button(title:String,key:String,secondary:Boolean=false,action:()->Unit)=PracticeButton(context).apply{
        text=title;textSize=18f;isAllCaps=false;gravity=Gravity.CENTER;minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0
        setPadding(dp(10),0,dp(10),0);contentDescription=key;tag=key;setTypeface(typeface,Typeface.BOLD)
        style(this,!secondary)
        val sourceIcon = when(key) {
            "park_course_reverse", "park_course_parallel" -> "vehicle"
            "park_start" -> "steering"
            "park_history" -> "menu"
            "park_center" -> "steering"
            else -> null
        }
        if(sourceIcon != null && key != "park_center") PleosUi.prefix(this, sourceIcon, 24)
        setOnClickListener{action()}
    }
    private fun style(b:Button,selected:Boolean){
        PleosUi.decorate(b, selected)
    }
    private fun shape(color:Int,radius:Int)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat()}
    private fun divider()=View(context).apply{setBackgroundColor(line)}
    private fun row(parent:LinearLayout,height:Int)=LinearLayout(context).apply{orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;parent.addView(this,full(height))}
    private fun weight(height:Int=-1,w:Float=1f)=LayoutParams(0,if(height<0)height else dp(height),w).apply{marginEnd=dp(7)}
    private fun fixed(width:Int,height:Int)=LayoutParams(dp(width),dp(height)).apply{marginEnd=dp(7)}
    private fun full(height:Int)=LayoutParams(-1,if(height<0)height else dp(height))
    private fun gap(parent:LinearLayout,height:Int){parent.addView(View(context),full(height))}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).roundToInt()
    private fun courseName(c:ParkingCourse)=if(c==ParkingCourse.REVERSE_BAY)"후진 주차" else "평행 주차"
    private fun date(millis:Long)=SimpleDateFormat("M월 d일 HH:mm",Locale.KOREA).format(Date(millis))
    private fun TextView.setTextIfChanged(value:String){if(text.toString()!=value)text=value}
}
