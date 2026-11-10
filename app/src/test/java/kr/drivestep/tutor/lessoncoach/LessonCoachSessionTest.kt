package kr.drivestep.tutor.lessoncoach

import org.junit.Assert.*
import org.junit.Test

class LessonCoachSessionTest {
    @Test fun spokenConversationEndPausesAndClearsUnconfirmedDirection() {
        val s = session()
        val candidate = s.acceptUtterance("왼쪽으로 차로 변경", SpeechInputSource.PLEOS_STT, 100)
        assertTrue(candidate.awaitingConfirmation)
        val ended = s.acceptUtterance("대화 종료", SpeechInputSource.PLEOS_STT, 200)
        assertEquals(LessonPhase.PAUSED, ended.phase)
        assertFalse(ended.awaitingConfirmation)
        assertEquals(LessonDirection.UNKNOWN, ended.direction)
        assertTrue(ended.events.any { it.kind == LessonEventKind.UTTERANCE && it.detail == "대화 종료" })
    }

    private fun session() = LessonCoachSession().apply { start(LessonTopic.LANE_CHANGE, 0) }
    private fun say(session: LessonCoachSession, text: String, at: Long = 100) =
        session.acceptUtterance(text, SpeechInputSource.TEXT_DEMO, at)
    private fun vehicle(at: Long, signal: LessonSignal = LessonSignal.OFF, source: VehicleInputSource = VehicleInputSource.SIMULATION, initial: Boolean = false) =
        VehicleObservation(at, source, 20.0, signal, LessonGear.D, false, 0.0, initial)
    private fun nav(at: Long, id: String = "turn-one", direction: LessonDirection = LessonDirection.RIGHT, distance: Double = 200.0,
                    source: NavigationInputSource = NavigationInputSource.SIMULATION) =
        NavigationObservation(at, source, id, direction, distance)

    @Test fun ambiguousLaneChangeAsksDirectionAndKeepsReportedTraffic() {
        val s = session()
        val asked = say(s, "차로 변경 도와줘 차가 빽빽해")
        assertTrue(asked.awaitingDirection)
        assertEquals(TrafficDensity.HIGH, asked.reportedTraffic.density)
        val answer = say(s, "왼쪽", 500)
        assertFalse(answer.awaitingDirection)
        assertEquals(LessonDirection.LEFT, answer.direction)
        assertEquals(TrafficDensity.HIGH, answer.reportedTraffic.density)
        assertTrue(answer.reply.contains("말씀하셨군요"))
    }

    @Test fun unclearAnswerDoesNotCreateAnEndlessQuestionnaire() {
        val s = session()
        say(s, "차로 변경", 100)
        val answer = say(s, "잘 모르겠어", 500)
        assertFalse(answer.awaitingDirection)
        assertEquals(LessonDirection.UNKNOWN, answer.direction)
        assertFalse(answer.reply.contains("어느 쪽"))
    }

    @Test fun expiredFollowupDoesNotReuseOldTrafficReport() {
        val s = session()
        say(s, "차로 변경 차가 많아", 100)
        val answer = say(s, "오른쪽", 20_000)
        assertEquals(TrafficDensity.UNKNOWN, answer.reportedTraffic.density)
        assertFalse(answer.awaitingDirection)
    }

    @Test fun correctedDirectionUsesTheExplicitReplacement() {
        val answer = say(session(), "오른쪽 말고 왼쪽 차로 변경 도와줘")
        assertEquals(LessonDirection.LEFT, answer.direction)
        assertFalse(answer.reply.contains("오른쪽 차로 변경"))
    }

    @Test fun shortNaturalRequestAndTrafficAreHandledInOneTurn() {
        val result = say(session(), "왼쪽으로 가고 싶은데 차가 많아")
        assertEquals(LessonDirection.LEFT, result.direction)
        assertEquals(TrafficDensity.HIGH, result.reportedTraffic.density)
        assertFalse(result.awaitingDirection)
        assertTrue(result.reply.contains("왼쪽 차로 변경 준비"))
    }

