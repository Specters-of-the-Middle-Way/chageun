package kr.drivestep.tutor.journey

import org.junit.Assert.*
import org.junit.Test

class JourneyStateTest {
    private fun prepared(): JourneyState = JourneyState().apply {
        confirmProfile()
        repeat(9) { confirmSetupStep() }
        repeat(7) { setPreflight(it, true) }
    }

    @Test fun firstUseStartsAtProfileWithNoConsentsOrChecks() {
        val state = JourneyState()
        assertEquals(JourneyStep.PROFILE, state.page)
        assertFalse(state.canPractice)
        assertFalse(state.recordConsent)
        assertFalse(state.vehicleConsent)
        assertEquals(0, state.setupCompletedCount)
        assertEquals(0, state.preflightCompletedCount)
    }

    @Test fun specifiedDemoMetadataIsNotAComputedSeatRecommendation() {
        val state = JourneyState()
        assertEquals(180, state.profile.heightCm)
        assertEquals(78, state.profile.weightKg)
        assertEquals(VirtualSeatCalibration(), state.calibration)
        assertEquals(50, state.calibration.foreAft)
        state.updateProfile(state.profile.copy(heightCm = 195, weightKg = 100))
        assertEquals(VirtualSeatCalibration(), state.calibration)
    }

    @Test fun profileConfirmationIsRequiredBeforeSetupCanAdvance() {
        val state = JourneyState()
        state.confirmSetupStep()
        state.enterSetup(5)
        assertEquals(JourneyStep.PROFILE, state.page)
        assertEquals(0, state.setupCompletedCount)
        state.confirmProfile()
        assertTrue(state.profileConfirmed)
        assertTrue(state.consentReviewed)
        assertEquals(JourneyStep.SETUP, state.page)
    }

    @Test fun practiceCannotSkipPreparation() {
        val state = JourneyState()
        assertFalse(state.enterPractice())
        assertEquals(JourneyStep.PROFILE, state.page)
        state.confirmProfile()
        assertFalse(state.enterPractice())
        assertEquals(JourneyStep.SETUP, state.page)
    }

    @Test fun allNineSetupChecksAreNeededAndDoNotAutoConfirmPreflight() {
        val state = JourneyState()
        state.confirmProfile()
        repeat(8) { state.confirmSetupStep() }
        assertFalse(state.setupComplete)
        assertEquals(8, state.setupIndex)
        state.confirmSetupStep()
        assertTrue(state.setupComplete)
        assertEquals(JourneyStep.PREFLIGHT, state.page)
        assertFalse(state.canPractice)
        assertEquals(0, state.preflightCompletedCount)
    }

    @Test fun sixOfSevenChecksDoNotPermitPractice() {
        val state = JourneyState()
        state.confirmProfile(); repeat(9) { state.confirmSetupStep() }
        repeat(6) { state.setPreflight(it, true) }
        assertFalse(state.enterPractice())
        state.setPreflight(6, true)
        assertTrue(state.enterPractice())
        assertEquals(JourneyStep.PRACTICE, state.page)
    }

    @Test fun uncheckingOnePreparationItemRevokesReadiness() {
        val state = prepared()
        assertTrue(state.canPractice)
        state.setPreflight(3, false)
        assertFalse(state.canPractice)
        assertFalse(state.enterPractice())
    }

    @Test fun absentVehicleOrRecordConsentDoesNotBlockManualSimulationPreparation() {
        val state = prepared()
        assertFalse(state.vehicleConsent)
        assertFalse(state.recordConsent)
        assertTrue(state.enterPractice())
    }

    @Test fun educationAlwaysRemainsAvailableWithoutProfileOrSignals() {
        val state = JourneyState()
        state.enterEducation()
        assertEquals(JourneyStep.EDUCATION, state.page)
        assertFalse(state.profileConfirmed)
        assertFalse(state.canPractice)
    }

    @Test fun failedSetupIsNotSilentlyCompleted() {
        val state = prepared()
        state.enterSetup(6)
        state.leaveSetupUnresolved()
        assertFalse(state.setupChecks()[6])
        assertEquals(8, state.setupCompletedCount)
        assertFalse(state.canPractice)
        assertEquals(0, state.preflightCompletedCount)
    }

