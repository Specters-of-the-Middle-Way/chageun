package kr.drivestep.tutor.lessoncoach

import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only smoke test. Run on a Pleos emulator already in P at zero speed. */
@RunWith(AndroidJUnit4::class)
class LessonInputCoordinatorSmokeTest {
    @Test fun realVehicleReadingsAndExplicitNavigationStatusStopAfterClose() {
        assertTrue("Run this smoke test only on an emulator, never a vehicle",
            Build.HARDWARE.contains("ranchu", true) || Build.HARDWARE.contains("goldfish", true) ||
                Build.DEVICE.contains("avd", true) || Build.MODEL.contains("sdk", true))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val permission = "android.car.permission.CAR_SPEED"
        val originallyGranted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        assertTrue("Grant CAR_SPEED to kr.drivestep.tutor for the current emulator user before running this test", originallyGranted)
        val ready = CountDownLatch(1)
        val navigationReported = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val observed = AtomicReference<VehicleObservation?>()
        val status = AtomicReference("")
        val navOutcome = AtomicReference("")
        var coordinator: LessonInputCoordinator? = null
        try {
            instrumentation.runOnMainSync {
                coordinator = LessonInputCoordinator(context, onVehicle = { sample ->
                    callbacks.incrementAndGet()
                    observed.set(sample)
                    if (sample.source == VehicleInputSource.SDK && sample.gear == LessonGear.P &&
                        sample.speedKmh?.let { it.isFinite() && abs(it) <= 0.036 } == true &&
                        sample.frontWheelAngleDegrees?.isFinite() == true && sample.signal != LessonSignal.UNKNOWN) ready.countDown()
                }, onNavigation = { sample ->
                    callbacks.incrementAndGet()
                    assertEquals(NavigationInputSource.NAVI, sample.source)
                }, onStatus = { text ->
                    callbacks.incrementAndGet()
                    status.set(text)
                    val navigation = text.lineSequence().lastOrNull().orEmpty()
                    if (navigation.contains("연결됨") || navigation.contains("수신됨") ||
                        navigation.contains("오류") || navigation.contains("불가") ||
                        navigation.contains("실패") || navigation.contains("권한") || navigation.contains("취소됨")) {
                        navOutcome.set(navigation)
                        navigationReported.countDown()
                    }
                }).also { it.start() }
            }
            val vehicleReady = ready.await(15, TimeUnit.SECONDS)
            assertTrue("P/zero-speed/steering/blinker SDK readings unavailable: ${status.get()} / ${observed.get()}", vehicleReady)
            assertNotNull(observed.get())
            val navigationReady = navigationReported.await(10, TimeUnit.SECONDS)
            assertTrue("Navigation must report connection or explicit unavailability, not stay silently pending: ${status.get()}", navigationReady)
            Log.i("LessonInputSmoke", "VEHICLE_SDK_READ_PASSED gear=P speed=0 steering=KNOWN signal=KNOWN")
            Log.i("LessonInputSmoke", "NAVI_SERVICE_OUTCOME=${navOutcome.get()} route_guidance=NOT_TESTED")
            instrumentation.runOnMainSync { coordinator?.close() }
            val stoppedCount = callbacks.get()
            Thread.sleep(300)
            instrumentation.waitForIdleSync()
            assertEquals("Closed coordinator delivered late callbacks", stoppedCount, callbacks.get())
        } finally {
            instrumentation.runOnMainSync { coordinator?.close() }
        }
    }
}
