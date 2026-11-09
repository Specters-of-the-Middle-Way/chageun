package kr.drivestep.tutor.journey

import kotlin.math.roundToInt

enum class JourneyDestination { DRIVE, PARKING, SIGNAL, STEERING, LEGACY, CONTEXT, LESSON }
enum class JourneyStep { PROFILE, REVISIT, SETUP, PREFLIGHT, PRACTICE, EDUCATION, REVIEW }
enum class CalibrationAxis { FORE_AFT, HEIGHT, BACKREST, STEERING }

/**
 * Heuristic reference band derived from driver height/weight for visual guidance only.
 * Neutral 50 remains the initial default until the user adjusts the slider or applies
 * the reference preset. Final confirmation always requires direct physical verification.
 */
data class PostureGuideBand(
    val recommendedCenter: Int,
    val minRecommended: Int,
    val maxRecommended: Int,
) {
    fun contains(value: Int): Boolean = value in minRecommended..maxRecommended
}

/** User-specified demonstration metadata, not an anthropometric seat-fitting model. */
data class DriverProfile(
    val heightCm: Int = 180,
    val weightKg: Int = 78,
    val experience: String = "장롱면허",
    val confidence: Int = 3,
    val guidance: String = "차근차근",
    val difficulty: String = "주차",
) {
    fun validated() = copy(
        heightCm = heightCm.coerceIn(150, 200),
        weightKg = weightKg.coerceIn(50, 110),
        confidence = confidence.coerceIn(1, 5),
    )

    fun guideBand(axis: CalibrationAxis): PostureGuideBand {
        val safe = validated()
        val heightDelta = safe.heightCm - 175
        val weightDelta = safe.weightKg - 75
        val center = when (axis) {
            CalibrationAxis.FORE_AFT -> (50 + (heightDelta * 0.48).roundToInt()).coerceIn(25, 75)
            CalibrationAxis.HEIGHT -> (50 - (heightDelta * 0.44).roundToInt()).coerceIn(25, 75)
            CalibrationAxis.BACKREST -> (50 + (weightDelta * 0.20).roundToInt()).coerceIn(35, 65)
            CalibrationAxis.STEERING -> (50 + (heightDelta * 0.28).roundToInt()).coerceIn(30, 70)
        }
        val halfSpan = 8
        return PostureGuideBand(
            recommendedCenter = center,
            minRecommended = (center - halfSpan).coerceAtLeast(0),
            maxRecommended = (center + halfSpan).coerceAtMost(100),
        )
    }

    fun recommendedCalibration(): VirtualSeatCalibration = VirtualSeatCalibration(
        foreAft = guideBand(CalibrationAxis.FORE_AFT).recommendedCenter,
        height = guideBand(CalibrationAxis.HEIGHT).recommendedCenter,
        backrest = guideBand(CalibrationAxis.BACKREST).recommendedCenter,
        steering = guideBand(CalibrationAxis.STEERING).recommendedCenter,
    )
}

/** Neutral 50 is a display starting point. These values have no mm/degree/SDK mapping. */
data class VirtualSeatCalibration(
    val foreAft: Int = 50,
    val height: Int = 50,
    val backrest: Int = 50,
    val steering: Int = 50,
) {
    fun value(axis: CalibrationAxis) = when (axis) {
        CalibrationAxis.FORE_AFT -> foreAft
        CalibrationAxis.HEIGHT -> height
        CalibrationAxis.BACKREST -> backrest
        CalibrationAxis.STEERING -> steering
    }
    fun withValue(axis: CalibrationAxis, value: Int): VirtualSeatCalibration {
        val bounded = value.coerceIn(0, 100)
        return when (axis) {
            CalibrationAxis.FORE_AFT -> copy(foreAft = bounded)
            CalibrationAxis.HEIGHT -> copy(height = bounded)
            CalibrationAxis.BACKREST -> copy(backrest = bounded)
            CalibrationAxis.STEERING -> copy(steering = bounded)
        }
    }
}

/**
 * Manual preparation evidence only. Vehicle UX availability is enforced by the host.
 * Completing this state permits an app exercise, never a real-vehicle departure.
 * No profile confidence or local completion is converted to a driving skill score.
 */
