@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.habitlair.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.habitlair.app.AppContainer
import com.habitlair.app.data.Dates

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer missing") }

data class Open<T>(val value: T? = null)

@Composable
inline fun <reified T : ViewModel> appViewModel(crossinline make: (AppContainer) -> T): T {
    val c = LocalContainer.current
    return viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <V : ViewModel> create(modelClass: Class<V>): V = make(c) as V
    })
}

fun NavController.goTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

val Palette: List<Long> = listOf(0xFF7CB342, 0xFF9C6FE4, 0xFFD63DAA, 0xFF2FA37F, 0xFF3F8CF2, 0xFFF2A33A, 0xFFE0415F, 0xFF26A6C9)

val IconMap: Map<String, ImageVector> by lazy {
    mapOf(
        "check" to Icons.Filled.Check, "health" to Icons.Filled.LocalHospital, "study" to Icons.Filled.School,
        "rest" to Icons.Filled.SelfImprovement, "chat" to Icons.Filled.Forum, "fitness" to Icons.Filled.FitnessCenter,
        "book" to Icons.Filled.AutoStories, "water" to Icons.Filled.WaterDrop, "sleep" to Icons.Filled.Bedtime,
        "code" to Icons.Filled.Code, "run" to Icons.Filled.Bolt, "music" to Icons.Filled.MusicNote,
        "heart" to Icons.Filled.Favorite, "food" to Icons.Filled.Restaurant, "work" to Icons.Filled.Work, "star" to Icons.Filled.Star
    )
}

fun iconFor(key: String): ImageVector = IconMap[key] ?: Icons.Filled.Check

fun moodEmoji(m: Int) = when (m) { 1 -> "😞"; 2 -> "😕"; 3 -> "😐"; 4 -> "🙂"; else -> "😄" }

@Composable
fun rowPad(): Dp = if (LocalCompact.current) 8.dp else 14.dp

@Composable
fun IconBadge(key: String, color: Color, size: Dp = 44.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(color), contentAlignment = Alignment.Center) {
        Icon(iconFor(key), null, tint = Color.Black.copy(alpha = 0.78f), modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
fun Tag(text: String, color: Color, icon: ImageVector? = null) {
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.16f)) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(3.dp)) }
            Text(text, color = color, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun NeutralTag(text: String, icon: ImageVector? = null) =
    Tag(text, MaterialTheme.colorScheme.onSurfaceVariant, icon)

/** Purely visual: the whole row is the tap target, so this circle itself isn't clickable. */
@Composable
fun CheckCircle(done: Boolean, enabled: Boolean = true, size: Dp = 38.dp) {
    val bg = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        when {
            done -> Icon(Icons.Filled.Check, "Completed", tint = MaterialTheme.colorScheme.onPrimary)
            !enabled -> Icon(Icons.Filled.Schedule, "Upcoming", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(48.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun LairSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content
        )
    }
}

private var timeDialogTyping = true   // remembers the last mode used in this session

/** Time picker where the digits can be typed directly (default) or set on the clock dial. */
@Composable
fun LairTimeDialog(initial: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val st = rememberTimePickerState(initial / 60, initial % 60, false)
    var typing by remember { mutableStateOf(timeDialogTyping) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (typing) "Type the time" else "Pick the time") },
        confirmButton = { TextButton({ onConfirm(st.hour * 60 + st.minute) }) { Text("OK") } },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ typing = !typing; timeDialogTyping = typing }) {
                    Icon(if (typing) Icons.Filled.Schedule else Icons.Filled.Keyboard, if (typing) "Switch to clock" else "Switch to typing")
                }
                TextButton(onDismiss) { Text("Cancel") }
            }
        },
        text = { if (typing) TimeInput(st) else TimePicker(st) }
    )
}

@Composable
fun TimeField(label: String, minutes: Int?, onChange: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { open = true }) {
            Icon(Icons.Filled.Schedule, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(minutes?.let { Dates.fmtTime(it) } ?: label)
        }
        if (minutes != null) IconButton({ onChange(null) }) { Icon(Icons.Filled.Close, "Clear time") }
    }
    if (open) LairTimeDialog(minutes ?: 9 * 60, { onChange(it); open = false }, { open = false })
}

@Composable
fun DateField(label: String, epochDay: Long?, onChange: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { open = true }) {
            Icon(Icons.Filled.Event, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(epochDay?.let { Dates.fmtDate(it) } ?: label)
        }
        if (epochDay != null) IconButton({ onChange(null) }) { Icon(Icons.Filled.Close, "Clear date") }
    }
    if (open) {
        val st = rememberDatePickerState(initialSelectedDateMillis = (epochDay ?: Dates.today()) * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton({ st.selectedDateMillis?.let { onChange(Math.floorDiv(it, 86_400_000L)) }; open = false }) { Text("OK") } },
            dismissButton = { TextButton({ open = false }) { Text("Cancel") } }
        ) { DatePicker(st) }
    }
}

@Composable
fun DaysPicker(mask: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Dates.dayShort.forEachIndexed { i, d ->
                FilterChip(selected = (mask and (1 shl i)) != 0, onClick = { onChange(mask xor (1 shl i)) }, label = { Text(d.take(2)) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { onChange(127) }, label = { Text("Daily") })
            AssistChip(onClick = { onChange(31) }, label = { Text("Weekdays") })
            AssistChip(onClick = { onChange(96) }, label = { Text("Weekends") })
        }
    }
}

@Composable
fun ColorRow(selected: Long, onSelect: (Long) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Palette.forEach { c ->
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Color(c)).clickable { onSelect(c) },
                contentAlignment = Alignment.Center
            ) { if (c == selected) Icon(Icons.Filled.Check, "Selected", tint = Color.Black.copy(alpha = .8f)) }
        }
    }
}

@Composable
fun SectionLabel(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

val outlineBorder @Composable get() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

/**
 * Moves every selected id one step up/down inside the *visible* list, then writes the result back into the
 * slots those visible ids occupy in the full ordering. Returns the new full order, or null if nothing moved.
 */
fun moveSelection(all: List<Long>, visible: List<Long>, selected: Set<Long>, up: Boolean): List<Long>? {
    val vis = visible.toMutableList()
    if (up) {
        for (i in 1 until vis.size) if (vis[i] in selected && vis[i - 1] !in selected) { val t = vis[i]; vis[i] = vis[i - 1]; vis[i - 1] = t }
    } else {
        for (i in vis.size - 2 downTo 0) if (vis[i] in selected && vis[i + 1] !in selected) { val t = vis[i]; vis[i] = vis[i + 1]; vis[i + 1] = t }
    }
    if (vis == visible) return null
    val visSet = visible.toSet()
    val slots = all.indices.filter { all[it] in visSet }
    val out = all.toMutableList()
    slots.forEachIndexed { k, slot -> out[slot] = vis[k] }
    return out
}
