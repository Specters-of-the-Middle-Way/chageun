package kr.drivestep.tutor.connected

import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.workflow.InputValidity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** Runner revokes the real location permission before starting this separate instrumentation. */
@RunWith(AndroidJUnit4::class)
class ConnectedDeniedPermissionTest {
    @Test fun deniedLocationIsNotSilentlyReplacedByNavigationPosition() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(LocationNavigationSource.LOCATION_PERMISSION))
        val last=AtomicReference<LocationNavigationSnapshot>()
        lateinit var source:LocationNavigationSource
        instrumentation.runOnMainSync { source=LocationNavigationSource(context,last::set);source.start() }
        try {
            val end=SystemClock.elapsedRealtime()+15_000
            while (SystemClock.elapsedRealtime()<end && last.get()?.navigation?.startsWith("NaviHelper 수신")!=true) SystemClock.sleep(100)
            val value=last.get()
            assertTrue(value.navigation,value.navigation.startsWith("NaviHelper 수신"))
            assertEquals(InputValidity.DENIED,value.location.quality);assertNull(value.location.value)
            assertEquals(InputValidity.DENIED,value.navigationPosition.quality);assertNull(value.navigationPosition.value)
        } finally { instrumentation.runOnMainSync { source.stop() } }
    }
}
