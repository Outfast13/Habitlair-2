@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.habitlair.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.habitlair.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class Group(val title: String) {
    OVERDUE("Overdue"), TODAY("Today"), TOMORROW("Tomorrow"), UPCOMING("Upcoming"), ANYTIME("No date"), DONE("Completed")
}

private fun groupOf(t: TaskItem, today: Long): Group {
    val d = t.dueEpochDay
    return when {
        t.done -> Group.DONE
        d == null -> Group.ANYTIME
        d < today -> Group.OVERDUE
        d == today -> Group.TODAY
        d == today + 1 -> Group.TOMORROW
        else -> Group.UPCOMING
    }
}

private fun dueLabel(t: TaskItem, today: Long): String? {
    val d = t.dueEpochDay ?: return null
    val day = when (d) { today -> "Today"; today + 1 -> "Tomorrow"; today - 1 -> "Yesterday"; else -> Dates.fmtDate(d) }
    return day + (t.dueMinutes?.let { " · " + Dates.fmtTime(it) } ?: "")
}

private fun sortGroup(g: Group, list: List<TaskUi>, sort: Int): List<TaskUi> = when {
    g == Group.DONE -> list.sortedByDescending { it.task.doneAt ?: 0L }
    sort == 1 -> list.sortedWith(compareBy({ it.task.dueEpochDay ?: Long.MAX_VALUE }, { it.task.dueMinutes ?: Int.MAX_VALUE }, { it.task.sortOrder }))
    sort == 2 -> list.sortedWith(compareBy({ -it.task.priority }, { it.task.dueEpochDay ?: Long.MAX_VALUE }))
    sort == 3 -> list.sortedBy { it.task.title.lowercase() }
    else -> list.sortedWith(compareBy({ it.task.sortOrder }, { it.task.id }))
}

