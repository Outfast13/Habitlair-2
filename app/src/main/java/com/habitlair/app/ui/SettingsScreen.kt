@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.habitlair.app.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.habitlair.app.MainActivity
import com.habitlair.app.ai.AiConfig
import com.habitlair.app.data.*
import com.habitlair.app.widget.DynamicWidgetReceiver
import com.habitlair.app.widget.MidnightWidgetReceiver
import com.habitlair.app.widget.SunriseWidgetReceiver
import com.habitlair.app.work.Notifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(nav: NavController) {
    val vm = appViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snack) },
        topBar = { TopAppBar(windowInsets = WindowInsets(0, 0, 0, 0), title = { Text("Settings") }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }
    ) { pv ->
        val cur = s
        if (cur == null) { Box(Modifier.padding(pv).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Scaffold }
        Column(Modifier.padding(pv).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column {
                    ShortcutRow("AI change history", "Review and undo anything the AI changed", Icons.Filled.History) { nav.navigate("aihistory") }
                    ShortcutRow("Categories", "Group your habits", Icons.Filled.Category) { nav.navigate("categories") }
                }
            }
            Section("AI Configuration", Icons.Filled.AutoAwesome, startOpen = false) { AiSection(cur, vm) }
            Section("Theme & UI", Icons.Filled.Palette) { ThemeSection(cur, vm) }
            Section("Accessibility", Icons.Filled.Accessibility) {
                SwitchRow("Text labels on attachment buttons", cur.labelsOnIcons,
                    supporting = "Off: icons only (Media, Camera, Audio, Files). On: icons with their names.") { v -> vm.update { it.copy(labelsOnIcons = v) } }
                SwitchRow("Animations", cur.animations, supporting = "Turn off for no sliding or fading between screens.") { v -> vm.update { it.copy(animations = v) } }
                SwitchRow("Compact layout", cur.compact, supporting = "Tighter rows so more fits on screen.") { v -> vm.update { it.copy(compact = v) } }
            }
            Section("Notifications & Alarms", Icons.Filled.Notifications) { NotificationSection(cur, vm) }
            Section("Backups", Icons.Filled.Backup) { BackupSection(vm) }
            Section("Home-screen widget", Icons.Filled.Widgets) { WidgetSection() }
        }
    }
}

@Composable
private fun ShortcutRow(title: String, sub: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        headlineContent = { Text(title) }, supportingContent = { Text(sub) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.Filled.ChevronRight, null) }
    )
}

