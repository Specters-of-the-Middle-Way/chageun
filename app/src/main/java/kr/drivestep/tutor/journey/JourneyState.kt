package kr.drivestep.tutor.journey

import kotlin.math.roundToInt

enum class JourneyDestination { DRIVE, PARKING, SIGNAL, STEERING, LEGACY, CONTEXT, LESSON, WORKFLOW }
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
    fun validationErrors(): List<String> = buildList {
        if (heightCm !in 150..200) add("키는 cm 단위 150~200 범위로 입력해 주세요.")
        if (weightKg !in 50..110) add("체중은 kg 단위 50~110 범위로 입력해 주세요.")
        if (confidence !in 1..5) add("자신감은 1~5 범위로 선택해 주세요.")
    }
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
    var profileInputError: String? = if (profile.validationErrors().isNotEmpty()) "저장된 프로필의 범위가 맞지 않아 화면 값을 조정했어요. 키·체중을 직접 다시 확인해 주세요." else null
        private set
    var calibration = calibration; private set
    var profileConfirmed = profileConfirmed && profile.validationErrors().isEmpty(); private set
    private val setup = MutableList(SETUP_COUNT) { profile.validationErrors().isEmpty() && setupChecks.getOrElse(it) { false } }
    private val preflight = MutableList(PREFLIGHT_COUNT) { false }
    var consentReviewed = this.profileConfirmed; private set
    var page = if (this.profileConfirmed) JourneyStep.REVISIT else JourneyStep.PROFILE
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

    fun updateProfile(next: DriverProfile): Boolean {
        val errors = next.validationErrors()
        if (errors.isNotEmpty()) { profileInputError = errors.joinToString("\n"); return false }
        profileInputError = null
        val safe = next
        if (profile.heightCm != safe.heightCm || profile.weightKg != safe.weightKg) {
            setup.fill(false)
            preflight.fill(false)
        }
        profile = safe
        return true
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
    }

    fun applyRecommendedPreset(axis: CalibrationAxis? = null) {
        val next = if (axis != null) {
            calibration.withValue(axis, profile.guideBand(axis).recommendedCenter)
        } else {
            profile.recommendedCalibration()
        }
        if (next == calibration) return
        calibration = next
    }

    fun enterProfile() { page = JourneyStep.PROFILE }
    fun enterSetup(index: Int? = null) {
        if (!profileConfirmed) { page = JourneyStep.PROFILE; return }
        setupIndex = index?.coerceIn(0, SETUP_COUNT - 1)
            ?: setup.indexOfFirst { !it }.takeIf { it >= 0 } ?: 0
        page = JourneyStep.SETUP
    }
    fun invalidateVehiclePreparation(steps: Set<Int>): Boolean {
        val changed = steps.any { it in setup.indices && setup[it] }
        steps.filter { it in setup.indices }.forEach { setup[it] = false }
        if (steps.isNotEmpty()) preflight.fill(false)
        return changed
    }

    fun confirmSetupStep() {
        if (page != JourneyStep.SETUP || !profileConfirmed) return
        setup[setupIndex] = true
        if (setupIndex < SETUP_COUNT - 1) {
            setupIndex += 1
        } else if (setupComplete) {
            page = JourneyStep.PREFLIGHT
        }
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
