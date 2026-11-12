package kr.drivestep.tutor.workflow

import kotlin.math.abs

enum class TrafficActorKind { CAR, VAN, PEDESTRIAN }
data class TrafficActor(val id: String, val label: String, val kind: TrafficActorKind,
    val lateralM: Double, val relativeM: Double, val relativeSpeedMps: Double)
data class TrafficScene(val variant: Int, val title: String, val actors: List<TrafficActor>,
    val visibilityBlocked: Boolean, val corridorOccupied: Boolean, val explanation: String) {
    val entryAvailable get() = !visibilityBlocked && !corridorOccupied
}

/** Authored positions in a small teaching scene, never a real gap/ADAS safety threshold. */
object TrafficScenarios {
    fun titles(task: String): List<String> = when(task) {
        "D10" -> listOf("뒤에서 다가오는 차", "옆 차로 시야 가림")
        "D11" -> listOf("맞은편 차와 교행", "가려진 통로의 보행자")
        "D13" -> listOf("연속해서 다가오는 본선 차", "합류 공간을 막은 차량")
        else -> emptyList()
    }
    fun scene(task: String, variant: Int, elapsed: Double, distance: Double, egoSpeedMps: Double): TrafficScene? {
        val title=titles(task).getOrNull(variant) ?: return null
        fun car(id:String,label:String,at:Double,speed:Double,lane:Double=-3.6,kind:TrafficActorKind=TrafficActorKind.CAR)=
            TrafficActor(id,label,kind,lane,at+elapsed*speed-distance,speed-egoSpeedMps)
        val actors=when(task) {
            "D10" -> if(variant==0) listOf(car("approach","뒤에서 오는 차",-28.0,9.0))
                else listOf(car("occluder","시야를 가린 정차 차량",4.0,0.0,kind=TrafficActorKind.VAN))
            "D11" -> if(variant==0) listOf(car("oncoming","맞은편 차",24.0,-7.0,0.0))
                else listOf(car("van","주차된 승합차",18.0,0.0,-3.6,TrafficActorKind.VAN),
                    TrafficActor("walker","통로를 지나는 보행자",TrafficActorKind.PEDESTRIAN,
                        -3.8+elapsed*.8,18.0-distance,-egoSpeedMps))
            else -> if(variant==0) listOf(car("main1","본선 앞 차량",-10.0,10.0),car("main2","본선 뒤 차량",-32.0,10.0))
                else listOf(car("blocked","합류 공간의 정차 차량",4.0,0.0,kind=TrafficActorKind.VAN))
        }
        val hidden=when {
            task=="D11" && variant==1 -> actors.first { it.id=="walker" }.lateralM < -.8
            variant==1 && task!="D11" -> actors.any { it.kind==TrafficActorKind.VAN && it.relativeM in -4.0..25.0 }
            else -> false
        }
        val occupied=actors.any { actor -> when {
            task=="D11" && actor.kind==TrafficActorKind.PEDESTRIAN -> abs(actor.lateralM)<2.6 && actor.relativeM in -4.0..30.0
            task=="D11" -> abs(actor.lateralM)<1.5 && actor.relativeM in -8.0..45.0
            else -> actor.relativeM in -36.0..18.0
        } }
        val reason=when {
            hidden && task!="D11" -> "정차 차량 때문에 시야와 공간을 확인할 수 없어요. 기다려도 사라지지 않는 상황이므로 이번 시도를 취소하고 다른 상황을 연습해요."
            hidden -> "승합차 뒤 통로가 가려져 있어요. 정차를 유지하며 보행자가 보이는지 살펴요."
            occupied && task=="D11" -> if(variant==0) "맞은편 차량이 좁은 통로를 사용 중이에요. 교행 지점 앞에서 정차·대기해요." else "보행자가 가상 통로를 지나고 있어요. 통로를 다 지날 때까지 기다려요."
            occupied -> "가상 옆 차로에 접근 차량이 있어요. 진입을 보류하고 차로를 유지하거나 시도를 취소해요."
            else -> "그림의 가상 통로가 비었어요. 주변 확인 버튼을 새로 누른 뒤 과제 조작을 이어가요. 실제 진입 허가가 아니에요."
        }
        return TrafficScene(variant,title,actors,hidden,occupied,reason)
    }
}