    @Test fun turnRequestDoesNotBecomeLaneChangeRequest() {
        val s = session()
        val answer = say(s, "좌회전해야 해")
        assertTrue(answer.reply.contains("회전 목적"))
        assertFalse(answer.reply.contains("왼쪽 차로 변경 준비"))
        s.acceptVehicle(vehicle(200, initial = true), 200)
        val after = s.acceptVehicle(vehicle(400, LessonSignal.LEFT), 400)
        assertEquals(answer.cueRevision, after.cueRevision)
    }

    @Test fun sparseTrafficNeverAuthorizesEntry() {
        val s = session()
        val answer = say(s, "왼쪽 차로로 가고 싶어 차가 많지 않아")
        assertEquals(TrafficDensity.LOW, answer.reportedTraffic.density)
        assertTrue(answer.reply.contains("대신하지는 않아요"))
        val permission = say(s, "지금 들어가도 돼?", 1000)
        assertTrue(permission.reply.contains("진입 시점은 판단할 수 없어요"))
        assertFalse(permission.awaitingDirection)
    }

    @Test fun reportsAreNotVehicleMeasurements() {
        val result = say(session(), "왼쪽 차로 변경 옆 차들이 멈춰 있어")
        assertEquals(TrafficFlow.STOPPED, result.reportedTraffic.flow)
        assertNull(result.vehicle)
        assertTrue(result.events.any { it.kind == LessonEventKind.UTTERANCE && it.source == LessonEvidence.DRIVER_REPORTED })
        assertFalse(result.events.any { it.source == LessonEvidence.SDK })
    }

    @Test fun reportAboutOppositeSideIsNotAttachedToCurrentRequest() {
        val s = session()
        say(s, "오른쪽 차로 변경", 100)
        val report = say(s, "왼쪽 차가 많아", 1000)
        assertTrue(report.awaitingDirection)
        assertEquals(LessonDirection.UNKNOWN, report.direction)
        assertEquals(TrafficDensity.UNKNOWN, report.reportedTraffic.density)
        val clarified = say(s, "오른쪽", 1500)
        assertEquals(LessonDirection.RIGHT, clarified.direction)
        assertEquals(TrafficDensity.UNKNOWN, clarified.reportedTraffic.density)
    }

    @Test fun duplicateFinalResultDoesNotRepeatSpeech() {
        val s = session()
        val first = say(s, "왼쪽 차로 변경", 100)
        val duplicate = say(s, "왼쪽 차로 변경", 200)
        assertEquals(first, duplicate)
        assertTrue(say(s, "다시 설명해 줘", 300).cueRevision > first.cueRevision)
    }

    @Test fun pauseDropsPendingQuestionAndDoesNotSpeakLifecyclePrompt() {
        val s = session()
        val before = say(s, "차로 변경 차가 많아")
        val paused = s.pause(200, "화면을 나감")
        assertEquals(before.cueRevision, paused.cueRevision)
        assertFalse(paused.awaitingDirection)
        assertEquals(LessonPhase.PAUSED, paused.phase)
        assertEquals(paused, say(s, "왼쪽", 300))
        val resumed = s.resume(400)
        assertEquals(TrafficDensity.UNKNOWN, resumed.reportedTraffic.density)
        assertNull(resumed.vehicle)
    }

    @Test fun cancelDoesNotLeaveOldDirectionOrTraffic() {
        val s = session()
        say(s, "왼쪽 차로 변경 차가 계속 와")
        val result = say(s, "취소", 1000)
        assertEquals(LessonDirection.UNKNOWN, result.direction)
        assertNull(result.reportedTraffic.approaching)
        assertFalse(result.awaitingDirection)
    }

    @Test fun initialVehicleSnapshotDoesNotAcknowledgeAnAction() {
        val s = session()
        val request = say(s, "왼쪽 차로 변경")
        val initial = s.acceptVehicle(vehicle(200, LessonSignal.LEFT, initial = true), 200)
        assertEquals(request.cueRevision, initial.cueRevision)
        assertTrue(initial.events.last().vehicle!!.isInitial)
    }

    @Test fun realSignalTransitionAcknowledgedOnlyOnce() {
        val s = session()
        say(s, "왼쪽 차로 변경")
        s.acceptVehicle(vehicle(200, initial = true), 200)
        val event = s.acceptVehicle(vehicle(400, LessonSignal.LEFT), 400)
        assertTrue(event.reply.contains("왼쪽 방향지시등이 켜졌어요"))
        val repeated = s.acceptVehicle(vehicle(600, LessonSignal.LEFT), 600)
        assertEquals(event.cueRevision, repeated.cueRevision)
        assertFalse(event.reply.contains("변경 성공"))
    }

