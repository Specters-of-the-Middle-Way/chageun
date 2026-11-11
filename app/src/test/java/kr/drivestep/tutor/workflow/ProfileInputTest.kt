package kr.drivestep.tutor.workflow
import kr.drivestep.tutor.journey.*
import org.junit.Assert.*
import org.junit.Test
class ProfileInputTest {
    @Test fun invalidUnitsOrRangesAreRejectedWithoutSilentlyChangingProfile() {
        val s=JourneyState();val original=s.profile
        assertFalse(s.updateProfile(original.copy(heightCm=1800)))
        assertEquals(original,s.profile);assertNotNull(s.profileInputError)
        assertFalse(s.updateProfile(original.copy(weightKg=780)))
        assertEquals(original,s.profile)
        assertTrue(s.updateProfile(original.copy(heightCm=181)));assertNull(s.profileInputError)
    }
    @Test fun invalidRestoredProfileRequiresExplicitConfirmationAgain() {
        val s=JourneyState(profile=DriverProfile(500,-50),setupChecks=List(9){true},profileConfirmed=true)
        assertFalse(s.profileConfirmed);assertFalse(s.setupComplete);assertFalse(s.canPractice)
        assertNotNull(s.profileInputError);assertEquals(JourneyStep.PROFILE,s.page)
    }
}
