package kr.drivestep.tutor.lessoncoach

import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.Future
import org.json.JSONObject

/** Development relay client. No provider credentials or raw audio are sent by the app. */
class TutorLlmClient(private val debug: Boolean) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var generation = 0L
    private var task: Future<*>? = null
    private var deadline: Runnable? = null
    private var call: Call? = null
    private var closed = false

    private class Call {
        private var cancelled = false
        private var connection: HttpURLConnection? = null
        @Synchronized fun attach(value: HttpURLConnection): Boolean {
            if (cancelled) { value.disconnect(); return false }
            connection = value; return true
        }
        @Synchronized fun cancel() { cancelled = true; connection?.disconnect() }
    }

    fun request(endpoint: String, body: Map<String, Any?>, callback: (Result<TutorLlmReply>) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        cancel()
        if (closed) return
        val token = generation
        val current = Call().also { call = it }
        deadline = Runnable {
            if (!closed && token == generation) {
                cancel()
                callback(Result.failure(IllegalStateException("REQUEST_TIMEOUT")))
            }
        }.also { main.postDelayed(it, 10000) }
        task = worker.submit {
            val result = runCatching {
                val uri = URI(endpoint)
                require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path in listOf("", "/"))
                // Local debug relay only. A production service needs its own authenticated deployment.
                require(debug && uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1024..65535)
                val connection = URI("${endpoint.trimEnd('/')}/tutor").toURL().openConnection() as HttpURLConnection
                check(current.attach(connection))
                try {
                    connection.requestMethod = "POST"; connection.instanceFollowRedirects = false
                    connection.connectTimeout = 2000; connection.readTimeout = 8500
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.doOutput = true
                    val bytes = JSONObject(body).toString().toByteArray(Charsets.UTF_8)
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                    val code = connection.responseCode
                    val stream = if (code == 200) connection.inputStream else connection.errorStream
                    val raw = stream?.use { it.readBytesBounded() } ?: throw IllegalStateException("NETWORK_ERROR")
                    val json = JSONObject(raw)
                    if (code != 200) throw IllegalStateException(json.optString("error", "NETWORK_ERROR").take(60))
                    val provider = TutorLlmProvider.valueOf(json.getString("provider"))
                    require(debug || provider != TutorLlmProvider.TEST_FIXTURE)
                    val resultJson = json.getJSONObject("result")
                    val intent = LessonIntent.valueOf(resultJson.getString("intent"))
                    val approach = if (resultJson.isNull("approaching")) null else resultJson.getBoolean("approaching")
                    TutorLlmReply(provider, ParsedLessonUtterance(intent,
                        LessonDirection.valueOf(resultJson.getString("direction")),
                        ReportedTraffic(TrafficDensity.valueOf(resultJson.getString("density")), TrafficFlow.valueOf(resultJson.getString("flow")), approach),
                        resultJson.getBoolean("hasTrafficReport")), resultJson.getString("reply").also { require(it.length in 1..300) },
                        resultJson.getString("risk").also { require(it in setOf("EDUCATION", "CLARIFY", "ENTRY_DECISION", "CONTROL", "OUT_OF_SCOPE")) })
                } finally { connection.disconnect() }
            }
            main.post {
                if (!closed && token == generation) {
                    deadline?.let { main.removeCallbacks(it) }; deadline = null; task = null; call = null
                    callback(result)
                }
            }
        }
    }

    fun cancel() {
        generation++
        deadline?.let { main.removeCallbacks(it) }; deadline = null
        call?.cancel(); call = null
        task?.cancel(true); task = null
    }
    override fun close() { closed = true; cancel(); worker.shutdownNow() }
    private fun java.io.InputStream.readBytesBounded(): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(2048)
        while (true) {
            val size = read(buffer)
            if (size < 0) break
            require(output.size() + size <= 16384)
            output.write(buffer, 0, size)
        }
        return output.toString("UTF-8")
    }
}