    @Test fun staleSignalIsUnknownEvenWhenAnotherChannelIsFresh() {
        val s = session()
        say(s, "왼쪽 차로 변경")
        s.acceptVehicle(vehicle(200, initial = true), 200)
        val sample = vehicle(3000, LessonSignal.LEFT).copy(signalAtMillis = 100, speedAtMillis = 3000)
        val result = s.acceptVehicle(sample, 3000)
        assertEquals(LessonSignal.UNKNOWN, result.vehicle!!.signal)
        assertEquals(20.0, result.vehicle!!.speedKmh!!, 0.001)
        assertFalse(result.reply.contains("방향지시등이 켜졌어요"))
    }

    @Test fun refreshingAnExistingSignalInAnInitialReadIsNotAnAction() {
        val s = session()
        val request = say(s, "왼쪽 차로 변경")
        s.acceptVehicle(vehicle(200, LessonSignal.LEFT, initial = true), 200)
        s.tick(2000)
        val refreshed = s.acceptVehicle(vehicle(2100, LessonSignal.LEFT, initial = true), 2100)
        assertEquals(request.cueRevision, refreshed.cueRevision)
        assertEquals(LessonSignal.LEFT, refreshed.vehicle!!.signal)
    }

    @Test fun tickExpiresEveryChannelAndReportedTraffic() {
        val s = session()
        say(s, "왼쪽 차로 변경 차가 많아")
        s.acceptVehicle(vehicle(200), 200)
        val result = s.tick(20_000)
        assertNull(result.vehicle!!.speedKmh)
        assertNull(result.vehicle!!.brakePressed)
        assertNull(result.vehicle!!.frontWheelAngleDegrees)
        assertEquals(LessonSignal.UNKNOWN, result.vehicle!!.signal)
        assertEquals(LessonGear.UNKNOWN, result.vehicle!!.gear)
        assertEquals(TrafficDensity.UNKNOWN, result.reportedTraffic.density)
        assertEquals(LessonDirection.UNKNOWN, result.direction)
    }

    @Test fun nonFiniteAndImpossibleReadingsAreUnknown() {
        val s = session()
        val result = s.acceptVehicle(vehicle(100).copy(speedKmh = Double.NaN, frontWheelAngleDegrees = 100.0), 100)
        assertNull(result.vehicle!!.speedKmh)
        assertNull(result.vehicle!!.frontWheelAngleDegrees)
    }

    @Test fun futureAndOutOfOrderVehicleSamplesCannotOverwrite() {
        val s = session()
        val accepted = s.acceptVehicle(vehicle(100), 100)
        assertEquals(accepted, s.acceptVehicle(vehicle(99, LessonSignal.LEFT), 101))
        assertEquals(accepted, s.acceptVehicle(vehicle(1000, LessonSignal.RIGHT), 102))
    }

    @Test fun vehicleSourceSwitchClearsDialogueAndCannotAcknowledgeAction() {
        val s = session()
        say(s, "왼쪽 차로 변경 차가 많아")
        s.acceptVehicle(vehicle(200), 200)
        val result = s.acceptVehicle(vehicle(300, LessonSignal.LEFT, VehicleInputSource.SDK), 300)
        assertEquals(LessonDirection.UNKNOWN, result.direction)
        assertEquals(TrafficDensity.UNKNOWN, result.reportedTraffic.density)
        assertEquals(VehicleInputSource.SDK, result.vehicle!!.source)
        assertFalse(result.reply.contains("방향지시등이 켜졌어요"))
    }

    @Test fun navigationPreparesOncePerManeuverAndSkipsAlreadyLitSignal() {
        val s = session()
        s.acceptVehicle(vehicle(100, LessonSignal.RIGHT, initial = true), 100)
        val first = s.acceptNavigation(nav(200), 200)
        assertTrue(first.reply.contains("방향지시등은 켜져 있어요"))
        val again = s.acceptNavigation(nav(300, distance = 150.0), 300)
        assertEquals(first.cueRevision, again.cueRevision)
        val next = s.acceptNavigation(nav(400, id = "turn-two", direction = LessonDirection.LEFT), 400)
        assertTrue(next.cueRevision > again.cueRevision)
        assertTrue(next.reply.contains("왼쪽 회전"))
    }

