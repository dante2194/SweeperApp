package com.dante.sweeper

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class Kind { SHELL, EMPTY_DIRS, DELETE_PATHS, MOVE_DELETE, DELETE_IF_EMPTY }

data class Task(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New task",
    val enabled: Boolean = false,
    val kind: Kind = Kind.SHELL,
    val body: String = "",
    val dest: String = "",
    val excludes: String = "Android",
    val junk: String = ".thumbnails",
    val intervalSec: Int = 0,
    val onScreenOff: Boolean = false,
    val onScreenOn: Boolean = false,
    val onBoot: Boolean = false,
    val onWatch: Boolean = false,
    val graceSec: Int = 3
)

/** One entry per line; blank lines and # comments ignored. */
fun String.items(): List<String> =
    lines().map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() && !it.startsWith("#") }

private fun Task.toJson() = JSONObject()
    .put("id", id).put("name", name).put("enabled", enabled).put("kind", kind.name)
    .put("body", body).put("dest", dest).put("excludes", excludes).put("junk", junk)
    .put("intervalSec", intervalSec).put("onScreenOff", onScreenOff)
    .put("onScreenOn", onScreenOn).put("onBoot", onBoot)
    .put("onWatch", onWatch).put("graceSec", graceSec)

private fun JSONObject.toTask() = Task(
    getString("id"), getString("name"), getBoolean("enabled"),
    Kind.valueOf(getString("kind")), getString("body"), optString("dest", ""), getString("excludes"),
    getString("junk"), getInt("intervalSec"), getBoolean("onScreenOff"),
    getBoolean("onScreenOn"), getBoolean("onBoot"), getBoolean("onWatch"), getInt("graceSec")
)

object Store {
    private var sp: SharedPreferences? = null
    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks
    // last result per task: in memory only, so 2-second loops never write to flash
    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    val status: StateFlow<Map<String, String>> = _status

    var serviceEnabled: Boolean
        get() = sp!!.getBoolean("svc", false)
        set(v) { sp!!.edit().putBoolean("svc", v).apply() }

    @Synchronized
    fun init(c: Context) {
        if (sp != null) return
        sp = c.applicationContext.getSharedPreferences("sweeper", Context.MODE_PRIVATE)
        val raw = sp!!.getString("tasks", null)
        if (raw == null) {
            _tasks.value = seed(); persist()
        } else {
            val a = JSONArray(raw)
            _tasks.value = (0 until a.length()).map { a.getJSONObject(it).toTask() }
        }
    }

    private fun persist() {
        sp!!.edit().putString("tasks", JSONArray(_tasks.value.map { it.toJson() }).toString()).apply()
    }

    @Synchronized
    fun upsert(t: Task) {
        val l = _tasks.value
        _tasks.value = if (l.any { it.id == t.id }) l.map { if (it.id == t.id) t else it } else l + t
        persist()
    }

    @Synchronized
    fun remove(id: String) { _tasks.value = _tasks.value.filter { it.id != id }; persist() }

    @Synchronized
    fun note(id: String, msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _status.value = _status.value + (id to "$time  $msg")
    }

    // Starter tasks built from your scripts. All disabled: review, then switch on.
    private fun seed() = listOf(
        Task(name = "Empty folders: internal", kind = Kind.EMPTY_DIRS, body = "/sdcard", onScreenOff = true),
        Task(name = "Empty folders: SD cards", kind = Kind.EMPTY_DIRS,
            body = "/storage/5D73-E800\n/storage/57FB-B400", onScreenOff = true),
        Task(name = "Junk paths", kind = Kind.DELETE_PATHS, onScreenOff = true, onWatch = true, body = """
            /sdcard/com.android.settings
            /sdcard/MIUI
            /sdcard/sepolicy_extends
            /sdcard/.system_config
            /sdcard/.SHAREit
            /sdcard/Documents/SmartTubeBackup
            /storage/5D73-E800/backups/.exmu-cfg1.data
            /sdcard/Download/.exmu-cfg1.data
            /sdcard/Music/.thumbnails
            /storage/emulated/0/SHAREit Premium/.thumbnails
            /storage/emulated/0/SHAREit Premium/.mediathumbs
            /storage/emulated/0/SHAREit Premium/.caches
            /sdcard/Android/media/org.telegram.messenger/Telegram/Telegram Images
            /storage/5D73-E800/DCIM
            /storage/emulated/0/MxPlayerAd
            /storage/emulated/0/Movies/.thumbnails
            /storage/emulated/0/Pictures/.thumbnails
        """.trimIndent()),
        Task(name = "Move & delete: Screenshots", kind = Kind.MOVE_DELETE,
            body = "/sdcard/DCIM/Screenshots", dest = "/storage/emulated/0/Screenshots",
            onWatch = true, graceSec = 1, onScreenOff = true),
        Task(name = "Shell example", kind = Kind.SHELL, body = "echo hello from sweeper; date", intervalSec = 60)
    )
}
