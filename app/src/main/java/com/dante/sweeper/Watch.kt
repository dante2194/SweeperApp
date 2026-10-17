package com.dante.sweeper

import android.os.FileObserver
import java.io.File
import java.nio.file.Files

@Suppress("DEPRECATION")
private class Obs(path: String, mask: Int, val cb: () -> Unit) : FileObserver(path, mask) {
    override fun onEvent(event: Int, path: String?) = cb()
}

/** inotify watchers per task. Capped so we stay under Android's watch limit. */
class Watcher(private val onChange: (String) -> Unit) {
    private val map = HashMap<String, List<FileObserver>>()

    @Synchronized
    fun stopAll() { map.values.flatten().forEach { it.stopWatching() }; map.clear() }

    @Synchronized
    fun rebuild(t: Task) {
        map.remove(t.id)?.forEach { it.stopWatching() }
        if (!t.enabled || !t.onWatch || t.kind == Kind.SHELL) return
        val dirs = LinkedHashSet<File>()
        val mask: Int
        if (t.kind == Kind.EMPTY_DIRS) {
            mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO
            val excl = t.excludes.items().toSet() + "Android"
            val junk = t.junk.items().toSet()
            for (r in t.body.items()) {
                val root = File(r)
                if (root.isDirectory && Engine.rootOk(root)) walk(root, root, excl, junk, dirs)
            }
        } else if (t.kind == Kind.MOVE_DELETE) {
            mask = FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE
            for (p in t.body.items()) {
                val f = File(p)
                // watch the folder itself (if present) and its nearest existing parent (to catch it reappearing)
                if (f.isDirectory && Engine.watchOk(f)) dirs += f
                var a = f.parentFile
                while (a != null && !a.isDirectory) a = a.parentFile
                if (a != null && Engine.watchOk(a)) dirs += a
            }
        } else if (t.kind == Kind.DELETE_IF_EMPTY) {
            // emptied (DELETE / MOVED_FROM), appeared (CREATE / MOVED_TO), or a file truncated (CLOSE_WRITE)
            mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or
                FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE
            for (p in t.body.items()) {
                val f = File(p)
                if (f.isDirectory && Engine.watchOk(f)) dirs += f
                var a = f.parentFile
                while (a != null && !a.isDirectory) a = a.parentFile
                if (a != null && Engine.watchOk(a)) dirs += a
            }
        } else {
            mask = FileObserver.CREATE or FileObserver.MOVED_TO
            for (p in t.body.items()) {
                // watch the nearest existing parent so we notice when the target (re)appears
                var a = File(p).parentFile
                while (a != null && !a.isDirectory) a = a.parentFile
                if (a != null && Engine.watchOk(a)) dirs += a
            }
        }
        map[t.id] = dirs.map { Obs(it.path, mask) { onChange(t.id) } }.onEach { it.startWatching() }
    }

    private fun walk(root: File, dir: File, excl: Set<String>, junk: Set<String>, acc: MutableSet<File>) {
        if (acc.size >= 3000) return
        acc += dir
        dir.listFiles()?.forEach { k ->
            if (k.isDirectory && !Files.isSymbolicLink(k.toPath()) && k.name !in junk &&
                k.relativeTo(root).path !in excl && k.path !in excl) walk(root, k, excl, junk, acc)
        }
    }
}