class JourneyState(
    profile: DriverProfile = DriverProfile(),
    calibration: VirtualSeatCalibration = VirtualSeatCalibration(),
    setupChecks: List<Boolean> = List(SETUP_COUNT) { false },
    profileConfirmed: Boolean = false,
    var recordConsent: Boolean = false,
    var vehicleConsent: Boolean = false,
) {
    var profile = profile.validated(); private set
    var calibration = calibration; private set
    var profileConfirmed = profileConfirmed; private set
    private val setup = MutableList(SETUP_COUNT) { setupChecks.getOrElse(it) { false } }
    private val preflight = MutableList(PREFLIGHT_COUNT) { false }
    var consentReviewed = profileConfirmed; private set
    var page = if (profileConfirmed) JourneyStep.REVISIT else JourneyStep.PROFILE
        private set
    var setupIndex = setup.indexOfFirst { !it }.takeIf { it >= 0 } ?: 0
        private set

    val setupComplete get() = setup.all { it }
    val preflightComplete get() = preflight.all { it }
    val canPractice get() = profileConfirmed && consentReviewed && setupComplete && preflightComplete
    val setupCompletedCount get() = setup.count { it }
    val preflightCompletedCount get() = preflight.count { it }
    fun setupChecks(): List<Boolean> = setup.toList()
    fun preflightChecks(): List<Boolean> = preflight.toList()
    fun guideBand(axis: CalibrationAxis): PostureGuideBand = profile.guideBand(axis)

    fun updateProfile(next: DriverProfile) {
        val safe = next.validated()
        if (profile.heightCm != safe.heightCm || profile.weightKg != safe.weightKg) {
            setup.fill(false)
            preflight.fill(false)
        }
        profile = safe
    }

    fun confirmProfile() {
        profileConfirmed = true
        consentReviewed = true
        enterSetup()
    }

    fun setCalibration(axis: CalibrationAxis, value: Int) {
        val next = calibration.withValue(axis, value)
        if (next == calibration) return
        calibration = next
        val ownStep = when (axis) {
            CalibrationAxis.FORE_AFT -> 0
            CalibrationAxis.HEIGHT -> 1
            CalibrationAxis.BACKREST -> 2
            CalibrationAxis.STEERING -> 4
        }
        setup[ownStep] = false
        // A changed eye/arm position invalidates all previously checked mirror views.
        for (i in 5..7) setup[i] = false
        preflight.fill(false)
    }

    fun applyRecommendedPreset() {
        val next = profile.recommendedCalibration()
        if (next == calibration) return
        val changedAxes = CalibrationAxis.entries.filter { calibration.value(it) != next.value(it) }
        calibration = next
        changedAxes.forEach { axis ->
            val ownStep = when (axis) {
                CalibrationAxis.FORE_AFT -> 0
                CalibrationAxis.HEIGHT -> 1
                CalibrationAxis.BACKREST -> 2
                CalibrationAxis.STEERING -> 4
            }
            setup[ownStep] = false
        }
        // Applying a new posture starting preset invalidates all mirror confirmations.
        for (i in 5..7) setup[i] = false
        preflight.fill(false)
    }

    fun enterProfile() { page = JourneyStep.PROFILE }
    fun enterSetup(index: Int? = null) {
        if (!profileConfirmed) { page = JourneyStep.PROFILE; return }
        setupIndex = index?.coerceIn(0, SETUP_COUNT - 1)
            ?: setup.indexOfFirst { !it }.takeIf { it >= 0 } ?: 0
        page = JourneyStep.SETUP
    }
    fun confirmSetupStep() {
        if (page != JourneyStep.SETUP || !profileConfirmed) return
        setup[setupIndex] = true
        val next = setup.indexOfFirst { !it }
        if (next < 0) page = JourneyStep.PREFLIGHT else setupIndex = next
    }
    fun leaveSetupUnresolved() {
        if (page != JourneyStep.SETUP) return
        setup[setupIndex] = false
        preflight.fill(false)
    }
    fun enterPreflight() {
        page = if (!profileConfirmed) JourneyStep.PROFILE
            else if (!setupComplete) JourneyStep.SETUP else JourneyStep.PREFLIGHT
        if (page == JourneyStep.SETUP) setupIndex = setup.indexOfFirst { !it }
    }
    fun setPreflight(index: Int, confirmed: Boolean) {
        if (index !in preflight.indices || !profileConfirmed || !setupComplete) return
        preflight[index] = confirmed
    }
    fun enterPractice(): Boolean {
        if (!canPractice) { enterPreflight(); return false }
        page = JourneyStep.PRACTICE
        return true
    }
    fun enterEducation() { page = JourneyStep.EDUCATION }
    fun enterReview() { page = JourneyStep.REVIEW }
    fun revisitUnchanged() {
        // Stored settings are reusable, this visit's checks never are.
        preflight.fill(false)
        enterPreflight()
    }
    fun revisitChanged() {
        preflight.fill(false)
        setup.fill(false)
        enterSetup(0)
    }
    fun newVisit() {
        preflight.fill(false)
        page = if (profileConfirmed) JourneyStep.REVISIT else JourneyStep.PROFILE
    }

    companion object {
        const val SETUP_COUNT = 9
        const val PREFLIGHT_COUNT = 7
    }
}