@Composable
fun TasksScreen(nav: NavController) {
    val vm = appViewModel { TasksViewModel(it) }
    val hvm = appViewModel { HabitViewModel(it) }
    val c = LocalContainer.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val habits by hvm.habits.collectAsStateWithLifecycle()
    val today = Dates.today()

    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var prio by rememberSaveable { mutableIntStateOf(-1) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var showDone by rememberSaveable { mutableStateOf(true) }
    var doneOpen by rememberSaveable { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var hidden by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var moveMode by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Open<TaskUi>?>(null) }
    var newTitle by remember { mutableStateOf<String?>(null) }
    var addSubFor by remember { mutableStateOf<TaskItem?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmClearDone by remember { mutableStateOf(false) }
    var quick by rememberSaveable { mutableStateOf("") }
    val selecting = selected.isNotEmpty()

    val allTags = tasks.flatMap { it.task.tags.tagList() }.distinct().sorted()
    val terms = query.trim().lowercase().split(' ').filter { it.isNotBlank() }
    val visible = tasks.filter { it.task.id !in hidden }
        .filter { prio < 0 || it.task.priority == prio }
        .filter { tag == null || tag in it.task.tags.tagList() }
        .filter { t -> terms.all { w -> t.task.title.lowercase().contains(w) || t.task.notes.lowercase().contains(w) || t.task.tags.lowercase().contains(w) || t.subs.any { s -> s.title.lowercase().contains(w) } } }
    val grouped = visible.groupBy { groupOf(it.task, today) }.mapValues { (g, l) -> sortGroup(g, l, sort) }
    val order = Group.entries.filter { !grouped[it].isNullOrEmpty() && (it != Group.DONE || showDone) }
    val allIds = tasks.map { it.task.id }
    val activeFilters = (if (prio >= 0) 1 else 0) + (if (tag != null) 1 else 0) + (if (!showDone) 1 else 0) + (if (sort != 0) 1 else 0)

    LaunchedEffect(allIds) { selected = selected.filter { it in allIds }.toSet(); if (selected.isEmpty()) moveMode = false }

    fun clearSelection() { selected = emptySet(); moveMode = false }
    fun toggleSel(id: Long) { selected = if (id in selected) selected - id else selected + id; if (selected.isEmpty()) moveMode = false }
    val selectedGroups = tasks.filter { it.task.id in selected }.map { groupOf(it.task, today) }.toSet()
    val canMove = sort == 0 && selectedGroups.size == 1
    fun move(up: Boolean) {
        val g = selectedGroups.singleOrNull() ?: return
        val ids = grouped[g].orEmpty().map { it.task.id }
        moveSelection(allIds, ids, selected, up)?.let { vm.reorder(it) }
    }

    fun complete(t: TaskUi) {
        val nowDone = !t.task.done
        vm.setDone(t.task.id, nowDone)
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (nowDone) scope.launch {
            snack.currentSnackbarData?.dismiss()
            val r = snack.showSnackbar("Completed \"${t.task.title}\"", "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.setDone(t.task.id, false)
        }
    }

    fun deleteWithUndo(t: TaskUi) {
        val id = t.task.id
        hidden = hidden + id
        scope.launch {
            snack.currentSnackbarData?.dismiss()
            try {
                val r = snack.showSnackbar("Deleted \"${t.task.title}\"", "Undo", duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) hidden = hidden - id else { vm.delete(id); hidden = hidden - id }
            } catch (e: CancellationException) {
                vm.delete(id)   // leaving the screen confirms the delete
                throw e
            }
        }
    }

    fun quickAdd() {
        val t = quick.trim()
        if (t.isEmpty()) return
        vm.save(TaskItem(title = t), emptyList(), AttachmentChanges())
        quick = ""
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            if (selecting) TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationIcon = { IconButton({ clearSelection() }) { Icon(Icons.Filled.Close, "Exit selection") } },
                title = {
                    if (moveMode) Text("Move ${selected.size}")
                    else Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ tasks.firstOrNull { it.task.id == selected.first() }?.let { sheet = Open(it) } }, enabled = selected.size == 1) {
                            Icon(Icons.Filled.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Edit")
                        }
                        TextButton({ moveMode = true }, enabled = canMove) {
                            Icon(Icons.Filled.SwapVert, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Move")
                        }
                    }
                },
                actions = {
                    if (moveMode) {
                        IconButton({ move(true) }) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
                        IconButton({ move(false) }) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
                        TextButton({ moveMode = false }) { Text("Done") }
                    } else {
                        val allDone = tasks.filter { it.task.id in selected }.all { it.task.done }
                        IconButton({ vm.setDoneMany(selected, !allDone) }) { Icon(if (allDone) Icons.Filled.Replay else Icons.Filled.DoneAll, if (allDone) "Reopen selected" else "Complete selected") }
                        IconButton({ confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete selected") }
                    }
                }
            ) else TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    if (searching) TextField(query, { query = it }, singleLine = true, placeholder = { Text("Search tasks, notes, tags") },
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                        trailingIcon = { DictateButton { query = appendSpoken(query, it) } })
                    else Text("Tasks", style = MaterialTheme.typography.headlineMedium)
                },
                actions = {
                    IconButton({ searching = !searching; if (!searching) query = "" }) { Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Search") }
                    IconButton({ showFilters = true }) {
                        BadgedBox({ if (activeFilters > 0) Badge { Text("$activeFilters") } }) { Icon(Icons.Filled.FilterList, "Filter and sort") }
                    }
                }
            )
        },
        bottomBar = {
            if (!selecting) Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        quick, { quick = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Add a task…") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { quickAdd() }),
                        trailingIcon = { DictateButton { quick = appendSpoken(quick, it) } }
                    )
                    IconButton({ quickAdd() }, enabled = quick.isNotBlank()) { Icon(Icons.Filled.Add, "Add task") }
                    IconButton({ newTitle = quick; quick = "" }) { Icon(Icons.Filled.Tune, "Add with date, time and more") }
                }
            }
        }
    ) { pv ->
        LazyColumn(Modifier.padding(pv).fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (order.isEmpty()) item("empty") {
                EmptyState(Icons.Filled.TaskAlt, if (tasks.isEmpty()) "No tasks yet.\nType one below and press the plus." else "No tasks match your search or filters.")
            }
            order.forEach { g ->
                val list = grouped[g].orEmpty()
                item("header_${g.name}") {
                    Row(Modifier.fillMaxWidth().then(if (g == Group.DONE) Modifier.clickable { doneOpen = !doneOpen } else Modifier).padding(top = 10.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${g.title} · ${list.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                            color = if (g == Group.OVERDUE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        if (g == Group.DONE) {
                            if (doneOpen) TextButton({ confirmClearDone = true }) { Text("Clear") }
                            Icon(if (doneOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (doneOpen) "Collapse completed" else "Expand completed")
                        }
                    }
                }
                if (g != Group.DONE || doneOpen) items(list, key = { it.task.id }) { t ->
                    val id = t.task.id
                    TaskCard(
                        t = t, today = today, selected = id in selected, selecting = selecting, expanded = id in expanded,
                        onToggleExpand = { expanded = if (id in expanded) expanded - id else expanded + id },
                        onClick = { if (selecting) toggleSel(id) else sheet = Open(t) },
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); toggleSel(id) },
                        onToggleDone = { complete(t) },
                        onToggleSub = { s -> vm.setDone(s.id, !s.done) },
                        onAddSub = { addSubFor = t.task },
                        onSwipeDone = { complete(t) },
                        onSwipeDelete = { deleteWithUndo(t) }
                    )
                }
            }
        }
    }

    if (showFilters) LairSheet({ showFilters = false }) {
        Text("Filter and sort", style = MaterialTheme.typography.titleLarge)
        SectionLabel("Sort within each section")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Manual", "Due date", "Priority", "A–Z").forEachIndexed { i, l -> FilterChip(sort == i, { sort = i }, { Text(l) }) }
        }
        SectionLabel("Priority")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(prio < 0, { prio = -1 }, { Text("Any") })
            listOf("Low", "Medium", "High").forEachIndexed { i, l -> FilterChip(prio == i, { prio = i }, { Text(l) }) }
        }
        if (allTags.isNotEmpty()) {
            SectionLabel("Tag")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(tag == null, { tag = null }, { Text("Any") })
                allTags.forEach { t -> FilterChip(tag == t, { tag = if (tag == t) null else t }, { Text("#$t") }) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Show completed section", Modifier.weight(1f)); Switch(showDone, { showDone = it }) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            TextButton({ prio = -1; tag = null; showDone = true; sort = 0 }) { Text("Reset") }
            Button({ showFilters = false }) { Text("Done") }
        }
    }

    sheet?.let { s ->
        TaskEditorSheet(
            s.value?.task, s.value?.subs.orEmpty(), habits.map { it.habit }, s.value?.focusMin ?: 0,
            { t, subs, a -> vm.save(t, subs, a); sheet = null; clearSelection() },
            s.value?.let { u -> { vm.delete(u.task.id); sheet = null; clearSelection() } },
            { t -> c.pomodoro.setLink(null, t.id); sheet = null; nav.goTab("timer") },
            { sheet = null }
        )
    }
    newTitle?.let { prefill ->
        TaskEditorSheet(null, emptyList(), habits.map { it.habit }, 0, { t, subs, a -> vm.save(t, subs, a); newTitle = null }, null, null, { newTitle = null }, prefillTitle = prefill)
    }
    addSubFor?.let { parent ->
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addSubFor = null },
            title = { Text("Add sub-task") },
            text = { OutlinedTextField(text, { text = it }, label = { Text("Sub-task for \"${parent.title}\"") }, singleLine = true, trailingIcon = { DictateButton { text = appendSpoken(text, it) } }) },
            confirmButton = { TextButton({ if (text.isNotBlank()) vm.addSubtask(parent.id, text); addSubFor = null }, enabled = text.isNotBlank()) { Text("Add") } },
            dismissButton = { TextButton({ addSubFor = null }) { Text("Cancel") } }
        )
    }
    if (confirmDelete) ConfirmDialog("Delete ${selected.size} task(s)?", "Their sub-tasks will be removed too.", "Delete",
        { vm.deleteMany(selected); clearSelection(); confirmDelete = false }, { confirmDelete = false })
    if (confirmClearDone) ConfirmDialog("Clear completed tasks?", "All completed tasks are deleted.", "Clear",
        { vm.deleteMany(tasks.filter { it.task.done }.map { it.task.id }); confirmClearDone = false }, { confirmClearDone = false })
}

