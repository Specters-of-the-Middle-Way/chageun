package kr.drivestep.tutor.workflow

enum class RoadIllustration(val caption: String) {
    LOOK_AHEAD("먼 도로 흐름을 먼저 살펴요"), NEAR_FIELD("가까운 차로와 주변도 함께 확인해요"),
    MIRRORS("미러를 짧게 확인한 뒤 전방으로 돌아와요"), LANE_CENTER("차로 양쪽을 보며 진행 방향을 유지해요"),
    WHITE_DASH("같은 방향 차로 사이의 흰색 점선"), WHITE_SOLID("같은 방향 차로 사이의 흰색 실선"),
    YELLOW_CENTER("반대 방향을 나누는 황색 중앙 실선"), STOP_LINE("횡단보도 앞 정지선")
}

/** Time headway, not time to collision or a calculation of stopping distance. */
object FollowingGap {
    const val TRAINING_SECONDS = 3.0
    fun seconds(speedKmh: Double, gapMeters: Double?): Double? {
        if (!speedKmh.isFinite() || speedKmh <= 1.0 || gapMeters == null || !gapMeters.isFinite() || gapMeters < 0) return null
        return gapMeters / (speedKmh / 3.6)
    }
    fun distance(speedKmh: Double, seconds: Double): Double? {
        if (!speedKmh.isFinite() || speedKmh <= 1.0 || !seconds.isFinite() || seconds <= 0) return null
        return speedKmh / 3.6 * seconds
    }
}

/** Explicit classroom examples; never populated from vehicle or obstacle inputs. */
class GapStudyState {
    var speedKmh = 30.0; private set
    var gapMeters = 30.0; private set
    var targetSeconds = FollowingGap.TRAINING_SECONDS; private set
    fun speed(value: Double) { if (value.isFinite() && value in 0.0..120.0) speedKmh = value }
    fun gap(value: Double) { if (value.isFinite() && value in 0.0..200.0) gapMeters = value }
    fun target(value: Double) { if (value == 3.0 || value == 6.0) targetSeconds = value }
    val headwaySeconds get() = FollowingGap.seconds(speedKmh, gapMeters)
    val targetMeters get() = FollowingGap.distance(speedKmh, targetSeconds)
    val reachesExample get() = headwaySeconds?.let { it >= targetSeconds }
}
