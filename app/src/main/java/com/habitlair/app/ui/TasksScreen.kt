@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.habitlair.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.habitlair.app.data.*

@Composable
fun TasksScreen(nav: NavController) {
    val vm = appViewModel { TasksViewModel(it) }
    val hvm = appViewModel { HabitViewModel(it) }
    val c = LocalContainer.current
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val habits by hvm.habits.collectAsStateWithLifecycle()
    var status by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var prio by rememberSaveable { mutableIntStateOf(-1) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sheet by remember { mutableStateOf<Open<TaskUi>?>(null) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var addSubFor by remember { mutableStateOf<TaskItem?>(null) }
    val selecting = selected.isNotEmpty()

    val allTags = tasks.flatMap { it.task.tags.tagList() }.distinct().sorted()
    val filtered = tasks
        .filter { when (status) { 0 -> !it.task.done; 1 -> it.task.done; else -> true } }
        .filter { prio < 0 || it.task.priority == prio }
        .filter { tag == null || tag in it.task.tags.tagList() }
        .filter { query.isBlank() || it.task.title.contains(query, true) || it.subs.any { s -> s.title.contains(query, true) } }
    val shown = when (sort) {
        1 -> filtered.sortedWith(compareBy({ it.task.dueEpochDay ?: Long.MAX_VALUE }, { -it.task.priority }))
        2 -> filtered.sortedWith(compareBy({ -it.task.priority }, { it.task.dueEpochDay ?: Long.MAX_VALUE }))
        else -> filtered
    }
    val shownIds = shown.map { it.task.id }
    val allIds = tasks.map { it.task.id }
    LaunchedEffect(allIds) { selected = selected.filter { it in allIds }.toSet() }

    fun move(up: Boolean) { moveSelection(allIds, shownIds, selected, up)?.let { vm.reorder(it) } }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (selecting) TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationIcon = { IconButton({ selected = emptySet() }) { Icon(Icons.Filled.Close, "Exit selection") } },
                title = { Text("${selected.size} selected") },
                actions = {
                    IconButton({ selected = shownIds.toSet() }) { Icon(Icons.Filled.SelectAll, "Select all") }
                    IconButton({ move(true) }, enabled = sort == 0) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
                    IconButton({ move(false) }, enabled = sort == 0) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
                    val allDone = tasks.filter { it.task.id in selected }.all { it.task.done }
                    IconButton({ vm.setDoneMany(selected, !allDone) }) { Icon(if (allDone) Icons.Filled.Undo else Icons.Filled.DoneAll, if (allDone) "Reopen" else "Mark done") }
                    IconButton({ confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete selected") }
                }
            ) else TopAppBar(windowInsets = WindowInsets(0, 0, 0, 0), title = { Text("Tasks", style = MaterialTheme.typography.headlineMedium) })
        },
        floatingActionButton = { if (!selecting) FloatingActionButton({ sheet = Open() }) { Icon(Icons.Filled.Add, "New task") } }
    ) { pv ->
        Column(Modifier.padding(pv)) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search tasks") }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Close, "Clear search") } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Open", "Done", "All").forEachIndexed { i, l -> FilterChip(status == i, { status = i }, { Text(l) }) }
                listOf("Low", "Medium", "High").forEachIndexed { i, l -> FilterChip(prio == i, { prio = if (prio == i) -1 else i }, { Text(l) }) }
                allTags.forEach { t -> FilterChip(tag == t, { tag = if (tag == t) null else t }, { Text("#$t") }) }
            }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sort", style = MaterialTheme.typography.labelLarge)
                listOf("Manual", "Due date", "Priority").forEachIndexed { i, l -> FilterChip(sort == i, { sort = i }, { Text(l) }) }
            }
            if (!selecting && tasks.size > 1) Text("Long-press a task to select, reorder or delete.", Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shown.isEmpty()) item { EmptyState(Icons.Filled.TaskAlt, if (tasks.isEmpty()) "No tasks yet. Tap + to add one." else "No tasks match your filters.") }
                items(shown, key = { it.task.id }) { t ->
                    val isSel = t.task.id in selected
                    val toggleSel = { selected = if (isSel) selected - t.task.id else selected + t.task.id }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = if (isSel) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth().combinedClickable(
                            onClick = { if (selecting) toggleSel() else sheet = Open(t) },
                            onLongClick = { toggleSel() }
                        )
                    ) {
                        Column(Modifier.padding(bottom = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selecting) Checkbox(isSel, { toggleSel() }, Modifier.padding(start = 8.dp))
                                Box(Modifier.weight(1f)) {
                                    TaskRow(t, 0, onToggle = { if (selecting) toggleSel() else vm.setDone(t.task.id, !t.task.done) }, onClick = null)
                                }
                            }
                            Column(Modifier.padding(start = 72.dp, end = 16.dp)) {
                                val meta = buildList {
                                    t.task.dueEpochDay?.let { add(Dates.fmtDate(it)) }
                                    t.task.estimateMinutes?.let { add("$it min") }
                                    if (t.focusMin > 0) add("⏱ ${t.focusMin}m focused")
                                    t.task.tags.tagList().forEach { add("#$it") }
                                }.joinToString("  ·  ")
                                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (t.subs.isNotEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(progress = { t.subs.count { it.done }.toFloat() / t.subs.size }, Modifier.fillMaxWidth())
                                }
                                t.subs.forEach { s ->
                                    Row(Modifier.fillMaxWidth().clickable(enabled = !selecting) { vm.setDone(s.id, !s.done) }, verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(s.done, { vm.setDone(s.id, it) }, enabled = !selecting)
                                        Text(s.title + (s.estimateMinutes?.let { " · ${it}m" } ?: ""), style = MaterialTheme.typography.bodyMedium,
                                            textDecoration = if (s.done) TextDecoration.LineThrough else null,
                                            color = if (s.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                                if (!selecting) TextButton({ addSubFor = t.task }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                    Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Add sub-task")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    sheet?.let { s ->
        TaskEditorSheet(
            s.value?.task, s.value?.subs.orEmpty(), habits.map { it.habit }, s.value?.focusMin ?: 0,
            { t, subs, a -> vm.save(t, subs, a); sheet = null },
            s.value?.let { u -> { vm.delete(u.task.id); sheet = null } },
            { t -> c.pomodoro.setLink(null, t.id); sheet = null; nav.goTab("timer") },
            { sheet = null }
        )
    }
    addSubFor?.let { parent ->
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addSubFor = null },
            title = { Text("Add sub-task") },
            text = { OutlinedTextField(text, { text = it }, label = { Text("Sub-task for \"${parent.title}\"") }, singleLine = true) },
            confirmButton = { TextButton({ if (text.isNotBlank()) vm.addSubtask(parent.id, text); addSubFor = null }, enabled = text.isNotBlank()) { Text("Add") } },
            dismissButton = { TextButton({ addSubFor = null }) { Text("Cancel") } }
        )
    }
    if (confirmDelete) ConfirmDialog("Delete ${selected.size} task(s)?", "Their sub-tasks will be removed too.", "Delete",
        { vm.deleteMany(selected); selected = emptySet(); confirmDelete = false }, { confirmDelete = false })
}
