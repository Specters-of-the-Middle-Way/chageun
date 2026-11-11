package kr.drivestep.tutor.vehicle

enum class Powertrain { GASOLINE, DIESEL, HYBRID, PLUG_IN_HYBRID, ELECTRIC, UNKNOWN }

/** Presentation follows the chosen reference vehicle, never an assumption about the emulator. */
data class VehicleEducation(val powertrain: Powertrain) {
    val showEvEducation get() = powertrain == Powertrain.ELECTRIC
    val energyCheck: Pair<String, String> get() = when (powertrain) {
        Powertrain.ELECTRIC -> "구동 배터리 잔량" to "계기판의 충전 잔량·주행 가능 거리와 충전 계획"
        Powertrain.PLUG_IN_HYBRID -> "연료와 충전 잔량" to "계기판의 연료·충전 잔량과 오늘의 이동 계획"
        Powertrain.GASOLINE, Powertrain.DIESEL, Powertrain.HYBRID -> "연료 잔량" to "계기판의 연료·주행 가능 거리와 주유 계획"
        Powertrain.UNKNOWN -> "주행 에너지 확인" to "차량 동력 방식을 확인하고 계기판의 주행 가능 거리를 살펴요"
    }
}

/**
 * App reference vehicle, not a vehicle identity detected by the Pleos SDK.
 * Hyundai AVANTE CN7 Smartstream gasoline 1.6, standard body (2023 facelift).
 * https://www.hyundai.com/kr/ko/brand/brandstory/model/avante-history/2023-the-new-avante
 * Body dimensions exclude mirrors; they do not establish physical seat/steering calibration.
 */
object TrainingVehicle {
    val education = VehicleEducation(Powertrain.GASOLINE)
    const val NAME = "아반떼"
    const val VARIANT = "CN7 · 스마트스트림 가솔린 1.6"
    const val LENGTH_MM = 4710
    const val WIDTH_MM = 1825
    const val HEIGHT_MM = 1420
    const val WHEELBASE_MM = 2720
}
