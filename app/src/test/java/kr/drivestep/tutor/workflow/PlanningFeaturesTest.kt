package kr.drivestep.tutor.workflow

import kr.drivestep.tutor.vehicle.Powertrain
import kr.drivestep.tutor.vehicle.TrainingVehicle
import kr.drivestep.tutor.vehicle.VehicleEducation
import org.junit.Assert.*
import org.junit.Test

class PlanningFeaturesTest {
    private val now = 1_794_385_800_000L
    private val place = WeatherPlace("서울", 37.56653, 126.97812)
    private fun reading(code: Int = 0, temperature: Double = 12.0, time: Long = now) = LiveWeatherReading(place, temperature, code, time)
    @Test fun gasolineAvanteUsesFuelAndNoEvLesson() {
        assertEquals(Powertrain.GASOLINE, TrainingVehicle.education.powertrain)
        assertFalse(TrainingVehicle.education.showEvEducation)
        assertEquals("연료 잔량", TrainingVehicle.education.energyCheck.first)
        assertTrue(VehicleEducation(Powertrain.ELECTRIC).showEvEducation)
        assertEquals("구동 배터리 잔량", VehicleEducation(Powertrain.ELECTRIC).energyCheck.first)
        Powertrain.entries.filter { it != Powertrain.ELECTRIC }.forEach { assertFalse(VehicleEducation(it).showEvEducation) }
        assertFalse(VehicleEducation(Powertrain.UNKNOWN).energyCheck.first.contains("배터리"))
    }
    @Test fun weatherRejectsExpiredFutureUnknownAndInvalidInputs() {
        assertTrue(reading(time = now - LiveWeatherReading.TTL).validAt(now))
        assertFalse(reading(time = now - LiveWeatherReading.TTL - 1).validAt(now))
        assertFalse(reading(time = now + 60_001).validAt(now))
        assertFalse(reading(code = 4).validAt(now))
        assertFalse(reading(temperature = Double.NaN).validAt(now))
        assertFalse(reading(temperature = 99.0).validAt(now))
        assertFalse(reading().copy(place = place.copy(latitude = 91.0)).validAt(now))
    }
    @Test fun rainAndColdCanBothProduceAdviceAndClearColdIsNotOmitted() {
        val wetCold = reading(61, 1.0)
        assertTrue(wetCold.advice.contains("비 예보")); assertTrue(wetCold.advice.contains("살얼음"))
        assertTrue(reading(0, 3.0).advice.contains("살얼음"))
        assertFalse(reading(0, 3.1).advice.contains("살얼음"))
        assertTrue(reading(66).advice.contains("어는 비"))
        assertEquals("눈", reading(85).condition)
        assertEquals("안개", reading(48).condition)
        assertEquals("뇌우", reading(99).condition)
    }
    @Test fun consentAndForegroundAreBothRequiredAndOldCallbacksAreRejected() {
        val state = LiveWeatherState()
        assertNull(state.begin()); state.activate(true); assertNull(state.begin())
        state.consent(true); val old = state.begin()!!
        state.activate(false); state.accept(old, reading(), now); assertNull(state.reading)
        state.activate(true); val current = state.begin()!!
        state.accept(old, reading(), now); assertTrue(state.loading)
        state.accept(current, reading(), now); assertEquals(reading(), state.fresh(now))
        assertNull(state.fresh(now + LiveWeatherReading.TTL + 1))
        state.consent(false); assertNull(state.reading); assertNull(state.begin())
    }
    @Test fun cancelledOrRevokedQueriesCannotPublishOrOverwriteANewRequest() {
        val state = LiveWeatherState().apply { activate(true); consent(true) }
        val first = state.begin()!!; state.cancel(); state.accept(first, reading(), now); assertNull(state.reading)
        val second = state.begin()!!; state.consent(false); state.fail(second, "late error")
        assertFalse(state.message.contains("late error"))
        state.consent(true); val third = state.begin()!!
        state.accept(third, reading(time = now - LiveWeatherReading.TTL - 1), now)
        assertNull(state.reading); assertFalse(state.loading)
    }
    @Test fun transmittedCoordinatesAreApproximate() {
        assertEquals(WeatherPlace("서울",37.57,126.98), place.approximate())
    }
    @Test fun unavailableLocationUsesExplicitBokjeongFallbackWithoutRelabellingItAsGps() {
        val fallback = WeatherPlaces.locationOrDefault(null)
        assertEquals("경기도 성남시 수정구 복정동",fallback.name)
        assertEquals(37.47,fallback.approximate().latitude,0.000001)
        assertEquals(127.13,fallback.approximate().longitude,0.000001)
        assertEquals(fallback,WeatherPlaces.locationOrDefault(place.copy(latitude = Double.NaN)))
        assertEquals(place,WeatherPlaces.locationOrDefault(place))
    }
    @Test fun manufacturerManualRequiresExactReferenceAndNeverPretendsToOperate() {
        val repo = ManualRepository()
        assertNull(repo.find(ManualVehicle("아반떼",null,null),ManualFeature.AC))
        assertNull(repo.find(Cn7Manual.AUTO_CLIMATE.copy(year = "2025"),ManualFeature.AC))
        for (vehicle in listOf(Cn7Manual.AUTO_CLIMATE, Cn7Manual.MANUAL_CLIMATE)) {
            for (feature in ManualFeature.entries.filter { it != ManualFeature.OTHER }) {
                val lesson = repo.find(vehicle,feature)!!
                assertEquals(InputSource.MANUAL, lesson.source); assertEquals(Cn7Manual.URL,lesson.sourceUrl)
                val session = ManualLearningSession(lesson)
                session.confirm(); assertFalse(session.learned)
                repeat(2) { session.next() }; session.operate(); assertFalse(session.simulatedOperation)
                session.next(); assertTrue(session.canConfirm); session.confirm(); assertTrue(session.learned)
            }
        }
        assertNull(repo.find(Cn7Manual.AUTO_CLIMATE,ManualFeature.OTHER))
        assertTrue(repo.find(Cn7Manual.MANUAL_CLIMATE,ManualFeature.DEFOG)!!.steps.last().contains("성에"))
    }
    @Test fun guideNeedsLastStepAndCorrectAnswerNotJustNextTaps() {
        for (guide in DrivingGuideCatalog.guides) {
            val state = DrivingGuideSession(guide)
            state.answer(guide.answer); assertFalse(state.completed)
            repeat(guide.steps.size + 2) { state.next() }; assertEquals(guide.steps.lastIndex,state.index)
            state.answer(1 - guide.answer); assertFalse(state.completed)
            state.answer(guide.answer); assertTrue(state.completed)
            state.previous(); assertFalse(state.completed)
        }
        assertTrue(DrivingGuideCatalog.guides.first { it.id == "double_parking" }.steps.first().contains("가로막아"))
    }
}
