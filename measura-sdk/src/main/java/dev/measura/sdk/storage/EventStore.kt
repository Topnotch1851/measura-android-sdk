package dev.measura.sdk.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import org.json.JSONObject

/**
 * Persistent SQLite queue for offline event storage.
 * Events are stored when network is unavailable and flushed on next launch.
 *
 * Every operation is defensive (audit L-1): a storage failure (disk full,
 * corrupt db) must degrade to losing queued analytics events - never crash
 * the HOST app or kill the flush scheduler.
 */
internal class PersistentEventStore(
    private val context: Context,
    private val maxSize: Int,
) {
    // Lazy: opening the database can itself throw on corrupt storage
    private val db: SQLiteDatabase? by lazy {
        try {
            EventDatabase(context).writableDatabase
        } catch (e: Exception) {
            android.util.Log.e("MeasuraSDK", "Event store unavailable: ${e.message}")
            null
        }
    }

    fun saveAll(events: List<Map<String, Any>>) {
        val database = db ?: return
        try {
            database.beginTransaction()
            try {
                for (event in events) {
                    val values = ContentValues().apply {
                        put("event_json", JSONObject(event).toString())
                        put("created_at", System.currentTimeMillis())
                    }
                    database.insert("pending_events", null, values)
                }
                // Enforce max size (delete oldest if over limit)
                database.execSQL("""
                    DELETE FROM pending_events WHERE id NOT IN (
                        SELECT id FROM pending_events ORDER BY created_at DESC LIMIT $maxSize
                    )
                """)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        } catch (e: Exception) {
            android.util.Log.e("MeasuraSDK", "Failed to persist events: ${e.message}")
        }
    }

    fun loadAll(): List<Map<String, Any>> {
        val database = db ?: return emptyList()
        val events = mutableListOf<Map<String, Any>>()
        try {
            database.query("pending_events", arrayOf("event_json"), null, null, null, null, "created_at ASC")
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        val json = cursor.getString(0)
                        val jsonObj = JSONObject(json)
                        val map = mutableMapOf<String, Any>()
                        jsonObj.keys().forEach { key -> map[key] = jsonObj.get(key) }
                        events.add(map)
                    }
                }
        } catch (e: Exception) {
            android.util.Log.e("MeasuraSDK", "Failed to load persisted events: ${e.message}")
        }
        return events
    }

    /**
     * Same rows as loadAll(), plus the highest `id` among them (null when the
     * table was empty) - the anchor clearSentUpTo() needs to delete exactly
     * the rows this call actually read, and no others.
     */
    fun loadAllWithMaxId(): Pair<List<Map<String, Any>>, Long?> {
        val database = db ?: return emptyList<Map<String, Any>>() to null
        val events = mutableListOf<Map<String, Any>>()
        var maxId: Long? = null
        try {
            database.query(
                "pending_events", arrayOf("id", "event_json"), null, null, null, null, "created_at ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val json = cursor.getString(1)
                    val jsonObj = JSONObject(json)
                    val map = mutableMapOf<String, Any>()
                    jsonObj.keys().forEach { key -> map[key] = jsonObj.get(key) }
                    events.add(map)
                    maxId = maxOf(maxId ?: id, id)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MeasuraSDK", "Failed to load persisted events: ${e.message}")
        }
        return events to maxId
    }

    /**
     * The oldest [limit] rows, with their ids, for draining in batches the
     * server will accept.
     *
     * The drain used to load every stored row and send them as one request.
     * /ingest refuses a batch over 50 events (MAX_BATCH_SIZE), so a device
     * whose queue grew past 50 - one on mobile data with Wi-Fi only on, say -
     * was rejected on every attempt and never drained again. Loading a bounded
     * batch by id keeps each request inside that limit, and returning the ids
     * lets the caller delete exactly the rows it sent (see [deleteIds]).
     */
    fun loadOldest(limit: Int): Pair<List<Map<String, Any>>, List<Long>> {
        val database = db ?: return emptyList<Map<String, Any>>() to emptyList()
        val events = mutableListOf<Map<String, Any>>()
        val ids = mutableListOf<Long>()
        try {
            database.query(
                "pending_events", arrayOf("id", "event_json"), null, null, null, null,
                "id ASC", limit.coerceAtLeast(1).toString(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val jsonObj = JSONObject(cursor.getString(1))
                    val map = mutableMapOf<String, Any>()
                    jsonObj.keys().forEach { key -> map[key] = jsonObj.get(key) }
                    events.add(map)
                    ids.add(cursor.getLong(0))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MeasuraSDK", "Failed to load persisted events: ${e.message}")
        }
        return events to ids
    }

    /**
     * Deletes exactly these rows. Scoped by id, like [clearSentUpTo], so a row
     * a concurrent saveAll() inserts while a batch is in flight is never
     * touched.
     */
    fun deleteIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        try {
            val placeholders = ids.joinToString(",") { "?" }
            db?.delete("pending_events", "id IN ($placeholders)", ids.map { it.toString() }.toTypedArray())
        } catch (e: Exception) {
            android.util.Log.e("MeasuraSDK", "Failed to delete sent events: ${e.message}")
        }
    }

    fun clearAll() {
        try {
            db?.delete("pending_events", null, null)
        } catch (e: Exception) {
            android.util.Log.e("MeasuraSDK", "Failed to clear persisted events: ${e.message}")
        }
    }

    /**
     * Deletes only rows with id <= maxId - the rows a specific loadAllWithMaxId()
     * call actually returned. AUTOINCREMENT ids are strictly increasing and
     * SQLite serializes writers, so any row a CONCURRENT saveAll() inserted
     * after that load has a strictly greater id and is never touched here.
     *
     * clearAll()'s unconditional `DELETE FROM pending_events WHERE null` had
     * no such scoping: flushPersistedEvents() runs once at startup and can
     * take seconds (network-bound), and doFlush() - fired by the periodic
     * scheduler, trackInstall(), an explicit flush(), or the low-battery
     * branch of trackEvent(), all on the same scope - can call saveAll()
     * during that exact window on a send failure. clearAll() then deleted
     * those brand-new, never-sent rows along with the ones that actually
     * succeeded: a real, reachable "events vanish on relaunch" path, not a
     * theoretical one, since both flush paths are live within seconds of
     * every app start that has anything left over from a previous session.
     */
    fun clearSentUpTo(maxId: Long) {
        try {
            db?.delete("pending_events", "id <= ?", arrayOf(maxId.toString()))
        } catch (e: Exception) {
            android.util.Log.e("MeasuraSDK", "Failed to clear sent events: ${e.message}")
        }
    }
}

/**
 * In-memory event queue with thread-safe access.
 */
internal class EventQueue(private val batchSize: Int) {
    private val queue = ArrayDeque<Map<String, Any>>()
    private val lock = Any()

    fun enqueue(event: Map<String, Any>) = synchronized(lock) { queue.addLast(event) }

    fun drainAll(): List<Map<String, Any>> = synchronized(lock) {
        val snapshot = queue.toList()
        queue.clear()
        snapshot
    }

    fun size(): Int = synchronized(lock) { queue.size }
}

private class EventDatabase(context: Context) :
    SQLiteOpenHelper(context, "measura_events.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS pending_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_json TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_created_at ON pending_events(created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS pending_events")
        onCreate(db)
    }
}
