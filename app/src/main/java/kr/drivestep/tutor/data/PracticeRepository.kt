package kr.drivestep.tutor.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kr.drivestep.tutor.practice.ObservationDisposition
import kr.drivestep.tutor.practice.PracticeAction
import kr.drivestep.tutor.practice.PracticeOutcome
import kr.drivestep.tutor.practice.PracticeSnapshot
import kr.drivestep.tutor.practice.PracticeStage
import kr.drivestep.tutor.practice.SignalObservation
import kr.drivestep.tutor.practice.SignalPattern
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable

/**
 * Local-only exercise history. Call on an IO executor, including reads and recovery.
 * A row contains the entire immutable snapshot, so progress and events commit together.
 */
class PracticeRepository(
    context: Context,
    databaseName: String = "drivestep-practice.db",
) : Closeable {
    private val helper = DatabaseHelper(context.applicationContext, databaseName)

    /** Returns false when a delayed write would overwrite a terminal or newer record. */
    @Synchronized
    fun save(snapshot: PracticeSnapshot): Boolean {
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            val existing = find(database, snapshot.id)
            if (existing != null &&
                (existing.isTerminal || existing.events.size > snapshot.events.size)
            ) {
                database.setTransactionSuccessful()
                return false
            }
            write(database, snapshot)
            database.setTransactionSuccessful()
            return true
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    fun get(id: String): PracticeSnapshot? = find(helper.readableDatabase, id)

    @Synchronized
    fun listRecent(limit: Int = 30): List<PracticeSnapshot> {
        require(limit in 1..200) { "History limit must be between 1 and 200" }
        return helper.readableDatabase.query(
            TABLE,
            arrayOf("snapshot_json"),
            null,
            null,
            null,
            null,
            "started_at DESC, session_id DESC",
            limit.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(SnapshotJson.decode(cursor.getString(0)))
            }
        }
    }

    /**
     * Call once when the app starts, before accepting a new exercise. An unfinished
     * persisted session is evidence of interruption, never evidence of success.
     * Returns the number of recovered records; completed history is left untouched.
     */
    @Synchronized
    fun recoverInterrupted(atMillis: Long = System.currentTimeMillis()): Int {
        require(atMillis >= 0) { "Recovery time must not be negative" }
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            val pending = database.query(
                TABLE,
                arrayOf("snapshot_json"),
                "outcome = ?",
                arrayOf(PracticeOutcome.ACTIVE.name),
                null,
                null,
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(SnapshotJson.decode(cursor.getString(0)))
                }
            }
            pending.forEach { snapshot ->
                val lastObserved = snapshot.events.maxOfOrNull { it.observedAtMillis }
                    ?: snapshot.startedAtMillis
                write(
                    database,
                    snapshot.copy(
                        stage = PracticeStage.INTERRUPTED,
                        outcome = PracticeOutcome.INTERRUPTED,
                        nextAction = PracticeAction.NONE,
                        endedAtMillis = maxOf(atMillis, snapshot.startedAtMillis, lastObserved),
                        interruptionReason = "PROCESS_RESTART",
                    ),
                )
            }
            database.setTransactionSuccessful()
            return pending.size
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    fun delete(id: String): Boolean = helper.writableDatabase.delete(
        TABLE,
        "session_id = ?",
        arrayOf(id),
    ) > 0

    @Synchronized
    override fun close() = helper.close()

    private fun find(database: SQLiteDatabase, id: String): PracticeSnapshot? = database.query(
        TABLE,
        arrayOf("snapshot_json"),
        "session_id = ?",
        arrayOf(id),
        null,
        null,
        null,
        "1",
    ).use { cursor ->
        if (cursor.moveToFirst()) SnapshotJson.decode(cursor.getString(0)) else null
    }

    private fun write(database: SQLiteDatabase, snapshot: PracticeSnapshot) {
        val values = ContentValues().apply {
            put("session_id", snapshot.id)
            put("started_at", snapshot.startedAtMillis)
            put("outcome", snapshot.outcome.name)
            put("snapshot_json", SnapshotJson.encode(snapshot))
        }
        check(database.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
            "Could not save practice history"
        }
    }

    private class DatabaseHelper(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 1) {
        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL(
                """CREATE TABLE $TABLE (
                    session_id TEXT PRIMARY KEY NOT NULL,
                    started_at INTEGER NOT NULL,
                    outcome TEXT NOT NULL,
                    snapshot_json TEXT NOT NULL
                )""".trimIndent(),
            )
            database.execSQL("CREATE INDEX practice_started_at ON $TABLE(started_at DESC)")
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("No migration defined from version $oldVersion to $newVersion")
        }
    }

    private companion object {
        const val TABLE = "practice_sessions"
    }
}

private object SnapshotJson {
    fun encode(snapshot: PracticeSnapshot): String = JSONObject().apply {
        put("version", 1)
        put("id", snapshot.id)
        put("startedAtMillis", snapshot.startedAtMillis)
        put("endedAtMillis", snapshot.endedAtMillis ?: JSONObject.NULL)
        put("stage", snapshot.stage.name)
        put("outcome", snapshot.outcome.name)
        put("nextAction", snapshot.nextAction.name)
        put("completedStepCount", snapshot.completedStepCount)
        put("lastSignal", snapshot.lastSignal?.name ?: JSONObject.NULL)
        put("interruptionReason", snapshot.interruptionReason ?: JSONObject.NULL)
        put("events", JSONArray().apply {
            snapshot.events.forEach { event ->
                put(JSONObject().apply {
                    put("pattern", event.pattern.name)
                    put("observedAtMillis", event.observedAtMillis)
                    put("stageBefore", event.stageBefore.name)
                    put("stageAfter", event.stageAfter.name)
                    put("disposition", event.disposition.name)
                })
            }
        })
    }.toString()

    fun decode(value: String): PracticeSnapshot {
        val json = JSONObject(value)
        check(json.getInt("version") == 1) { "Unsupported practice record version" }
        val events = json.getJSONArray("events")
        return PracticeSnapshot(
            id = json.getString("id"),
            startedAtMillis = json.getLong("startedAtMillis"),
            endedAtMillis = if (json.isNull("endedAtMillis")) null else json.getLong("endedAtMillis"),
            stage = PracticeStage.valueOf(json.getString("stage")),
            outcome = PracticeOutcome.valueOf(json.getString("outcome")),
            nextAction = PracticeAction.valueOf(json.getString("nextAction")),
            completedStepCount = json.getInt("completedStepCount"),
            lastSignal = if (json.isNull("lastSignal")) null else SignalPattern.valueOf(json.getString("lastSignal")),
            events = List(events.length()) { index ->
                val event = events.getJSONObject(index)
                SignalObservation(
                    pattern = SignalPattern.valueOf(event.getString("pattern")),
                    observedAtMillis = event.getLong("observedAtMillis"),
                    stageBefore = PracticeStage.valueOf(event.getString("stageBefore")),
                    stageAfter = PracticeStage.valueOf(event.getString("stageAfter")),
                    disposition = ObservationDisposition.valueOf(event.getString("disposition")),
                )
            },
            interruptionReason = if (json.isNull("interruptionReason")) null else json.getString("interruptionReason"),
        )
    }
}
