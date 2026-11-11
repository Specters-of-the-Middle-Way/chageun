package kr.drivestep.tutor.connected

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Virtual Console input, never an injected application observation. Restores the original indicator. */
internal class EmulatorWarningFixture : AutoCloseable {
    private val client = "chageun-warning-verification"
    private val original: JSONObject?
    private val heartbeat = Executors.newSingleThreadScheduledExecutor()
    init {
        check(android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        original = request("/vehicle/state").getJSONObject("telltales").getJSONObject("actual").optJSONObject("MasterWarning")
        heartbeat.scheduleAtFixedRate({ runCatching { request("/browser/session?client=$client","POST") } },0,700,TimeUnit.MILLISECONDS)
    }
    fun set(active: Boolean) { request("/vehicle/telltales/simulator?type=MasterWarning&state=${if (active) "On" else "Off"}&color=Red","PUT") }
    override fun close() {
        try { request("/vehicle/telltales/simulator?type=MasterWarning&state=${original?.optString("state","Off") ?: "Off"}&color=${original?.optString("color","Red") ?: "Red"}","PUT") }
        finally { heartbeat.shutdownNow();request("/browser/session?client=$client&closing=1","POST") }
    }
    private fun request(path: String,method: String = "GET"): JSONObject {
        val connection = URL("http://127.0.0.1:8088$path").openConnection() as HttpURLConnection
        connection.requestMethod=method;connection.connectTimeout=3_000;connection.readTimeout=3_000
        return try { connection.inputStream.bufferedReader().use { JSONObject(it.readText()) } } finally { connection.disconnect() }
    }
}