    @Test fun changingSeatPositionInvalidatesItsOwnCheckAndAllMirrors() {
        val state = prepared()
        state.setCalibration(CalibrationAxis.FORE_AFT, 62)
        assertEquals(62, state.calibration.foreAft)
        assertFalse(state.setupChecks()[0])
        for (i in 5..7) assertFalse(state.setupChecks()[i])
        assertTrue(state.setupChecks()[8])
        assertFalse(state.canPractice)
        assertEquals(0, state.preflightCompletedCount)
    }

    @Test fun changingSteeringInvalidatesMirrorChecks() {
        val state = prepared()
        state.setCalibration(CalibrationAxis.STEERING, 30)
        assertFalse(state.setupChecks()[4])
        for (i in 5..7) assertFalse(state.setupChecks()[i])
    }

    @Test fun unchangedVirtualPositionDoesNotDestroyConfirmedChecks() {
        val state = prepared()
        state.setCalibration(CalibrationAxis.HEIGHT, 50)
        assertTrue(state.canPractice)
    }

    @Test fun manualCalibrationIsBoundedAndNoPhysicalCoordinatesAreProduced() {
        val state = JourneyState()
        state.setCalibration(CalibrationAxis.HEIGHT, 999)
        state.setCalibration(CalibrationAxis.BACKREST, -5)
        assertEquals(100, state.calibration.height)
        assertEquals(0, state.calibration.backrest)
    }

    @Test fun bodyProfileChangeRequiresPreparationAgain() {
        val state = prepared()
        state.updateProfile(state.profile.copy(heightCm = 181))
        assertEquals(0, state.setupCompletedCount)
        assertEquals(0, state.preflightCompletedCount)
        assertFalse(state.canPractice)
    }

    @Test fun confidenceChangeDoesNotAlterCalibrationOrInventSkills() {
        val state = prepared()
        state.updateProfile(state.profile.copy(confidence = 5))
        assertEquals(5, state.profile.confidence)
        assertEquals(VirtualSeatCalibration(), state.calibration)
        assertTrue(state.canPractice)
    }

    @Test fun profileValuesAreBoundedOnLoad() {
        val state = JourneyState(profile = DriverProfile(500, -50, confidence = 12))
        assertEquals(200, state.profile.heightCm)
        assertEquals(50, state.profile.weightKg)
        assertEquals(5, state.profile.confidence)
    }

    @Test fun restoredProfileStartsAtRevisitWithFreshPreflight() {
        val state = JourneyState(setupChecks = List(9) { true }, profileConfirmed = true)
        assertEquals(JourneyStep.REVISIT, state.page)
        assertTrue(state.setupComplete)
        assertFalse(state.preflightComplete)
        assertFalse(state.canPractice)
    }

    @Test fun unchangedRevisitStillRequiresEveryCurrentPreflightCheck() {
        val state = prepared()
        state.newVisit(); state.revisitUnchanged()
        assertEquals(JourneyStep.PREFLIGHT, state.page)
        assertTrue(state.setupComplete)
        assertFalse(state.canPractice)
        assertEquals(0, state.preflightCompletedCount)
    }

    @Test fun changedRevisitResetsAllPostureAndPreflightChecks() {
        val state = prepared()
        state.revisitChanged()
        assertEquals(JourneyStep.SETUP, state.page)
        assertEquals(0, state.setupIndex)
        assertEquals(0, state.setupCompletedCount)
        assertEquals(0, state.preflightCompletedCount)
    }

    @Test fun storedPartialSetupResumesAtFirstIncompleteItem() {
        val state = JourneyState(setupChecks = listOf(true, true, false), profileConfirmed = true)
        state.revisitUnchanged()
        assertEquals(JourneyStep.SETUP, state.page)
        assertEquals(2, state.setupIndex)
        assertEquals(2, state.setupCompletedCount)
    }

    @Test fun callersCannotMutateInternalChecks() {
        val state = prepared()
        (state.setupChecks() as MutableList<Boolean>)[0] = false
        (state.preflightChecks() as MutableList<Boolean>)[0] = false
        assertTrue(state.canPractice)
    }

    @Test fun invalidPreflightIndexesAreIgnored() {
        val state = prepared()
        state.setPreflight(-1, false); state.setPreflight(500, false)
        assertTrue(state.canPractice)
    }

    @Test fun returningFromReviewCannotBypassMissingChecks() {
        val state = JourneyState()
        state.enterReview()
        assertFalse(state.enterPractice())
        assertEquals(JourneyStep.PROFILE, state.page)
    }
}