@Composable
private fun Section(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, startOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    Card(Modifier.fillMaxWidth().animateContentSize(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "Collapse" else "Expand")
        }
        if (open) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, supporting: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun AiSection(s: AppSettings, vm: SettingsViewModel) {
    val c = LocalContainer.current
    var provider by remember { mutableStateOf(s.provider) }
    val models = remember { mutableStateMapOf<Provider, String>().apply { Provider.entries.forEach { put(it, s.modelFor(it)) } } }
    val keys = remember { mutableStateMapOf<Provider, String>() }
    LaunchedEffect(Unit) {
        val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Provider.entries.associateWith { c.secure.get(it) } }
        keys.putAll(loaded)
    }
    var base by remember { mutableStateOf(s.customBaseUrl.ifBlank { Provider.CUSTOM.defaultBase }) }
    var prompt by remember { mutableStateOf(s.systemPrompt) }
    var temp by remember { mutableFloatStateOf(s.temperature) }
    var share by remember { mutableStateOf(s.shareContext) }
    var show by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    val test by vm.test.collectAsStateWithLifecycle()
    val key = keys[provider].orEmpty()

    SectionLabel("Provider")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Provider.entries.forEach { p -> FilterChip(provider == p, { provider = p; show = false }, { Text(p.label) }, leadingIcon = { if (provider == p) Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }) }
    }
    if (provider == Provider.CUSTOM) OutlinedTextField(base, { base = it }, label = { Text("Base URL (OpenAI-compatible)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        supportingText = { Text("Requests go to <base>/chat/completions") })
    else OutlinedTextField(provider.defaultBase, {}, readOnly = true, label = { Text("Endpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth())

    OutlinedTextField(
        key, { keys[provider] = it.trim() }, label = { Text("API key" + if (provider == Provider.CUSTOM) " (optional)" else "") }, singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
        supportingText = { Text("Stored encrypted on this device only.") },
        trailingIcon = {
            Row {
                IconButton({ show = !show }) { Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (show) "Hide key" else "Show key") }
                if (key.isNotEmpty()) IconButton({ keys[provider] = ""; c.secure.set(provider, "") }) { Icon(Icons.Filled.Close, "Clear key") }
            }
        }
    )

    if (provider.models.isNotEmpty()) ExposedDropdownMenuBox(modelMenu, { modelMenu = it }) {
        OutlinedTextField(models[provider].orEmpty(), {}, readOnly = true, label = { Text("Model") }, modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelMenu) })
        ExposedDropdownMenu(modelMenu, { modelMenu = false }) {
            provider.models.forEach { m -> DropdownMenuItem({ Text(m) }, { models[provider] = m; modelMenu = false }) }
        }
    }
    OutlinedTextField(models[provider].orEmpty(), { models[provider] = it.trim() }, label = { Text("Model ID (custom)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

    OutlinedTextField(prompt, { prompt = it }, label = { Text("Coach persona / system prompt") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
        trailingIcon = { IconButton({ prompt = DEFAULT_PROMPT }) { Icon(Icons.Filled.RestartAlt, "Reset prompt") } })
    Text("Temperature: ${"%.1f".format(temp)}")
    Slider(temp, { temp = Math.round(it * 10) / 10f }, valueRange = 0f..2f, steps = 19)
    SwitchRow("Share app context with the AI", share, supporting = "Includes habits, streaks, open tasks and recent journal summaries in prompts.") { share = it }

    fun cfg() = AiConfig(provider, if (provider == Provider.CUSTOM) base.trim() else provider.defaultBase, models[provider].orEmpty().trim(), keys[provider].orEmpty().trim(), temp)
    Button({ vm.testConnection(cfg()) }, enabled = test !is TestState.Running, modifier = Modifier.fillMaxWidth()) {
        if (test is TestState.Running) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)); Text("Testing…") }
        else { Icon(Icons.Filled.NetworkCheck, null); Spacer(Modifier.width(8.dp)); Text("Test connection") }
    }
    when (val t = test) {
        is TestState.Success -> ResultCard(true, "Connected · ${t.model} · ${t.ms} ms")
        is TestState.Failure -> ResultCard(false, t.message)
        else -> {}
    }
    FilledTonalButton({ vm.saveAi(provider, models.toMap(), base, keys.toMap(), prompt, temp, share) }, Modifier.fillMaxWidth()) { Text("Save AI settings") }
}

@Composable
private fun ResultCard(ok: Boolean, text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error, null); Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ThemeSection(s: AppSettings, vm: SettingsViewModel) {
    SectionLabel("Mode")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { m -> FilterChip(s.themeMode == m, { vm.update { it.copy(themeMode = m) } }, { Text(m.label) }) }
    }
    val canDynamic = Build.VERSION.SDK_INT >= 31
    SwitchRow("Material You dynamic color", s.dynamicColor && canDynamic, canDynamic, if (canDynamic) "Uses your wallpaper colors." else "Requires Android 12 or newer.") { v -> vm.update { it.copy(dynamicColor = v) } }
    SectionLabel("Accent" + if (s.dynamicColor && canDynamic) " (turn off dynamic color to use)" else "")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Accent.entries.forEach { a -> FilterChip(s.accent == a, { vm.update { it.copy(accent = a) } }, { Text(a.label) }, enabled = !(s.dynamicColor && canDynamic)) }
    }
    SwitchRow("Animations", s.animations) { v -> vm.update { it.copy(animations = v) } }
    SectionLabel("Density")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!s.compact, { vm.update { it.copy(compact = false) } }, { Text("Spacious") })
        FilterChip(s.compact, { vm.update { it.copy(compact = true) } }, { Text("Compact") })
    }
}

