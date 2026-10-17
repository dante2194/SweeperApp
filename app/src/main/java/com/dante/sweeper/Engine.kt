package com.dante.sweeper

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object Engine {
    private val volume = Regex("/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")
    private val never = setOf("/", "/sdcard", "/storage", "/storage/emulated", "/storage/emulated/0",
        "/storage/self", "/storage/self/primary")

    private fun norm(f: File) = f.toPath().normalize().toString()
    private fun inStorage(p: String) = p.startsWith("/sdcard") || p.startsWith("/storage/")

    /** A sweep root may be a storage root (it is never deleted itself). */
    fun rootOk(f: File): Boolean {
        val p = norm(f)
        return inStorage(p) && p != "/storage" && p != "/storage/emulated"
    }

    /** A delete target must be INSIDE storage, never a storage root. */
    fun targetOk(f: File): Boolean {
        val p = norm(f)
        return inStorage(p) && p !in never && !volume.matches(p)
    }

    fun watchOk(f: File): Boolean = rootOk(f) && norm(f).length > 8

    fun run(t: Task, dry: Boolean): String = try {
        when (t.kind) {
            Kind.SHELL -> if (dry) "Dry run isn't available for shell tasks." else shell(t.body)
            Kind.EMPTY_DIRS -> emptyDirs(t, dry)
            Kind.DELETE_PATHS -> deletePaths(t, dry)
            Kind.MOVE_DELETE -> moveDelete(t, dry)
            Kind.DELETE_IF_EMPTY -> deleteIfEmpty(t, dry)
        }
    } catch (e: Exception) { "Error: ${e.message}" }

    /** Moves [src] into [dstDir]; renames on collision; falls back to copy+delete across volumes. */
    private fun moveInto(src: File, dstDir: File): Boolean {
        var target = File(dstDir, src.name)
        var n = 1
        while (target.exists() || isLink(target)) {
            val ext = if (src.isFile && src.extension.isNotEmpty()) "." + src.extension else ""
            val base = if (ext.isEmpty()) src.name else src.nameWithoutExtension
            target = File(dstDir, "${base}_$n$ext"); n++
        }
        if (src.renameTo(target)) return true
        return try {
            if (src.isDirectory) src.copyRecursively(target, overwrite = false) && src.deleteRecursively()
            else { src.copyTo(target, overwrite = false); src.delete() }
        } catch (e: Exception) { false }
    }

    /** Move everything out of each source folder into dest, then delete the source folder. */
    private fun moveDelete(t: Task, dry: Boolean): String {
        val out = mutableListOf<String>()
        val dest = t.dest.trim().trimEnd('/')
        if (dest.isEmpty()) return "No destination set."
        val dstDir = File(dest)
        if (!rootOk(dstDir)) return "Refused destination: $dest"
        val dp = norm(dstDir)
        for (p in t.body.items()) {
            val src = File(p)
            if (!targetOk(src)) { out += "Refused: $p"; continue }
            val sp = norm(src)
            if (dp == sp || dp.startsWith("$sp/")) { out += "Refused (destination inside source): $p"; continue }
            if (!src.isDirectory) continue
            val kids = src.listFiles()
            if (kids == null) { out += "Can't read: $p"; continue }
            if (dry) {
                kids.forEach { out += "Would move: ${it.name}" }
                out += "Would delete folder: $p"; continue
            }
            if (!dstDir.isDirectory && !dstDir.mkdirs()) { out += "Can't create: $dest"; continue }
            var allOk = true
            for (k in kids) {
                if (moveInto(k, dstDir)) out += "Moved: ${k.name}"
                else { allOk = false; out += "Failed: ${k.name}" }
            }
            if (allOk) out += (if (src.deleteRecursively()) "Deleted folder: " else "Failed: ") + p
            else out += "Kept folder (some moves failed): $p"
        }
        return report(out)
    }

    private fun shell(script: String): String {
        val p = ProcessBuilder("/system/bin/sh", "-c", script).redirectErrorStream(true).start()
        val sb = StringBuilder()
        val reader = thread {
            p.inputStream.bufferedReader().use { r ->
                val buf = CharArray(1024)
                while (true) {
                    val n = r.read(buf); if (n < 0) break
                    if (sb.length < 4000) sb.append(buf, 0, n)
                }
            }
        }
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); return "Timed out after 60s" }
        reader.join(1000)
        return "exit ${p.exitValue()}\n" + sb.toString().trim()
    }

    private fun report(out: List<String>): String =
        if (out.isEmpty()) "Nothing to do."
        else out.take(30).joinToString("\n") + if (out.size > 30) "\n…and ${out.size - 30} more" else ""

    private fun isLink(f: File) = Files.isSymbolicLink(f.toPath())

    private fun deletePaths(t: Task, dry: Boolean): String {
        val out = mutableListOf<String>()
        for (p in t.body.items()) {
            val f = File(p)
            if (!targetOk(f)) { out += "Refused: $p"; continue }
            if (!f.exists() && !isLink(f)) continue
            if (dry) { out += "Would delete: $p"; continue }
            val ok = if (isLink(f)) f.delete() else f.deleteRecursively()
            out += (if (ok) "Deleted: " else "Failed: ") + p
        }
        return report(out)
    }

    /** A folder with no entries, or a zero-byte file. Symlinks never count. */
    private fun isEmptyTarget(f: File): Boolean = when {
        isLink(f) -> false
        f.isDirectory -> f.list()?.isEmpty() == true
        f.isFile -> f.length() == 0L
        else -> false
    }

    /** Deletes each listed path only if it is empty right now. File.delete() on a folder fails if it filled up meanwhile. */
    private fun deleteIfEmpty(t: Task, dry: Boolean): String {
        val out = mutableListOf<String>()
        for (p in t.body.items()) {
            val f = File(p)
            if (!targetOk(f)) { out += "Refused: $p"; continue }
            if (!isEmptyTarget(f)) continue
            if (dry) { out += "Would delete empty: $p"; continue }
            out += (if (f.delete()) "Deleted empty: " else "Failed: ") + p
        }
        return report(out)
    }

    private fun emptyDirs(t: Task, dry: Boolean): String {
        val out = mutableListOf<String>()
        val excl = t.excludes.items().toSet() + "Android"   // Android folder is always left alone
        val junk = t.junk.items().toSet()
        for (r in t.body.items()) {
            val root = File(r)
            if (!root.isDirectory) continue
            if (!rootOk(root)) { out += "Refused root: $r"; continue }
            sweep(root, root, excl, junk, dry, out)
        }
        return report(out)
    }

    /** Deletes empty-ish subfolders. Returns true if [dir] holds nothing but junk. */
    private fun sweep(root: File, dir: File, excl: Set<String>, junk: Set<String>,
                      dry: Boolean, out: MutableList<String>): Boolean {
        val kids = dir.listFiles() ?: return false
        var keep = false
        for (k in kids) {
            when {
                isLink(k) -> keep = true
                k.name in junk -> {}
                k.isDirectory -> {
                    if (k.relativeTo(root).path in excl || k.path in excl) keep = true
                    else if (sweep(root, k, excl, junk, dry, out)) {
                        if (dry) out += "Would delete: ${k.path}"
                        else out += (if (k.deleteRecursively()) "Deleted: " else "Failed: ") + k.path
                    } else keep = true
                }
                else -> keep = true
            }
        }
        return !keep
    }
}
