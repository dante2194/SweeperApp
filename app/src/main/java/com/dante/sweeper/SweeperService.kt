package com.dante.sweeper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class SweeperService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loops = HashMap<String, Job>()
    private val debounce = HashMap<String, Job>()
    private val busy = ConcurrentHashMap.newKeySet<String>()
    private val lock = Mutex()
    private lateinit var watcher: Watcher
    private var screenRx: BroadcastReceiver? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        watcher = Watcher { id -> onWatchEvent(id) }
        goForeground()
        screenRx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val off = i.action == Intent.ACTION_SCREEN_OFF
                scope.launch { fire { if (off) it.onScreenOff else it.onScreenOn } }
            }
        }
        ContextCompat.registerReceiver(this, screenRx,
            IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) },
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        reload()
        if (i?.getBooleanExtra("boot", false) == true) {
            // give the SD card a moment to mount after boot
            scope.launch { delay(15_000); fire { it.onBoot } }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel(); watcher.stopAll()
        screenRx?.let { unregisterReceiver(it) }
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("sweeper", "Sweeper running", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, "sweeper")
            .setSmallIcon(R.drawable.ic_notif).setContentTitle("Sweeper is active")
            .setContentText("Running your cleanup tasks").setContentIntent(open)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    private fun reload() = scope.launch {
        lock.withLock {
            val on = Store.tasks.value.filter { it.enabled }
            loops.values.forEach { it.cancel() }; loops.clear()
            on.filter { it.intervalSec > 0 }.forEach { t ->
                loops[t.id] = scope.launch {
                    while (isActive) { delay(t.intervalSec * 1000L); runTask(t.id) }
                }
            }
            watcher.stopAll(); on.forEach { watcher.rebuild(it) }
        }
    }

    private fun fire(pred: (Task) -> Boolean) {
        Store.tasks.value.filter { it.enabled && pred(it) }.forEach { runTask(it.id) }
    }

    private fun runTask(id: String) {
        val t = Store.tasks.value.firstOrNull { it.id == id && it.enabled } ?: return
        if (!busy.add(id)) return
        try { Store.note(id, Engine.run(t, false)) } finally { busy.remove(id) }
    }

    /** Debounced: wait [graceSec] after the LAST event so apps can finish writing. */
    private fun onWatchEvent(id: String) {
        val t = Store.tasks.value.firstOrNull { it.id == id } ?: return
        synchronized(debounce) {
            debounce[id]?.cancel()
            debounce[id] = scope.launch {
                val minGrace = if (t.kind == Kind.DELETE_IF_EMPTY) 0 else 1
                delay(t.graceSec.coerceAtLeast(minGrace) * 1000L)
                runTask(id)
                Store.tasks.value.firstOrNull { it.id == id }?.let { watcher.rebuild(it) }
            }
        }
    }

    companion object {
        fun start(c: Context, boot: Boolean = false) {
            ContextCompat.startForegroundService(c,
                Intent(c, SweeperService::class.java).putExtra("boot", boot))
        }
        fun stop(c: Context) { c.stopService(Intent(c, SweeperService::class.java)) }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Store.init(c)
        if (Store.serviceEnabled)
            SweeperService.start(c, boot = i.action != Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
