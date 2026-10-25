package com.abdulrehman.simplealarm

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import android.widget.Toast
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private var tick by mutableIntStateOf(0)

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sp = getSharedPreferences("cfg", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null &&
            !sp.getBoolean("askedNotif", false) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            sp.edit().putBoolean("askedNotif", true).apply()
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        try { Scheduler.scheduleAll(this) } catch (e: Exception) { }
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Screen() } }
    }

    override fun onResume() {
        super.onResume()
        tick++
        if (Cfg.guard(this) && Perms.usage(this) && Perms.overlay(this)) {
            try { Guard.start(this) } catch (e: Exception) { }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun syncToClock(list: List<Alarm>) {
        var count = 0
        for (a in list.filter { it.enabled }) {
            val i = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, a.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, a.minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, a.label.ifBlank { "Alarm" })
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            try { startActivity(i); count++ } catch (e: Exception) { }
        }
        toast(if (count > 0) "Sent $count alarms to Clock app" else "No Clock app found")
    }

    private fun loadApps(): List<Pair<String, String>> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    // ---------------------------------------------------------------- screens

    @Composable
    fun Screen() {
        var tab by remember { mutableIntStateOf(0) }
        val names = listOf("Alarms", "Focus", "Limits")
        val icons = listOf("⏰", "🎯", "🛡")
        val ctx = this@MainActivity
        var crash by remember {
            mutableStateOf(ctx.getSharedPreferences("cfg", MODE_PRIVATE).getString("crash", null))
        }
        crash?.let { text ->
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Last crash (send a screenshot)") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text, fontSize = 10.sp) } },
                confirmButton = {
                    TextButton(onClick = {
                        ctx.getSharedPreferences("cfg", MODE_PRIVATE).edit().remove("crash").apply()
                        crash = null
                    }) { Text("Clear") }
                }
            )
        }
        Scaffold(
            bottomBar = {
                NavigationBar {
                    names.forEachIndexed { i, n ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Text(icons[i]) },
                            label = { Text(n) }
                        )
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> AlarmsTab()
                    1 -> FocusTab()
                    else -> LimitsTab()
                }
            }
        }
    }

    @Composable
    fun AlarmsTab() {
        val ctx = this@MainActivity
        var alarms by remember { mutableStateOf(Store.load(ctx)) }
        var dialog by remember { mutableStateOf<Alarm?>(null) }
        var ring by remember { mutableStateOf(Store.ring(ctx)) }

        fun update(list: List<Alarm>) {
            Store.save(ctx, list)
            alarms = Store.load(ctx)
        }

        Box(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp)) {
                Text("Prayers & Alarms", fontSize = 28.sp)
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ring from this app", Modifier.weight(1f))
                    Switch(checked = ring, onCheckedChange = {
                        ring = it
                        Store.setRing(ctx, it)
                        Scheduler.scheduleAll(ctx)
                    })
                }
                Button(
                    onClick = { syncToClock(alarms) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Sync enabled alarms to Clock app") }
                Text(
                    "Turn off \"Ring from this app\" before syncing to avoid double ringing. " +
                        "Delete old synced alarms in your Clock app.",
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))

                LazyColumn {
                    items(alarms, key = { it.id }) { a ->
                        Card(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .clickable { dialog = a }
                        ) {
                            Row(
                                Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("%02d:%02d".format(a.hour, a.minute), fontSize = 32.sp)
                                    if (a.label.isNotBlank()) Text(a.label)
                                }
                                Switch(checked = a.enabled, onCheckedChange = { on ->
                                    val n = a.copy(enabled = on)
                                    Scheduler.schedule(ctx, n)
                                    update(alarms.map { if (it.id == a.id) n else it })
                                })
                                TextButton(onClick = {
                                    Scheduler.cancel(ctx, a.id)
                                    update(alarms.filter { it.id != a.id })
                                }) { Text("Delete") }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
            FloatingActionButton(
                onClick = { dialog = Alarm(-1, 12, 0, "") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) { Text("+", fontSize = 28.sp) }
        }

        dialog?.let { cur ->
            val state = rememberTimePickerState(cur.hour, cur.minute, false)
            var label by remember { mutableStateOf(cur.label) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(if (cur.id == -1) "New alarm" else "Edit alarm") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        TimePicker(state = state)
                        OutlinedTextField(
                            value = label, onValueChange = { label = it },
                            label = { Text("Label") }, singleLine = true
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val id = if (cur.id == -1)
                            (System.currentTimeMillis() % Int.MAX_VALUE).toInt() else cur.id
                        val a = Alarm(id, state.hour, state.minute, label, true)
                        Scheduler.schedule(ctx, a)
                        update(alarms.filter { it.id != id } + a)
                        dialog = null
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } }
            )
        }
    }

    @Composable
    fun Chips(options: List<Int>, selected: Int, unit: String, onPick: (Int) -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o ->
                FilterChip(
                    selected = o == selected,
                    onClick = { onPick(o) },
                    label = { Text(if (o == 0) "Off" else "$o$unit") }
                )
            }
        }
    }

    @Composable
    fun FocusTab() {
        val ctx = this@MainActivity
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }
        var strict by remember { mutableStateOf(Cfg.strict(ctx)) }
        var sess by remember { mutableIntStateOf(Cfg.sessionMin(ctx)) }
        var pf by remember { mutableIntStateOf(Cfg.prayerFocus(ctx)) }
        var custom by remember { mutableStateOf("") }
        val left = Cfg.focusUntil(ctx) - now

        fun start(min: Int) {
            if (min <= 0) return
            Cfg.setFocusUntil(ctx, System.currentTimeMillis() + min * 60000L)
            now = System.currentTimeMillis()
            if (Cfg.limits(ctx).isEmpty()) toast("Pick apps to pause in the Limits tab")
            else if (!Cfg.guard(ctx)) toast("Turn on Scroll guard in the Limits tab")
        }

        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Focus", fontSize = 28.sp)
            Spacer(Modifier.height(12.dp))

            if (left > 0) {
                val s = left / 1000
                Text("%02d:%02d".format(s / 60, s % 60), fontSize = 64.sp)
                Text("Your selected apps are paused. Breathe, you're doing well.")
                Spacer(Modifier.height(8.dp))
                if (!strict) {
                    OutlinedButton(onClick = { Cfg.setFocusUntil(ctx, 0L); now = System.currentTimeMillis() }) {
                        Text("End focus")
                    }
                } else {
                    Text("Strict mode is on: focus can't be ended early.", fontSize = 12.sp)
                }
            } else {
                Text("Start a session")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { start(25) }) { Text("25 min") }
                    Button(onClick = { start(45) }) { Text("45 min") }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("Custom minutes") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { start(custom.toIntOrNull() ?: 0) }) { Text("Start") }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("Settings", fontSize = 20.sp)
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Strict mode")
                    Text("Hides End focus and the break button during focus.", fontSize = 12.sp)
                }
                Switch(checked = strict, onCheckedChange = {
                    strict = it
                    Cfg.setStrict(ctx, it)
                })
            }
            Spacer(Modifier.height(16.dp))

            Text("Auto focus at prayer time (minutes)")
            Chips(listOf(0, 10, 15, 20, 30), pf, "m") {
                pf = it
                Cfg.setPrayerFocus(ctx, it)
                Scheduler.scheduleAll(ctx)
            }
            Text("Starts when Fajr, Dhuhr, Asr, Maghrib or Isha alarms fire.", fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))

            Text("Scroll reminder after continuous use (minutes)")
            Chips(listOf(0, 5, 10, 15, 20), sess, "m") {
                sess = it
                Cfg.setSessionMin(ctx, it)
            }
            Text("Shows a pause screen if you stay in a limited app this long.", fontSize = 12.sp)
        }
    }

    @Composable
    fun LimitsTab() {
        val ctx = this@MainActivity
        val t = tick
        var guard by remember { mutableStateOf(Cfg.guard(ctx)) }
        var limits by remember { mutableStateOf(Cfg.limits(ctx)) }
        var usage by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
        val usageOk = remember(t) { Perms.usage(ctx) }
        val overlayOk = remember(t) { Perms.overlay(ctx) }
        var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadApps() } }
        var pick by remember { mutableStateOf<Pair<String, String>?>(null) }

        LaunchedEffect(usageOk) {
            while (true) {
                if (Perms.usage(ctx)) {
                    usage = withContext(Dispatchers.IO) {
                        try { Usage.today(ctx).first } catch (e: Exception) { emptyMap<String, Long>() }
                    }
                }
                delay(5000)
            }
        }

        val sorted = apps.sortedBy { if (limits.containsKey(it.first)) 0 else 1 }

        Column(Modifier.padding(16.dp)) {
            Text("Scroll guard", fontSize = 28.sp)
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Usage access: " + if (usageOk) "granted" else "needed")
                    Text("Lets the app see how long you use each app.", fontSize = 12.sp)
                }
                if (!usageOk) Button(onClick = {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }) { Text("Grant") }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Display over other apps: " + if (overlayOk) "granted" else "needed")
                    Text("Lets the break screen appear above limited apps.", fontSize = 12.sp)
                }
                if (!overlayOk) Button(onClick = {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }) { Text("Grant") }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Scroll guard on", Modifier.weight(1f))
                Switch(checked = guard, onCheckedChange = { on ->
                    if (on && !(usageOk && overlayOk)) {
                        toast("Grant both permissions first")
                    } else {
                        guard = on
                        Cfg.setGuard(ctx, on)
                        if (on) Guard.start(ctx) else Guard.stop(ctx)
                    }
                })
            }
            Text(
                "Shows a break screen over limited apps. It can't lock them: Return to home always works. " +
                    "Battery saver may stop the guard; set this app to Unrestricted battery.",
                fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            Text("Tap an app to set its limit")
            Spacer(Modifier.height(4.dp))

            LazyColumn {
                items(sorted, key = { it.first }) { app ->
                    val lim = limits[app.first]
                    val used = (usage[app.first] ?: 0L) / 60000L
                    Row(
                        Modifier.fillMaxWidth().clickable { pick = app }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(app.second, Modifier.weight(1f))
                        if (lim != null) {
                            Text(if (lim == 0) "Focus only" else "${used}m / ${lim}m")
                        }
                    }
                    HorizontalDivider()
                }
            }
        }

        pick?.let { app ->
            val opts = listOf<Pair<String, Int?>>(
                "Focus mode only" to 0,
                "15 min / day" to 15,
                "30 min / day" to 30,
                "45 min / day" to 45,
                "60 min / day" to 60,
                "90 min / day" to 90,
                "Remove" to null
            )
            AlertDialog(
                onDismissRequest = { pick = null },
                title = { Text(app.second) },
                text = {
                    Column {
                        opts.forEach { (name, v) ->
                            TextButton(
                                onClick = {
                                    Cfg.setLimit(ctx, app.first, v)
                                    limits = Cfg.limits(ctx)
                                    pick = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(name) }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { pick = null }) { Text("Cancel") } }
            )
        }
    }
}