    @Test fun navigationOutsidePreparationWindowDoesNotSpeak() {
        val s = session()
        val before = s.snapshot().cueRevision
        assertEquals(before, s.acceptNavigation(nav(100, distance = 1500.0), 100).cueRevision)
        assertEquals(before, s.acceptNavigation(nav(200, distance = 10.0), 200).cueRevision)
    }

    @Test fun navigationDoesNotMixWithOppositeVehicleSource() {
        val s = session()
        s.acceptVehicle(vehicle(100, source = VehicleInputSource.SDK), 100)
        val result = s.acceptNavigation(nav(200), 200)
        assertNull(result.navigation)
        val actual = s.acceptNavigation(nav(300, source = NavigationInputSource.NAVI), 300)
        assertEquals(NavigationInputSource.NAVI, actual.navigation!!.source)
    }

    @Test fun freshInvalidNavigationClearsOldManeuverAndAllowsNewGuidance() {
        val s = session()
        val first = s.acceptNavigation(nav(100), 100)
        val invalid = s.acceptNavigation(nav(200, direction = LessonDirection.UNKNOWN, distance = Double.NaN), 200)
        assertNull(invalid.navigation)
        val recovered = s.acceptNavigation(nav(300), 300)
        assertTrue(recovered.cueRevision > first.cueRevision)
    }

    @Test fun staleNavigationCannotAcknowledgeLaterSignalAndRecoveryDoesNotRepeat() {
        val s = session()
        s.acceptVehicle(vehicle(100), 100)
        val prepared = s.acceptNavigation(nav(200), 200)
        s.tick(2000)
        val stale = s.acceptVehicle(vehicle(2100, LessonSignal.RIGHT), 2100)
        assertEquals(prepared.cueRevision, stale.cueRevision)
        assertNull(stale.navigation)
        val recovered = s.acceptNavigation(nav(2200), 2200)
        assertEquals(prepared.cueRevision, recovered.cueRevision)
    }

    @Test fun requestHasPriorityOverNavigationPrompt() {
        val s = session()
        val speech = say(s, "왼쪽 차로 변경")
        assertEquals(speech.cueRevision, s.acceptNavigation(nav(200), 200).cueRevision)
    }

    @Test fun observationsAreBoundedAndRetainOwnVehicleOnly() {
        val s = LessonCoachSession(maxEvents = 8)
        s.start(LessonTopic.START_STOP, 0)
        repeat(30) { index -> s.acceptVehicle(vehicle((index + 1) * 1000L).copy(speedKmh = index.toDouble()), (index + 1) * 1000L) }
        val result = s.snapshot()
        assertEquals(8, result.events.size)
        val observations = result.events.filter { it.kind == LessonEventKind.VEHICLE }
        assertEquals(6, observations.size)
        assertTrue(observations.all { it.vehicle != null })
        assertEquals(listOf(LessonEventKind.START, LessonEventKind.GUIDANCE), result.events.take(2).map { it.kind })
        assertEquals(29.0, result.events.last().vehicle!!.speedKmh!!, 0.001)
    }

    @Test fun voiceAndReadbackRemainAvailableForReviewAfterLongObservation() {
        val s = session()
        val question = s.acceptUtterance("왼쪽 차로 변경 차가 많아", SpeechInputSource.PLEOS_STT, 100)
        val conversationIds = question.events.map { it.id }.toSet()
        assertTrue(question.awaitingConfirmation)
        repeat(200) { index ->
            val at = (index + 1) * 1000L
            s.acceptVehicle(vehicle(at), at)
            s.tick(at)
        }
        val result = s.finish(LessonReflection.OKAY, 201_000)
        assertEquals(160, result.events.size)
        assertTrue(result.events.map { it.id }.containsAll(conversationIds))
        assertTrue(result.events.any { it.kind == LessonEventKind.UTTERANCE && it.title == "운전자 음성" })
        assertTrue(result.events.any { it.kind == LessonEventKind.GUIDANCE && it.detail.contains("왼쪽 차로 변경 준비가 맞나요") })
        assertTrue(result.events.any { it.kind == LessonEventKind.FINISH })
        assertTrue(result.events.zipWithNext().all { (before, after) -> before.id < after.id && before.atMillis <= after.atMillis })
        assertEquals(200_000L, result.events.last { it.vehicle != null }.atMillis)
    }

