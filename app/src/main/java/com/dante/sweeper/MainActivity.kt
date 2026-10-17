package com.dante.sweeper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)
    private val legacyPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    override fun onCreate(b: android.os.Bundle?) {
        super.onCreate(b)
        Store.init(this)
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val scheme = if (Build.VERSION.SDK_INT >= 31)
                (if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx))
            else (if (dark) darkColorScheme() else lightColorScheme())
            MaterialTheme(colorScheme = scheme) { App(tick) }
        }
    }

    override fun onResume() { super.onResume(); tick++ }

    // ---------- permissions ----------
    private fun hasFiles() = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    private fun hasNotif() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun hasBattery() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun askFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (e: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else legacyPerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    private fun askNotif() { if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }
    @SuppressLint("BatteryLife")
    private fun askBattery() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }
    private fun sync() { if (Store.serviceEnabled) SweeperService.start(this) }

    // ---------- screens ----------
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun App(tick: Int) {
        val tasks by Store.tasks.collectAsState()
        val status by Store.status.collectAsState()
        var editing by remember { mutableStateOf<Task?>(null) }
        var output by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        fun runNow(t: Task, dry: Boolean) {
            output = "Running…"
            scope.launch(Dispatchers.IO) {
                val r = Engine.run(t, dry)
                if (!dry) Store.note(t.id, r)
                output = (if (dry) "DRY RUN (nothing deleted)\n" else "") + r
            }
        }

        Scaffold(
            topBar = { TopAppBar(title = { Text("Sweeper") }) },
            floatingActionButton = {
                FloatingActionButton(onClick = { editing = Task() }) { Icon(Icons.Default.Add, "Add task") }
            }
        ) { pad ->
            LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { StatusCard(tick) }
                items(tasks, key = { it.id }) { t ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.name, style = MaterialTheme.typography.titleMedium)
                                    Text(summary(t), style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = t.enabled, onCheckedChange = {
                                    Store.upsert(t.copy(enabled = it)); sync()
                                })
                            }
                            status[t.id]?.let {
                                Text(it, Modifier.padding(top = 6.dp), maxLines = 3,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Row {
                                TextButton({ runNow(t, false) }) { Text("Run") }
                                if (t.kind != Kind.SHELL) TextButton({ runNow(t, true) }) { Text("Dry run") }
                                TextButton({ editing = t }) { Text("Edit") }
                                TextButton({ Store.remove(t.id); sync() }) { Text("Delete") }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(72.dp)) }
            }
        }

        editing?.let { Editor(it, { editing = null }) { saved -> Store.upsert(saved); editing = null; sync() } }
        output?.let {
            AlertDialog(onDismissRequest = { output = null },
                confirmButton = { TextButton({ output = null }) { Text("OK") } },
                title = { Text("Result") },
                text = {
                    Text(it, Modifier.verticalScroll(rememberScrollState()),
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                })
        }
    }

    private fun summary(t: Task): String {
        val s = mutableListOf<String>()
        if (t.intervalSec > 0) s += "every ${t.intervalSec}s"
        if (t.onScreenOff) s += "screen off"
        if (t.onScreenOn) s += "screen on"
        if (t.onBoot) s += "boot"
        if (t.onWatch) s += if (t.graceSec == 0) "file change (instant)" else "file change (${t.graceSec}s grace)"
        val kind = when (t.kind) { Kind.SHELL -> "Shell"; Kind.EMPTY_DIRS -> "Empty folders"
            Kind.DELETE_PATHS -> "Delete paths"; Kind.MOVE_DELETE -> "Move & delete"
            Kind.DELETE_IF_EMPTY -> "Delete if empty" }
        return "$kind · " + if (s.isEmpty()) "manual only" else s.joinToString(", ")
    }

    @Composable
    private fun StatusCard(tick: Int) {
        var svc by remember { mutableStateOf(Store.serviceEnabled) }
        val files = remember(tick) { hasFiles() }
        val notif = remember(tick) { hasNotif() }
        val battery = remember(tick) { hasBattery() }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Background service", style = MaterialTheme.typography.titleMedium)
                        Text("Starts itself after reboot", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = svc, onCheckedChange = {
                        svc = it; Store.serviceEnabled = it
                        if (it) { if (!hasNotif()) askNotif(); SweeperService.start(this@MainActivity) }
                        else SweeperService.stop(this@MainActivity)
                    })
                }
                PermRow("All files access (required)", files) { askFiles() }
                PermRow("Notifications", notif) { askNotif() }
                PermRow("Battery: unrestricted", battery) { askBattery() }
            }
        }
    }

    @Composable
    private fun PermRow(label: String, ok: Boolean, onGrant: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            if (ok) Text("✓ granted") else Button(onGrant) { Text("Grant") }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Editor(task: Task, onClose: () -> Unit, onSave: (Task) -> Unit) {
        var d by remember { mutableStateOf(task) }
        AlertDialog(
            onDismissRequest = onClose,
            confirmButton = { TextButton({ onSave(d) }) { Text("Save") } },
            dismissButton = { TextButton(onClose) { Text("Cancel") } },
            title = { Text("Task") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(d.name, { d = d.copy(name = it) }, label = { Text("Name") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(d.kind == Kind.SHELL, { d = d.copy(kind = Kind.SHELL, onWatch = false) }, { Text("Shell") })
                        FilterChip(d.kind == Kind.EMPTY_DIRS, { d = d.copy(kind = Kind.EMPTY_DIRS) }, { Text("Empty folders") })
                        FilterChip(d.kind == Kind.DELETE_PATHS, { d = d.copy(kind = Kind.DELETE_PATHS) }, { Text("Delete paths") })
                        FilterChip(d.kind == Kind.MOVE_DELETE, { d = d.copy(kind = Kind.MOVE_DELETE) }, { Text("Move & delete") })
                        FilterChip(d.kind == Kind.DELETE_IF_EMPTY, { d = d.copy(kind = Kind.DELETE_IF_EMPTY) }, { Text("Delete if empty") })
                    }
                    OutlinedTextField(d.body, { d = d.copy(body = it) },
                        label = { Text(when (d.kind) {
                            Kind.SHELL -> "Shell script"
                            Kind.EMPTY_DIRS -> "Roots to clean (one per line)"
                            Kind.DELETE_PATHS -> "Paths to delete (one per line)"
                            Kind.MOVE_DELETE -> "Folders to move out & delete (one per line)"
                            Kind.DELETE_IF_EMPTY -> "Folders/files to delete when empty (one per line)" }) },
                        minLines = 4, modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                    if (d.kind == Kind.MOVE_DELETE) {
                        OutlinedTextField(d.dest, { d = d.copy(dest = it) },
                            label = { Text("Move contents to (folder)") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                    }
                    if (d.kind == Kind.EMPTY_DIRS) {
                        OutlinedTextField(d.excludes, { d = d.copy(excludes = it) },
                            label = { Text("Never touch (Android is always skipped)") },
                            modifier = Modifier.fillMaxWidth(), minLines = 2)
                        OutlinedTextField(d.junk, { d = d.copy(junk = it) },
                            label = { Text("Ignore names, e.g. .thumbnails") },
                            modifier = Modifier.fillMaxWidth(), minLines = 2)
                    }
                    Text("Run when…", style = MaterialTheme.typography.titleSmall)
                    Num("Every N seconds (0 = off)", d.intervalSec) { d = d.copy(intervalSec = it) }
                    if (d.intervalSec in 1..4) Text("Very short intervals drain battery.", style = MaterialTheme.typography.bodySmall)
                    Check("Screen turns off", d.onScreenOff) { d = d.copy(onScreenOff = it) }
                    Check("Screen turns on", d.onScreenOn) { d = d.copy(onScreenOn = it) }
                    Check("After boot", d.onBoot) { d = d.copy(onBoot = it) }
                    if (d.kind != Kind.SHELL) {
                        Check("Instantly on file change", d.onWatch) { d = d.copy(onWatch = it) }
                        if (d.onWatch) {
                            val empty = d.kind == Kind.DELETE_IF_EMPTY
                            Num(if (empty) "Grace seconds (0 = instant)" else "Grace seconds before acting", d.graceSec) {
                                d = d.copy(graceSec = it.coerceAtLeast(if (empty) 0 else 1))
                            }
                        }
                    }
                }
            })
    }

    @Composable
    private fun Check(label: String, v: Boolean, onV: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().clickable { onV(!v) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(v, onV); Text(label)
        }
    }

    @Composable
    private fun Num(label: String, v: Int, onV: (Int) -> Unit) {
        OutlinedTextField(if (v == 0) "" else v.toString(),
            { onV(it.filter(Char::isDigit).take(5).toIntOrNull() ?: 0) },
            label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth())
    }
}