@Composable
private fun TaskCard(
    t: TaskUi, today: Long, selected: Boolean, selecting: Boolean, expanded: Boolean,
    onToggleExpand: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit, onToggleDone: () -> Unit,
    onToggleSub: (TaskItem) -> Unit, onAddSub: () -> Unit, onSwipeDone: () -> Unit, onSwipeDelete: () -> Unit
) {
    val task = t.task
    val prioColor = when (task.priority) { 2 -> Color(0xFFE0415F); 0 -> Color(0xFF7CB342); else -> Color(0xFFF2A33A) }
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v == SwipeToDismissBoxValue.StartToEnd) onSwipeDone() else if (v == SwipeToDismissBoxValue.EndToStart) onSwipeDelete()
        false
    })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = !selecting,
        enableDismissFromEndToStart = !selecting,
        backgroundContent = {
            val toRight = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val bg = if (toRight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 20.dp), contentAlignment = if (toRight) Alignment.CenterStart else Alignment.CenterEnd) {
                Icon(if (toRight) Icons.Filled.Check else Icons.Filled.Delete, if (toRight) "Complete" else "Delete")
            }
        }
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(4.dp).height(42.dp).clip(RoundedCornerShape(2.dp)).background(prioColor))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(task.title, style = MaterialTheme.typography.titleMedium, textDecoration = if (task.done) TextDecoration.LineThrough else null,
                            color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            dueLabel(task, today)?.let { lbl ->
                                val late = !task.done && (task.dueEpochDay ?: today) < today
                                if (late) Tag(lbl, MaterialTheme.colorScheme.error, Icons.Filled.Event) else NeutralTag(lbl, Icons.Filled.Event)
                            }
                            if (task.priority == 2 && !task.done) Tag("High", prioColor)
                            task.estimateMinutes?.let { NeutralTag("${it}m") }
                            if (t.focusMin > 0) NeutralTag("⏱ ${t.focusMin}m")
                            task.tags.tagList().forEach { NeutralTag("#$it") }
                        }
                    }
                    if (t.subs.isNotEmpty()) TextButton(onToggleExpand, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("${t.subs.count { it.done }}/${t.subs.size}")
                        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (expanded) "Hide sub-tasks" else "Show sub-tasks")
                    }
                    IconButton(onToggleDone, Modifier.size(52.dp), enabled = !selecting) { CheckCircle(task.done, size = 32.dp) }
                }
                AnimatedVisibility(expanded && t.subs.isNotEmpty()) {
                    Column(Modifier.padding(start = 28.dp, end = 8.dp, bottom = 8.dp)) {
                        t.subs.forEach { s ->
                            Row(Modifier.fillMaxWidth().clickable(enabled = !selecting) { onToggleSub(s) }.padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(s.title + (s.estimateMinutes?.let { " · ${it}m" } ?: ""), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                    textDecoration = if (s.done) TextDecoration.LineThrough else null,
                                    color = if (s.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                CheckCircle(s.done, size = 26.dp)
                            }
                        }
                        TextButton(onAddSub, contentPadding = PaddingValues(horizontal = 4.dp)) { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Add sub-task") }
                    }
                }
            }
        }
    }
}
