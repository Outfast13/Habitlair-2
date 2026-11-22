@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.habitlair.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.habitlair.app.data.*
import com.habitlair.app.timer.Phase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TimerScreen() {
    val vm = appViewModel { TimerViewModel(it) }
    val st by vm.timer.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val habits by vm.habits.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    var linkMenu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()) }

    val linkLabel = when {
        st.habitId != null -> "Habit: " + (habits.firstOrNull { it.id == st.habitId }?.name ?: "?")
        st.taskId != null -> "Task: " + (tasks.firstOrNull { it.id == st.taskId }?.title ?: "?")
        else -> "Not linked"
    }
    val todayFocus = sessions.filter { it.kind == "FOCUS" && Dates.fromMillis(it.startedAt) == Dates.today() }.sumOf { it.minutes }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(windowInsets = WindowInsets(0, 0, 0, 0), title = { Text("Timer", style = MaterialTheme.typography.headlineMedium) }) }
    ) { pv ->
        LazyColumn(Modifier.padding(pv).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val prog = if (st.totalMs == 0L) 0f else st.remainingMs.toFloat() / st.totalMs
                    val track = MaterialTheme.colorScheme.surfaceContainerHighest
                    val col = if (st.phase == Phase.FOCUS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                    Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            val sw = 16.dp.toPx()
                            val sz = Size(size.width - sw, size.height - sw)
                            drawArc(track, 0f, 360f, false, Offset(sw / 2, sw / 2), sz, style = Stroke(sw, cap = StrokeCap.Round))
                            drawArc(col, -90f, 360f * prog, false, Offset(sw / 2, sw / 2), sz, style = Stroke(sw, cap = StrokeCap.Round))
                        }
                        val secs = ((st.remainingMs + 999) / 1000).toInt()
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("%02d:%02d".format(secs / 60, secs % 60), style = MaterialTheme.typography.displayMedium)
                            Text(st.phase.label + " · ${st.focusDone} done", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(vm::reset, Modifier.size(52.dp)) { Icon(Icons.Filled.Replay, "Reset") }
                        FilledIconButton({ if (st.running) vm.pause() else vm.start() }, Modifier.size(72.dp)) {
                            Icon(if (st.running) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (st.running) "Pause" else "Start", Modifier.size(36.dp))
                        }
                        FilledTonalIconButton(vm::skip, Modifier.size(52.dp)) { Icon(Icons.Filled.SkipNext, "Skip") }
                    }
                    Text("Focused today: $todayFocus min", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box {
                        OutlinedButton({ linkMenu = true }) { Icon(Icons.Filled.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(linkLabel) }
                        DropdownMenu(linkMenu, { linkMenu = false }) {
                            DropdownMenuItem({ Text("Not linked") }, { vm.link(null, null); linkMenu = false })
                            habits.forEach { h -> DropdownMenuItem({ Text("Habit: ${h.name}") }, { vm.link(h.id, null); linkMenu = false }) }
                            tasks.forEach { t -> DropdownMenuItem({ Text("Task: ${t.title}") }, { vm.link(null, t.id); linkMenu = false }) }
                        }
                    }
                }
            }
            item {
                SectionLabel("Durations" + if (st.running) " (pause to change)" else "")
                Stepper("Focus", s.focusMin, 5..120, 5, !st.running) { v -> vm.updateSettings { it.copy(focusMin = v) } }
                Stepper("Short break", s.breakMin, 1..30, 1, !st.running) { v -> vm.updateSettings { it.copy(breakMin = v) } }
                Stepper("Long break", s.longBreakMin, 5..60, 5, !st.running) { v -> vm.updateSettings { it.copy(longBreakMin = v) } }
                Stepper("Focus rounds before long break", s.cyclesBeforeLong, 2..8, 1, !st.running) { v -> vm.updateSettings { it.copy(cyclesBeforeLong = v) } }
            }
            if (presets.isNotEmpty()) {
                item { SectionLabel("Presets (from AI)") }
                items(presets, key = { "p${it.id}" }) { p ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.label); Text("${p.focusMin}/${p.breakMin} min × ${p.cycles}", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton({ vm.applyPreset(p) }, enabled = !st.running) { Text("Apply") }
                            IconButton({ vm.deletePreset(p.id) }) { Icon(Icons.Filled.Delete, "Delete preset") }
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Session log"); Spacer(Modifier.weight(1f))
                    if (sessions.isNotEmpty()) TextButton({ confirmClear = true }) { Text("Clear") }
                }
                if (sessions.isEmpty()) Text("No sessions yet. Finish a focus round and it shows up here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(sessions.take(50), key = { "s${it.id}" }) { x ->
                val link = x.habitId?.let { id -> habits.firstOrNull { it.id == id }?.name } ?: x.taskId?.let { id -> tasks.firstOrNull { it.id == id }?.title }
                ListItem(
                    headlineContent = { Text("${if (x.kind == "FOCUS") "Focus" else "Break"} · ${x.minutes} min" + if (!x.completed) " (skipped early)" else "") },
                    supportingContent = { Text(fmt.format(Date(x.startedAt)) + (link?.let { " · $it" } ?: "")) },
                    leadingContent = { Icon(if (x.kind == "FOCUS") Icons.Filled.Timer else Icons.Filled.FreeBreakfast, null) }
                )
            }
        }
    }
    if (confirmClear) ConfirmDialog("Clear session log?", "All logged Pomodoro sessions will be removed.", "Clear", { vm.clearLog(); confirmClear = false }, { confirmClear = false })
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, step: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        IconButton({ onChange((value - step).coerceAtLeast(range.first)) }, enabled = enabled && value > range.first) { Icon(Icons.Filled.Remove, "Decrease $label") }
        Text("$value", style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(min = 32.dp))
        IconButton({ onChange((value + step).coerceAtMost(range.last)) }, enabled = enabled && value < range.last) { Icon(Icons.Filled.Add, "Increase $label") }
    }
}