    @Test fun conversationOnlyOverflowStillDropsOldestAndStaysBounded() {
        val s = LessonCoachSession(maxEvents = 5)
        val firstId = s.start(LessonTopic.LANE_CHANGE, 0).events.first().id
        repeat(20) { index -> say(s, "다시 설명해 줘", (index + 1) * 1000L) }
        val events = s.snapshot().events
        assertEquals(5, events.size)
        assertTrue(events.all { it.vehicle == null })
        assertTrue(events.first().id > firstId)
        assertTrue(events.zipWithNext().all { (before, after) -> after.id == before.id + 1 })
        assertEquals(20_000L, events.last().atMillis)
    }

    @Test fun fullConversationRetainsNewestVehicleAndReplacesOnlyItsPreviousSample() {
        val s = LessonCoachSession(maxEvents = 5)
        s.start(LessonTopic.LANE_CHANGE, 0)
        repeat(3) { index -> say(s, "다시 설명해 줘", (index + 1) * 1000L) }
        val conversationIds = s.snapshot().events.map { it.id }
        assertEquals(5, conversationIds.size)

        val first = s.acceptVehicle(vehicle(4000), 4000).events
        assertEquals(5, first.size)
        assertEquals(conversationIds.drop(1), first.filter { it.vehicle == null }.map { it.id })
        assertEquals(4000L, first.last().vehicle!!.atMillis)

        val next = s.acceptVehicle(vehicle(5000), 5000).events
        assertEquals(5, next.size)
        assertEquals(conversationIds.drop(1), next.filter { it.vehicle == null }.map { it.id })
        assertEquals(5000L, next.single { it.vehicle != null }.atMillis)
        assertTrue(next.zipWithNext().all { (before, after) -> before.id < after.id })
    }

    @Test fun nextLessonDependsOnExplicitReflectionNotBraking() {
        val s = session()
        s.acceptVehicle(vehicle(100).copy(speedKmh = 50.0), 100)
        s.acceptVehicle(vehicle(200).copy(speedKmh = 0.0, brakePressed = true), 200)
        val result = s.finish(LessonReflection.DIFFICULT, 300)
        assertEquals(LessonTopic.LANE_CHANGE, result.nextLesson)
        assertTrue(result.nextLessonReason.contains("복습 응답"))
        assertEquals(LessonPhase.FINISHED, result.phase)
        assertEquals(result, say(s, "오른쪽 차로 변경", 400))
    }

    @Test fun easyReflectionSuggestsAnotherTopicWithoutCertifyingMastery() {
        val s = session()
        val result = s.finish(LessonReflection.EASY, 100)
        assertEquals(LessonTopic.TURN_PREP, result.nextLesson)
        assertTrue(result.nextLessonReason.contains("숙련도 인증은 아니에요"))
    }

    @Test fun realSpokenTrafficWithActionUncertaintySurvivesDirectionAndConfirmation() {
        val s = session()
        val request = s.acceptUtterance(
            "좌회전으로 끼어들게 할 건데 차가 너무 많아서 어떻게 해야 될지 모르겠어",
            SpeechInputSource.PLEOS_STT, 100)
        assertTrue(request.awaitingDirection)
        assertEquals(TrafficDensity.HIGH, request.reportedTraffic.density)
        val direction = s.acceptUtterance("왼쪽", SpeechInputSource.PLEOS_STT, 1000)
        assertTrue(direction.awaitingConfirmation)
        assertEquals(LessonDirection.UNKNOWN, direction.direction)
        val confirmed = s.acceptUtterance("맞아요", SpeechInputSource.PLEOS_STT, 2000)
        assertEquals(LessonDirection.LEFT, confirmed.direction)
        assertEquals(TrafficDensity.HIGH, confirmed.reportedTraffic.density)
        assertTrue(confirmed.reply.contains("차가 많다고 말씀하셨군요"))
        assertTrue(confirmed.reply.contains("미러와 사각지대"))
    }
}
