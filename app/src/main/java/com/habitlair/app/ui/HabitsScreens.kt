@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.habitlair.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.habitlair.app.data.*
import java.time.LocalDate

@Composable
fun HabitDetailScreen(id: Long, nav: NavController) {
    val vm = appViewModel { HabitViewModel(it) }
    val habits by vm.habits.collectAsStateWithLifecycle()
    val cats by vm.categories.collectAsStateWithLifecycle()
    val c = LocalContainer.current
    val ui = habits.firstOrNull { it.habit.id == id }
    var edit by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    LaunchedEffect(ui?.habit) { attachments = vm.attachments(id) }
    val today = Dates.today()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(ui?.habit?.name ?: "Habit") },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { if (ui != null) IconButton({ edit = true }) { Icon(Icons.Filled.Edit, "Edit habit") } }
            )
        }
    ) { pv ->
        if (ui == null) { Box(Modifier.padding(pv).fillMaxSize(), contentAlignment = Alignment.Center) { Text("This habit no longer exists.") }; return@Scaffold }
        val h = ui.habit
        LazyColumn(Modifier.padding(pv), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(h.icon, Color(h.colorHex), 56.dp); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(h.name, style = MaterialTheme.typography.titleLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Tag(Dates.daysLabel(h.daysMask), Color(h.colorHex))
                            h.timeMinutes?.let { NeutralTag(Dates.fmtTime(it), Icons.Filled.NotificationsNone) }
                            ui.category?.let { Tag(it.name, Color(it.colorHex)) }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("Streak", "${ui.current}", Modifier.weight(1f)); StatCard("Best", "${ui.best}", Modifier.weight(1f))
                    StatCard("Total", "${ui.total}", Modifier.weight(1f)); StatCard("30 days", "${ui.rate30}%", Modifier.weight(1f))
                }
            }
            item {
                Button({ vm.toggle(h.id, today) }, Modifier.fillMaxWidth()) {
                    Icon(if (today in ui.days) Icons.Filled.Undo else Icons.Filled.Check, null); Spacer(Modifier.width(8.dp))
                    Text(if (today in ui.days) "Undo today's check-off" else "Complete for today")
                }
            }
            item { OutlinedButton({ c.pomodoro.setLink(h.id, null); nav.goTab("timer") }, Modifier.fillMaxWidth()) { Icon(Icons.Filled.Timer, null); Spacer(Modifier.width(8.dp)); Text("Start a focus session") } }
            item {
                SectionLabel("History (tap a day to edit)")
                Spacer(Modifier.height(8.dp))
                Heatmap(ui, today) { d -> vm.toggle(h.id, d) }
            }
            if (h.notes.isNotBlank()) item { SectionLabel("Notes"); Text(h.notes) }
            if (attachments.isNotEmpty()) item { SectionLabel("Attachments"); Spacer(Modifier.height(8.dp)); AttachmentGrid(attachments) }
        }
    }
    if (edit && ui != null) HabitEditorSheet(ui.habit, cats, { h, a -> vm.save(h, a); edit = false }, { vm.delete(id); edit = false; nav.popBackStack() }, { edit = false })
}

@Composable
private fun Heatmap(ui: HabitUi, today: Long, onToggle: (Long) -> Unit) {
    val weeks = 10
    val monday = today - (LocalDate.ofEpochDay(today).dayOfWeek.value - 1)
    val first = monday - 7L * (weeks - 1)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Dates.dayShort.forEach { Text(it.take(1), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        for (w in 0 until weeks) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (d in 0 until 7) {
                    val day = first + w * 7 + d
                    val done = day in ui.days
                    val color = when {
                        day > today -> Color.Transparent
                        done -> MaterialTheme.colorScheme.primary
                        ui.habit.isScheduled(day) -> MaterialTheme.colorScheme.surfaceContainerHighest
                        else -> MaterialTheme.colorScheme.surfaceContainerLow
                    }
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(color).clickable(enabled = day <= today) { onToggle(day) },
                        contentAlignment = Alignment.Center) {
                        if (day <= today) Text("${LocalDate.ofEpochDay(day).dayOfMonth}", style = MaterialTheme.typography.labelSmall,
                            color = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f))
                    }
                }
            }
        }
    }
}

@Composable
fun CategoriesScreen(nav: NavController) {
    val vm = appViewModel { HabitViewModel(it) }
    val cats by vm.categories.collectAsStateWithLifecycle()
    val habits by vm.habits.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<Open<Category>?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(windowInsets = WindowInsets(0, 0, 0, 0), navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }, title = { Text("Categories") }) },
        floatingActionButton = { FloatingActionButton({ dialog = Open() }) { Icon(Icons.Filled.Add, "New category") } }
    ) { pv ->
        LazyColumn(Modifier.padding(pv), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (cats.isEmpty()) item { EmptyState(Icons.Filled.Category, "No categories yet. Group habits like Health, Study or Work.") }
            items(cats, key = { it.id }) { c ->
                val n = habits.count { it.habit.categoryId == c.id }
                ListItem(
                    headlineContent = { Text(c.name) }, supportingContent = { Text("$n habit${if (n == 1) "" else "s"}") },
                    leadingContent = { Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Color(c.colorHex))) },
                    trailingContent = {
                        Row {
                            IconButton({ dialog = Open(c) }) { Icon(Icons.Filled.Edit, "Edit category") }
                            IconButton({ deleting = c }) { Icon(Icons.Filled.Delete, "Delete category") }
                        }
                    }
                )
            }
        }
    }
    dialog?.let { d ->
        var name by remember { mutableStateOf(d.value?.name ?: "") }
        var color by remember { mutableLongStateOf(d.value?.colorHex ?: Palette[0]) }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (d.value == null) "New category" else "Edit category") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it; err = null }, label = { Text("Name") }, singleLine = true, isError = err != null, supportingText = err?.let { { Text(it) } })
                    ColorRow(color) { color = it }
                }
            },
            confirmButton = {
                TextButton({
                    val n = name.trim()
                    when {
                        n.isEmpty() -> err = "Enter a name"
                        cats.any { it.name.equals(n, true) && it.id != d.value?.id } -> err = "That category already exists"
                        else -> { vm.saveCategory((d.value ?: Category(name = n)).copy(name = n, colorHex = color)); dialog = null }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } }
        )
    }
    deleting?.let { c -> ConfirmDialog("Delete category?", "Habits in \"${c.name}\" will become uncategorised.", "Delete", { vm.deleteCategory(c.id); deleting = null }, { deleting = null }) }
}
