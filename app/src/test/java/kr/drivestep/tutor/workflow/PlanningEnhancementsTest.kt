package kr.drivestep.tutor.workflow

import org.junit.Assert.*
import org.junit.Test

class PlanningEnhancementsTest {
    private val now = 1_794_385_800_000L
    private val reading = LiveWeatherReading(WeatherPlaces.DEFAULT, 2.0, 61, now)
    @Test fun autoWeatherRequiresOptInConsentForegroundAndFreshReading() {
        val state = WeatherAutoAnnouncement()
        val unexpected: (String) -> Boolean = { error("Must not speak") }
        assertFalse(state.request(reading, now, true, true, unexpected))
        state.enabled = true
        assertFalse(state.request(reading, now, false, true, unexpected))
        assertFalse(state.request(reading, now, true, false, unexpected))
        assertFalse(state.request(null, now, true, true, unexpected))
        assertFalse(state.request(reading, now + LiveWeatherReading.TTL + 1, true, true, unexpected))
        assertFalse(state.request(reading.copy(modelTime = now + 60_001), now, true, true, unexpected))
    }
    @Test fun autoWeatherSkipsMutedRequestsAndDoesNotRepeatSameForecast() {
        val state = WeatherAutoAnnouncement().apply { enabled = true }
        assertFalse(state.request(reading, now, true, true) { false })
        var calls = 0
        val speak: (String) -> Boolean = { calls++; assertTrue(it.contains("복정동")); assertTrue(it.contains("살얼음")); true }
        assertTrue(state.request(reading, now, true, true, speak))
        assertFalse(state.request(reading, now, true, true, speak))
        state.enabled = false; state.enabled = true
        assertFalse(state.request(reading, now, true, true, speak))
        assertTrue(state.request(reading.copy(modelTime = now + 900_000), now + 900_000, true, true, speak))
        assertEquals(2, calls)
    }
    @Test fun recommendationRequiresConsentAndAnExplicitValidDifficulty() {
        val evidence = listOf(DifficultyRecord("ok", "D01", "D09", now), DifficultyRecord("skip", "D03", null, now),
            DifficultyRecord("bad", "D03", "unknown", now), DifficultyRecord("bad-task", "unknown", "D02", now))
        assertTrue(PracticeRecommendations.suggest(evidence, false, now).isEmpty())
        assertEquals(listOf("D09"), PracticeRecommendations.suggest(evidence, true, now).map { it.task.id })
        assertTrue(PracticeRecommendations.suggest(evidence.drop(1), true, now).isEmpty())
    }
    @Test fun recommendationsIgnoreStaleFutureDuplicatesAndBlankAttempts() {
        val evidence = listOf(DifficultyRecord("old", "D01", "D01", now - PracticeRecommendations.MAX_AGE - 1),
            DifficultyRecord("future", "D01", "D01", now + 1), DifficultyRecord("", "D01", "D01", now),
            DifficultyRecord("edge", "D01", "D02", now - PracticeRecommendations.MAX_AGE),
            DifficultyRecord("same", "D01", "D09", now), DifficultyRecord("same", "D01", "D09", now))
        val result = PracticeRecommendations.suggest(evidence, true, now)
        assertEquals(listOf("D09", "D02"), result.map { it.task.id })
        assertTrue(result.all { it.reports == 1 })
    }
    @Test fun recommendationRanksBySelfReportFrequencyThenRecencyWithThreeOptions() {
        val evidence = listOf(DifficultyRecord("1", "D01", "D09", now - 30), DifficultyRecord("2", "D01", "D09", now - 20),
            DifficultyRecord("3", "D01", "D03", now), DifficultyRecord("4", "D01", "D02", now - 10),
            DifficultyRecord("5", "D01", "D01", now - 15))
        val result = PracticeRecommendations.suggest(evidence, true, now)
        assertEquals(listOf("D09", "D03", "D02"), result.map { it.task.id })
        assertEquals(2, result.first().reports)
    }
    @Test fun manualAddsVerifiedVisibilityControlsWithDistinctSources() {
        val expected = mapOf(ManualFeature.WIPER to "5-67", ManualFeature.LIGHTS to "5-62", ManualFeature.REAR_DEFOG to "5-89")
        expected.forEach { (feature, section) ->
            for (vehicle in listOf(Cn7Manual.AUTO_CLIMATE, Cn7Manual.MANUAL_CLIMATE)) {
                val lesson = Cn7Manual.find(vehicle, feature)!!
                assertTrue(lesson.section.startsWith(section)); assertEquals(4, lesson.steps.size)
                assertEquals(InputSource.MANUAL, lesson.source)
            }
        }
    }
}