@Composable
private fun NotificationSection(s: AppSettings, vm: SettingsViewModel) {
    val ctx = LocalContext.current
    val c = LocalContainer.current
    var granted by remember { mutableStateOf(Notifier.canPost(ctx)) }
    var exact by remember { mutableStateOf(c.scheduler.canExact()) }
    var timeDialog by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(Notifier.fullScreenAllowed(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = Notifier.canPost(ctx); exact = c.scheduler.canExact(); fullScreen = Notifier.fullScreenAllowed(ctx) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    SwitchRow("Reminders", s.remindersEnabled, supporting = "Master switch for habit and task alarms.") { v -> vm.update(reschedule = true) { it.copy(remindersEnabled = v) } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("Default task reminder time"); Text("Used for tasks with a due date but no time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        OutlinedButton({ timeDialog = true }) { Text(Dates.fmtTime(s.defaultReminderMinutes)) }
    }
    SwitchRow("Ring like an alarm", s.alarmRings,
        supporting = "Plays a looping alarm sound with vibration and a full-screen alarm until you dismiss, snooze or finish. Off: a normal notification.") { v -> vm.update { it.copy(alarmRings = v) } }
    SectionLabel("Snooze length")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(5, 10, 15, 20, 30).forEach { m -> FilterChip(s.snoozeMinutes == m, { vm.update { it.copy(snoozeMinutes = m) } }, { Text("$m min") }) }
    }
    SwitchRow("Sound (notifications)", s.sound) { v -> vm.update { it.copy(sound = v) } }
    SwitchRow("Vibration", s.vibration) { v -> vm.update { it.copy(vibration = v) } }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp)); Text("Notification permission: " + if (granted) "granted" else "not granted", Modifier.weight(1f))
        if (!granted) TextButton({
            if (Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS)
            else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }) { Text("Grant") }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (exact) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, tint = if (exact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp)); Text("Exact alarms: " + if (exact) "allowed" else "not allowed (reminders may be a few minutes late)", Modifier.weight(1f))
        if (!exact && Build.VERSION.SDK_INT >= 31) TextButton({
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
        }) { Text("Allow") }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (fullScreen) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, tint = if (fullScreen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp)); Text("Full-screen alarms: " + if (fullScreen) "allowed" else "not allowed (the alarm only shows as a heads-up)", Modifier.weight(1f))
        if (!fullScreen && Build.VERSION.SDK_INT >= 34) TextButton({
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")))
        }) { Text("Allow") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ vm.sendTestNotification() }) { Icon(Icons.Filled.NotificationsActive, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Test notification") }
        OutlinedButton({ vm.scheduleTestAlarm() }) { Icon(Icons.Filled.Alarm, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Test alarm (10 s)") }
    }
    if (timeDialog) LairTimeDialog(s.defaultReminderMinutes, { m -> vm.update(reschedule = true) { it.copy(defaultReminderMinutes = m) }; timeDialog = false }, { timeDialog = false })
}

@Composable
private fun BackupSection(vm: SettingsViewModel) {
    val ctx = LocalContext.current
    val pending by vm.pendingBackup.collectAsStateWithLifecycle()
    val error by vm.backupError.collectAsStateWithLifecycle()
    var includeKeys by remember { mutableStateOf(true) }
    var askExport by remember { mutableStateOf(false) }
    val stamp = remember { SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.export(ctx.contentResolver, uri, includeKeys) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.readBackup(uri) }

    Text("A backup is a .zip with habits and history, tasks, journal, categories, Pomodoro data, AI chat and change history, settings, and all photo/audio/video attachments.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ askExport = true }) { Icon(Icons.Filled.Upload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Export zip") }
        OutlinedButton({ importer.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Import") }
    }
    if (askExport) AlertDialog(
        onDismissRequest = { askExport = false },
        title = { Text("Export backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.clickable { includeKeys = !includeKeys }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(includeKeys, { includeKeys = it }); Text("Include AI API keys")
                }
                Text(if (includeKeys) "The keys are stored unencrypted inside the zip. Keep the file somewhere private." else "Keys stay on this device only.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton({ askExport = false; exporter.launch("habitlair-backup-$stamp.zip") }) { Text("Choose location") } },
        dismissButton = { TextButton({ askExport = false }) { Text("Cancel") } }
    )
    pending?.let { p ->
        val b = p.backup
        AlertDialog(
            onDismissRequest = { vm.cancelRestore() },
            title = { Text("Replace all data?") },
            text = {
                Text("Backup from ${SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()).format(Date(b.exportedAt))}: ${b.habits.size} habits, ${b.completions.size} check-offs, " +
                    "${b.tasks.size} tasks, ${b.journal.size} journal entries, ${b.categories.size} categories, ${b.sessions.size} Pomodoro sessions, " +
                    "${b.attachments.size} attachments, ${b.chat.size} chat messages and ${b.aiChanges.size} AI changes. " +
                    (if (b.apiKeys.isNotEmpty()) "It also contains ${b.apiKeys.size} AI API key(s) that will be restored. " else "") +
                    "Everything currently in the app will be replaced. This can't be undone.")
            },
            confirmButton = { TextButton({ vm.confirmRestore() }) { Text("Replace") } },
            dismissButton = { TextButton({ vm.cancelRestore() }) { Text("Cancel") } }
        )
    }
    error?.let { AlertDialog({ vm.backupError.value = null }, confirmButton = { TextButton({ vm.backupError.value = null }) { Text("OK") } }, title = { Text("Can't import") }, text = { Text(it) }) }
}

@Composable
private fun WidgetSection() {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf<String?>(null) }
    Text("Three home-screen checklists in different colours. Tap a row to tick it. Each resizes down to 2 icons wide and fills the space you give it.", style = MaterialTheme.typography.bodySmall)
    val mgr = remember { AppWidgetManager.getInstance(ctx) }
    fun pin(cls: Class<*>, name: String) {
        msg = if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(ctx, cls), null, null); "Confirm on the dialog to place the $name widget."
        } else "Your launcher can't pin from apps. Long-press the home screen, choose Widgets and pick HabitLair."
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton({ pin(DynamicWidgetReceiver::class.java, "Material You") }) { Text("Material You") }
        FilledTonalButton({ pin(MidnightWidgetReceiver::class.java, "Midnight") }) { Text("Midnight") }
        FilledTonalButton({ pin(SunriseWidgetReceiver::class.java, "Sunrise") }) { Text("Sunrise") }
    }
    msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
