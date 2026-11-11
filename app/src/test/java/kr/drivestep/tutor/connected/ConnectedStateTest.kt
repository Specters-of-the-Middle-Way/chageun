package kr.drivestep.tutor.connected

import kr.drivestep.tutor.journey.JourneyState
import kr.drivestep.tutor.workflow.InputValidity
import org.junit.Assert.*
import org.junit.Test

class ConnectedStateTest {
    @Test fun initialSeatDoesNotCertifyPostureAndMovementInvalidatesMirrors() {
        val state = VehicleReadinessState()
        assertTrue(state.seat(SeatAxis.HEIGHT,50,0,100,100).isEmpty())
        assertEquals(setOf(1,5,6,7),state.seat(SeatAxis.HEIGHT,51,0,100,200))
        assertTrue(state.seat(SeatAxis.HEIGHT,51,0,100,300).isEmpty())
        assertEquals(51,state.snapshot(300).seats.getValue(SeatAxis.HEIGHT).value!!.raw)
    }
    @Test fun invalidAndMissingPositionsNeverBecomeZeroOrValid() {
        val state = VehicleReadinessState()
        state.seat(SeatAxis.FORE_AFT,101,0,100,100)
        assertEquals(InputValidity.ERROR,state.snapshot(100).seats.getValue(SeatAxis.FORE_AFT).quality)
        state.seat(SeatAxis.FORE_AFT,null,0,100,200)
        assertNull(state.snapshot(200).seats.getValue(SeatAxis.FORE_AFT).value)
    }
    @Test fun seatChangeAfterADataGapStillInvalidatesPreparationWithinTheSameSession() {
        val state = VehicleReadinessState()
        state.seat(SeatAxis.HEIGHT,50,0,100,100)
        assertNull(state.snapshot(4_000).seats.getValue(SeatAxis.HEIGHT).value)
        assertEquals(setOf(1,5,6,7),state.seat(SeatAxis.HEIGHT,51,0,100,4_100))
        state.seat(SeatAxis.HEIGHT,null,0,100,4_200)
        assertNull(state.snapshot(4_200).seats.getValue(SeatAxis.HEIGHT).value)
        state.seat(SeatAxis.HEIGHT,101,0,100,4_300)
        assertEquals(setOf(1,5,6,7),state.seat(SeatAxis.HEIGHT,52,0,100,4_400))
        state.reset()
        assertTrue(state.seat(SeatAxis.HEIGHT,70,0,100,4_500).isEmpty())
    }
    @Test fun unbuckledInvalidatesOnceAndBucklingDoesNotCertifyWearing() {
        val state = VehicleReadinessState()
        assertEquals(setOf(8),state.belt(false,100))
        assertTrue(state.belt(false,200).isEmpty())
        assertTrue(state.belt(true,300).isEmpty())
        assertEquals(InputValidity.STALE,state.snapshot(3_301).belt.quality)
        assertNull(state.snapshot(3_301).belt.value)
    }
    @Test fun aFreshUnbuckledReceiptAfterAStaleGapRequiresConfirmationAgain() {
        val state = VehicleReadinessState()
        assertEquals(setOf(8),state.belt(false,100))
        assertEquals(InputValidity.STALE,state.snapshot(3_200).belt.quality)
        assertEquals(setOf(8),state.belt(false,3_300))
        assertTrue(state.belt(false,3_400).isEmpty())
    }
    @Test fun warningPriorityPersistsThroughMissingAndStaleDataUntilFreshClear() {
        val state = VehicleReadinessState()
        state.warnings(listOf(VehicleWarning("MASTER_WARNING","BLINK",4,1)),100)
        assertTrue(state.snapshot(5_000).speechBlocked)
        state.unavailable("warnings",InputValidity.DENIED,"permission")
        assertTrue(state.snapshot(5_100).speechBlocked)
        state.warnings(emptyList(),5_200)
        assertFalse(state.snapshot(5_200).speechBlocked)
        state.warnings(listOf(VehicleWarning("TURN_SIGNAL_LEFT","ON",1,0)),5_300)
        assertFalse(state.snapshot(5_300).speechBlocked)
    }
    @Test fun observationFreshnessRejectsClockRegressionAndExpires() {
        val value = Observed(5,InputValidity.VALID,100,"SDK")
        assertEquals(InputValidity.STALE,value.at(99).quality)
        assertEquals(InputValidity.VALID,value.at(3_100).quality)
        assertEquals(InputValidity.STALE,value.at(3_101).quality)
    }
    @Test fun cachedLocationUsesSourceTimeNotTheNewReceipt() {
        val fix = LocationFix(GeoPoint(37.5,127.1),100_000,1f)
        assertEquals(InputValidity.VALID,locationObservation(fix,100,110_000).quality)
        assertEquals(InputValidity.STALE,locationObservation(fix,100_000,140_000).quality)
        assertEquals(InputValidity.UNKNOWN,locationObservation(fix.copy(timestampMillis=0),100,110_000).quality)
        assertEquals(InputValidity.UNKNOWN,locationObservation(fix.copy(timestampMillis=120_000),100,110_000).quality)
        assertEquals(InputValidity.ERROR,locationObservation(fix.copy(point=GeoPoint(Double.NaN,0.0)),100,110_000).quality)
    }
    @Test fun routeSendAndOldDestinationAloneCannotConfirmNavigation() {
        val state = NavigationRequestState()
        val goal = NavigationGoal("직접 선택",GeoPoint(37.5,127.1))
        assertTrue(state.begin(goal,100))
        state.destination(goal.point,200)
        assertEquals(RequestPhase.WAITING,state.phase)
        state.routingCompleted(300)
        assertEquals(RequestPhase.WAITING,state.phase)
        state.activeRoute(true,350)
        state.destination(goal.point,400)
        assertEquals(RequestPhase.CONFIRMED,state.phase)
    }
    @Test fun mismatchedDestinationAndTimeoutCannotConfirmNavigation() {
        val state = NavigationRequestState()
        assertTrue(state.begin(NavigationGoal("목적지",GeoPoint(37.5,127.1)),100))
        state.routingCompleted(200);state.activeRoute(true,250);state.destination(GeoPoint(36.0,127.0),300)
        assertEquals(RequestPhase.FAILED,state.phase)
        assertTrue(state.begin(NavigationGoal("다시",GeoPoint(37.5,127.1)),400))
        state.expire(15_401);state.routingCompleted(15_500);state.destination(GeoPoint(37.5,127.1),15_600)
        assertEquals(RequestPhase.FAILED,state.phase)
    }
    @Test fun routeInvalidationAndStopDoNotKeepAnOldConfirmation() {
        val state = NavigationRequestState()
        val goal = NavigationGoal("목적지",GeoPoint(37.5,127.1))
        assertTrue(state.begin(goal,100));assertFalse(state.begin(goal,200))
        state.routingCompleted(300);state.activeRoute(true,350);state.destination(goal.point,400)
        state.fail("경로 취소");assertEquals(RequestPhase.FAILED,state.phase)
        state.reset();state.routingCompleted(500);state.destination(goal.point,600)
        assertEquals(RequestPhase.IDLE,state.phase)
    }
    @Test fun polledRouteRemovalClearsConfirmationWithoutACancelEvent() {
        val state = confirmedNavigation()
        state.activeRoute(false,500)
        assertEquals(RequestPhase.FAILED,state.phase)
    }
    @Test fun polledDestinationChangeClearsConfirmationWithoutAChangeEvent() {
        val state = confirmedNavigation()
        state.destination(GeoPoint(37.6,127.2),500)
        assertEquals(RequestPhase.FAILED,state.phase)
    }
    @Test fun confirmationRequiresContinuingFreshRouteAndDestinationReceipts() {
        val state = confirmedNavigation()
        state.activeRoute(true,3_000)
        state.destination(GeoPoint(37.5,127.1),3_100)
        state.expire(8_000)
        assertEquals(RequestPhase.CONFIRMED,state.phase)
        state.expire(8_001)
        assertEquals(RequestPhase.FAILED,state.phase)
        state.activeRoute(true,8_100);state.destination(GeoPoint(37.5,127.1),8_200)
        assertEquals(RequestPhase.FAILED,state.phase)
    }
    @Test fun freshRouteAloneCannotKeepAnOldDestinationConfirmed() {
        val state = confirmedNavigation()
        state.activeRoute(true,5_000)
        state.expire(5_401)
        assertEquals(RequestPhase.FAILED,state.phase)
    }
    @Test fun missingOrTimedOutEvidenceClearsConfirmationWithoutAutomaticRecovery() {
        val state = confirmedNavigation()
        state.evidenceUnavailable("목적지 응답 시간 초과")
        assertEquals(RequestPhase.FAILED,state.phase)
        state.activeRoute(true,500);state.destination(GeoPoint(37.5,127.1),600)
        assertEquals(RequestPhase.FAILED,state.phase)
        assertTrue(state.begin(NavigationGoal("다시 요청",GeoPoint(37.5,127.1)),700))
        state.routingStarted(800);state.activeRoute(true,900);state.destination(GeoPoint(37.5,127.1),1_000)
        assertEquals(RequestPhase.CONFIRMED,state.phase)
    }
    @Test fun lateDestinationCannotConfirmAgainstAnExpiredActiveRouteReceipt() {
        val state = NavigationRequestState()
        val point = GeoPoint(37.5,127.1)
        state.begin(NavigationGoal("목적지",point),100)
        state.routingStarted(200);state.activeRoute(true,300);state.destination(point,5_301)
        assertEquals(RequestPhase.WAITING,state.phase)
        state.activeRoute(true,5_400)
        assertEquals(RequestPhase.CONFIRMED,state.phase)
    }
    private fun confirmedNavigation(): NavigationRequestState = NavigationRequestState().apply {
        val target = GeoPoint(37.5,127.1)
        begin(NavigationGoal("목적지",target),100)
        routingStarted(200);activeRoute(true,300);destination(target,400)
        assertEquals(RequestPhase.CONFIRMED,phase)
    }
    @Test fun cancellationDuringReplacementRequiresFreshActiveRouteEvidence() {
        val state=NavigationRequestState()
        val goal=NavigationGoal("새 목적지",GeoPoint(37.5,127.1))
        state.begin(goal,100);state.cancelled()
        state.routingCompleted(200);state.destination(goal.point,300)
        assertEquals(RequestPhase.WAITING,state.phase)
        state.routingStarted(400);state.activeRoute(true,450);state.destination(goal.point,500)
        assertEquals(RequestPhase.CONFIRMED,state.phase)
        state.cancelled();assertEquals(RequestPhase.FAILED,state.phase)
    }
    @Test fun cancelledRequestWithOnlyOldRepliesTimesOut() {
        val state=NavigationRequestState()
        val goal=NavigationGoal("목적지",GeoPoint(37.5,127.1))
        state.begin(goal,100);state.cancelled();state.routingCompleted(200);state.destination(goal.point,300)
        state.expire(15_101)
        assertEquals(RequestPhase.FAILED,state.phase)
    }
    @Test fun providerMayEmitStartedWithoutRoutingCompletedButActiveDestinationMustMatch() {
        val state=NavigationRequestState()
        val goal=NavigationGoal("목적지",GeoPoint(37.5,127.1))
        state.begin(goal,100);state.routingStarted(200);state.destination(goal.point,250)
        assertEquals(RequestPhase.WAITING,state.phase)
        state.activeRoute(true,300);state.destination(goal.point,400)
        assertEquals(RequestPhase.CONFIRMED,state.phase)
    }
    @Test fun manualDestinationRejectsInvalidCoordinatesAndMissingNames() {
        assertFalse(NavigationGoal("",GeoPoint(37.5,127.0)).valid)
        assertFalse(NavigationGoal("좌표",GeoPoint(91.0,0.0)).valid)
        assertFalse(NavigationGoal("좌표",GeoPoint(0.0,Double.POSITIVE_INFINITY)).valid)
    }
    @Test fun emptyProviderDestinationIsUnknownInsteadOfAZeroDistanceSuccess() {
        val empty=navigationDestination("",GeoPoint(0.0,0.0),0,0,100)
        assertEquals(InputValidity.UNKNOWN,empty.quality);assertNull(empty.value)
        val invalid=navigationDestination("목적지",GeoPoint(100.0,0.0),0,0,100)
        assertEquals(InputValidity.ERROR,invalid.quality)
    }
    @Test fun realZeroCoordinatesAndArrivalValuesArePreservedButNegativeUnitsAreUnknown() {
        val arrived=navigationDestination("직접 선택한 위치",GeoPoint(0.0,0.0),0,0,100)
        assertEquals(InputValidity.VALID,arrived.quality)
        assertEquals(0,arrived.value!!.remainingMeters)
        val missing=navigationDestination("목적지",GeoPoint(37.5,127.0),-1,-1,100)
        assertNull(missing.value!!.remainingMeters);assertNull(missing.value!!.remainingSeconds)
    }
    @Test fun sdkMovementInvalidatesPreparationWithoutChangingVirtualCalibration() {
        val state = JourneyState(setupChecks=List(9){true},profileConfirmed=true)
        val before = state.calibration
        state.revisitUnchanged();repeat(7){state.setPreflight(it,true)}
        assertTrue(state.canPractice)
        assertTrue(state.invalidateVehiclePreparation(setOf(0,5,6,7)))
        assertFalse(state.canPractice)
        assertEquals(before,state.calibration)
        listOf(0,5,6,7).forEach { assertFalse(state.setupChecks()[it]) }
        assertTrue(state.setupChecks()[8])
    }
}
