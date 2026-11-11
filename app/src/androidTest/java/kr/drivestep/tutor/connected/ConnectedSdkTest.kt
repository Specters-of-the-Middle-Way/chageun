package kr.drivestep.tutor.connected

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.drivestep.tutor.workflow.InputValidity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import ai.pleos.playground.navi.helper.NaviHelper
import java.util.concurrent.CountDownLatch

/** Actual Pleos services, no injected observations. Requires the local AVD and location permission. */
@RunWith(AndroidJUnit4::class)
class ConnectedSdkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun waitFor(message: String, condition: ()->Boolean) {
        val end = SystemClock.elapsedRealtime()+20_000
        while (SystemClock.elapsedRealtime()<end) { if (condition()) return; SystemClock.sleep(100) }
        fail(message)
    }
    private fun console(path: String,method: String = "GET"): JSONObject {
        val connection = URL("http://127.0.0.1:8088$path").openConnection() as HttpURLConnection
        connection.requestMethod = method; connection.connectTimeout = 3_000; connection.readTimeout = 3_000
        return try { connection.inputStream.bufferedReader().use { JSONObject(it.readText()) } } finally { connection.disconnect() }
    }
    private val verificationGoal = NavigationGoal("SDK 공식 예시 위치 · 연동 검증",GeoPoint(37.48544722,127.03636666))
    private fun ownsVerificationRoute(value: LocationNavigationSnapshot?): Boolean = value?.destination?.value?.let {
        it.name == verificationGoal.name && kotlin.math.abs(it.point.latitude-verificationGoal.point.latitude)<.00001 &&
            kotlin.math.abs(it.point.longitude-verificationGoal.point.longitude)<.00001
    } == true
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }
    private fun restoreOwnRoute(last: AtomicReference<LocationNavigationSnapshot>) {
        if (!ownsVerificationRoute(last.get())) return
        check(android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val done=CountDownLatch(1)
        lateinit var helper:NaviHelper
        instrumentation.runOnMainSync {
            helper=NaviHelper(instrumentation.targetContext)
            helper.initialize(onConnected={ helper.cancelRoute();done.countDown() },onFailed={ done.countDown() })
        }
        try {
            assertTrue(done.await(8,TimeUnit.SECONDS))
            val end=SystemClock.elapsedRealtime()+3_000
            while (SystemClock.elapsedRealtime()<end && ownsVerificationRoute(last.get())) SystemClock.sleep(100)
            if (ownsVerificationRoute(last.get())) {
                // This image can leave cancelTrip stuck. Reset only the native process,
                // only for our named fixture, without clearing its data or other routes.
                shell("am force-stop --user current ai.umos.maps.android.navigation.app")
                shell("am start --user current -n ai.umos.maps.android.navigation.app/.MainActivity")
                instrumentation.sendStatus(0,Bundle().apply { putString("navigation_fixture_cleanup","Restarted only the native Navigation process for its stuck verification-route cancellation") })
            }
            waitFor("Verification route was not removed") { last.get()?.navigation?.contains("설정된 경로 없음")==true }
        } finally { instrumentation.runOnMainSync { helper.release() } }
    }
    @Test fun restoreVerificationRouteIfPresent() {
        check(android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val last=AtomicReference<LocationNavigationSnapshot>()
        lateinit var source:LocationNavigationSource
        instrumentation.runOnMainSync { source=LocationNavigationSource(instrumentation.targetContext,last::set);source.start() }
        try {
            waitFor("No navigation state for scoped cleanup") { last.get()?.navigation?.startsWith("내비게이션 ·")==true }
            if (last.get()?.navigation?.contains("설정된 경로 없음")!=true) {
                waitFor("No destination for scoped cleanup") { last.get()?.destination?.quality==InputValidity.VALID || last.get()?.navigation?.contains("설정된 경로 없음")==true }
            }
            restoreOwnRoute(last)
        } finally { instrumentation.runOnMainSync { source.stop() } }
    }
    @Test fun destinationRequestConfirmsActiveMatchingRoute() {
        check(android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val last = AtomicReference<LocationNavigationSnapshot>()
        lateinit var source: LocationNavigationSource
        var sent = false
        instrumentation.runOnMainSync { source=LocationNavigationSource(instrumentation.targetContext,last::set);source.start() }
        try {
            waitFor("No source location/navigation response") { last.get()?.let { it.location.value!=null && it.navigation.contains("설정된 경로 없음") } == true }
            // Official Kotlin destination example. FusedLocation's default AVD fix is
            // Mountain View while this Navigation provider's map/initial pose is Korea.
            val goal=verificationGoal
            instrumentation.runOnMainSync { sent=source.requestDestination(goal) };assertTrue(sent)
            waitFor("No provider completion or explicit SDK error") { last.get()?.let { it.phase == RequestPhase.CONFIRMED || (it.phase == RequestPhase.FAILED && it.requestMessage.startsWith("내비 오류")) } == true }
            instrumentation.sendStatus(0,Bundle().apply { putString("navigation_request_result",last.get().requestMessage) })
            assertEquals(last.get().requestMessage,RequestPhase.CONFIRMED,last.get().phase)
            assertTrue(ownsVerificationRoute(last.get()))
        } finally {
            instrumentation.sendStatus(0,Bundle().apply {
                putString("final_navigation_request",last.get()?.let { "${it.phase} · ${it.requestMessage} · ${it.navigation} · destinationReceived=${it.destination.value!=null}" })
            })
            try { if (sent) restoreOwnRoute(last) }
            finally { instrumentation.runOnMainSync { source.stop() } }
        }
    }
    @Test fun consoleWarningReachesTheRealSdkListener() {
        check(android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val original = console("/vehicle/state")
        val oldWarning = original.getJSONObject("telltales").getJSONObject("actual").optJSONObject("MasterWarning")
        val last = AtomicReference<ReadinessSnapshot>()
        val heartbeat = Executors.newSingleThreadScheduledExecutor()
        val client = "chageun-connected-verification"
        lateinit var source: VehicleReadinessSource
        heartbeat.scheduleAtFixedRate({ runCatching { console("/browser/session?client=$client","POST") } },0,700,TimeUnit.MILLISECONDS)
        instrumentation.runOnMainSync { source = VehicleReadinessSource(instrumentation.targetContext) { value,_ -> last.set(value) };source.start() }
        try {
            waitFor("No initial SDK warning receipt") { last.get()?.warnings?.quality == InputValidity.VALID }
            console("/vehicle/telltales/simulator?type=MasterWarning&state=On&color=Red","PUT")
            waitFor("Warning did not interrupt guidance policy") { last.get()?.warnings?.value?.any { it.type=="MASTER_WARNING" && it.active } == true && last.get()?.speechBlocked == true }
            console("/vehicle/telltales/simulator?type=MasterWarning&state=Off&color=Red","PUT")
            waitFor("Warning clear not observed") { last.get()?.warnings?.value?.none { it.type=="MASTER_WARNING" && it.active } == true }
        } finally {
            try {
                console("/vehicle/telltales/simulator?type=MasterWarning&state=${oldWarning?.optString("state","Off") ?: "Off"}&color=${oldWarning?.optString("color","Red") ?: "Red"}","PUT")
            } finally {
                instrumentation.runOnMainSync { source.stop() };heartbeat.shutdownNow()
                console("/browser/session?client=$client&closing=1","POST")
            }
        }
    }
    @Test fun publicVehicleReadsReceiveRealSdkValuesAndStopCleanly() {
        val last = AtomicReference<ReadinessSnapshot>()
        lateinit var source: VehicleReadinessSource
        instrumentation.runOnMainSync {
            source = VehicleReadinessSource(instrumentation.targetContext) { value,_ -> last.set(value) }; source.start()
        }
        try {
            waitFor("No actual seat/belt/warning receipt: ${last.get()}") {
                last.get()?.let { it.seats.values.all { field -> field.quality != InputValidity.UNKNOWN } &&
                    it.belt.quality != InputValidity.UNKNOWN && it.warnings.quality != InputValidity.UNKNOWN } == true
            }
            val value = last.get()
            instrumentation.sendStatus(0,Bundle().apply { putString("connected_vehicle",value.toString()) })
            assertEquals(InputValidity.VALID,value.belt.quality)
            assertEquals(InputValidity.VALID,value.warnings.quality)
            value.seats.values.forEach { assertEquals(InputValidity.VALID,it.quality); assertNotNull(it.value) }
        } finally {
            instrumentation.runOnMainSync { source.stop(); assertFalse(source.isRunning) }
        }
        val stopped = last.get(); SystemClock.sleep(500)
        assertEquals(stopped,last.get()); assertNull(stopped.belt.value)
    }
    @Test fun publicLocationAndNavigationReceiveAndReleaseWithoutSendingRoutes() {
        val last = AtomicReference<LocationNavigationSnapshot>()
        lateinit var source: LocationNavigationSource
        instrumentation.runOnMainSync { source = LocationNavigationSource(instrumentation.targetContext,last::set); source.start() }
        try {
            waitFor("FusedLocation did not return current data") { last.get()?.location?.quality == InputValidity.VALID }
            waitFor("NaviHelper connected without an actual route-state response: ${last.get()?.navigation}") { last.get()?.navigation?.startsWith("내비게이션 ·") == true }
            val value = last.get()
            assertTrue(value.location.value!!.point.valid)
            assertEquals(RequestPhase.IDLE,value.phase)
            instrumentation.sendStatus(0,Bundle().apply {
                putString("connected_location_quality",value.location.quality.name)
                putString("connected_navigation",value.navigation)
            })
        } finally { instrumentation.runOnMainSync { source.stop(); assertFalse(source.isRunning) } }
        val stopped = last.get(); SystemClock.sleep(500)
        assertEquals(stopped,last.get());assertNull(stopped.location.value);assertFalse(stopped.canRequest)
    }
}
