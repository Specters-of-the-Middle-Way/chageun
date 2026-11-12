package kr.drivestep.tutor.workflow

data class ParkingPlacement(val x: Float,val y: Float,val width: Float,val height: Float)
data class ParkingChoice(val id:String,val title:String,val correct:Boolean,val explanation:String,
    val placement:ParkingPlacement?=null)
data class DoubleParkingSituation(val id:String,val title:String,val prompt:String,val freeBay:Boolean,
    val choices:List<ParkingChoice>)

object DoubleParkingCatalog {
    private val exit=ParkingPlacement(.29f,.47f,.32f,.13f)
    private val walk=ParkingPlacement(.77f,.55f,.14f,.28f)
    private val bay=ParkingPlacement(.61f,.12f,.13f,.25f)
    val situations=listOf(
        DoubleParkingSituation("exit","다른 차가 나갈 길","정규 주차 공간이 한 곳 비어 있어요. 어디를 선택할까요?",true,listOf(
            ParkingChoice("front","주차된 차 바로 앞",false,"차 앞을 가로막으면 그림의 출차 경로를 사용할 수 없어요. 정규 공간을 먼저 선택해요.",exit),
            ParkingChoice("bay","비어 있는 정규 주차 공간",true,"빈 정규 공간을 선택하면 다른 차의 출차 경로와 오른쪽 통행 공간을 함께 비워 둘 수 있어요.",bay),
            ParkingChoice("walk","오른쪽 통행 공간",false,"차량이 지나갈 수 있어 보여도 보행·비상 통행 공간을 막으면 안 돼요. 빈 정규 공간이 있어요.",walk))),
        DoubleParkingSituation("passage","사람과 비상 차량의 통로","정규 공간이 모두 찼어요. 오른쪽 표시 구역은 보행·비상 통행 공간이에요.",false,listOf(
            ParkingChoice("walk","표시된 통로에 잠깐 세우기",false,"잠깐이라도 보행자와 비상 통행을 막게 돼요. 통로를 주차 공간으로 사용하지 않아요.",walk),
            ParkingChoice("front","다른 차 앞에 세우고 자리 비우기",false,"보행 통로를 피했어도 다른 차의 출차를 막아요. 연락만 가능하면 된다고 가정하지 않아요.",exit),
            ParkingChoice("coordinate","통로를 비우고 관리자와 이동·주차 위치 조율",true,"정규 공간이 없으면 출차와 통행을 막지 않는 위치를 관리자와 조율하거나 다른 주차 공간을 찾아요."))),
        DoubleParkingSituation("departure","막힌 차가 출차해야 해요","내 차가 다른 차 앞에 있고 차주가 출차를 요청했어요. 빈 정규 공간도 생겼어요.",true,listOf(
            ParkingChoice("leave","연락처만 남기고 떠나기",false,"연락처가 있어도 지금 출차 경로가 열리는 것은 아니에요. 직접 이동할 수 있도록 조율해야 해요.",exit),
            ParkingChoice("move","차주와 조율해 직접 이동하고 정규 공간 선택",true,"출차 요청을 확인하고 직접 차량 이동을 조율해 경로를 비워요. 이동 뒤 사람과 차량의 통로도 직접 확인해요.",bay),
            ParkingChoice("push","다른 사람이 차를 밀 수 있다고 가정하기",false,"차량 사양·경사·고정 상태를 모르는 채 이동을 맡기지 않아요. N단 유지나 주차 브레이크 해제를 공통 조작으로 안내하지 않아요.",exit))))
}

/** Requires a reasoned choice in every situation; next/elapsed time alone cannot finish. */
class DoubleParkingSession {
    var index=0; private set
    var paused=false; private set
    var completed=false; private set
    private val answers=mutableMapOf<String,String>()
    val situation get()=DoubleParkingCatalog.situations[index]
    val selected get()=situation.choices.find { it.id==answers[situation.id] }
    val correctCount get()=DoubleParkingCatalog.situations.count { item -> item.choices.any { it.id==answers[item.id] && it.correct } }
    val canAdvance get()=!paused && selected?.correct==true
    fun choose(id:String) { if(!paused && !completed && situation.choices.any { it.id==id }) answers[situation.id]=id }
    fun next() { if(canAdvance && !completed) { if(index<DoubleParkingCatalog.situations.lastIndex) index++ else completed=correctCount==DoubleParkingCatalog.situations.size } }
    fun previous() { if(!paused && index>0) index-- }
    fun retryCurrent() { if(!paused) { answers.remove(situation.id); completed=false } }
    fun restart() { answers.clear(); index=0; completed=false; paused=false }
    fun pause() { paused=true }
    fun resume() { paused=false }
    fun reviewAt(index:Int) { if(completed && index in DoubleParkingCatalog.situations.indices) this.index=index }
}
