@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.habitlair.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.habitlair.app.data.*
import java.time.LocalDate

/** The whole row is the tap target: tap = check/uncheck, long-press = select. The circle on the right is just the indicator. */
@Composable
fun HabitRow(ui: HabitUi, day: Long, today: Long, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val h = ui.habit
    val done = day in ui.days
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = rowPad()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(h.icon, Color(h.colorHex))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(h.name, style = MaterialTheme.typography.titleMedium, textDecoration = if (done) TextDecoration.LineThrough else null)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag("Habit", Color(h.colorHex))
                h.timeMinutes?.let { NeutralTag(Dates.fmtTime(it), Icons.Filled.NotificationsNone) }
                if (ui.current > 1) NeutralTag("🔥 ${ui.current}")
            }
        }
        CheckCircle(done, enabled = day <= today)
    }
}

@Composable
fun TaskRow(t: TaskUi, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val task = t.task
    val prioColor = when (task.priority) { 2 -> Color(0xFFE0415F); 0 -> Color(0xFF7CB342); else -> Color(0xFFF2A33A) }
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = rowPad()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(prioColor.copy(alpha = .2f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Flag, null, tint = prioColor)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(task.title, style = MaterialTheme.typography.titleMedium, textDecoration = if (task.done) TextDecoration.LineThrough else null)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag("Task", prioColor)
                val overdue = !task.done && task.dueEpochDay != null && task.dueEpochDay < Dates.today()
                if (overdue) Tag("Overdue", MaterialTheme.colorScheme.error)
                task.dueMinutes?.let { NeutralTag(Dates.fmtTime(it), Icons.Filled.NotificationsNone) }
                if (t.subs.isNotEmpty()) NeutralTag("${t.subs.count { it.done }}/${t.subs.size}")
            }
        }
        CheckCircle(task.done)
    }
}

@Composable
fun TodayScreen(nav: NavController) {
    val hvm = appViewModel { HabitViewModel(it) }
    val tvm = appViewModel { TasksViewModel(it) }
    val habits by hvm.habits.collectAsStateWithLifecycle()
    val tasks by tvm.tasks.collectAsStateWithLifecycle()
    val cats by hvm.categories.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val today = Dates.today()
    var selectedDay by rememberSaveable { mutableLongStateOf(today) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var filterMenu by remember { mutableStateOf(false) }
    var fabMenu by remember { mutableStateOf(false) }
    var jump by remember { mutableStateOf(false) }
    var habitSheet by remember { mutableStateOf<Open<Habit>?>(null) }
    var taskSheet by remember { mutableStateOf<Open<TaskUi>?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var moveMode by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()

    val dayHabits = habits.filter { it.habit.isScheduled(selectedDay) }
        .filter { query.isBlank() || it.habit.name.contains(query, true) }
        .filter { when (filter) { 1 -> selectedDay !in it.days; 2 -> selectedDay in it.days; else -> true } }
    val dayTasks = tasks.filter { t ->
        val d = t.task.dueEpochDay
        (d == selectedDay || (selectedDay == today && d != null && d < today && !t.task.done)) &&
            (query.isBlank() || t.task.title.contains(query, true)) &&
            when (filter) { 1 -> !t.task.done; 2 -> t.task.done; else -> true }
    }
    val allToday = habits.filter { it.habit.isScheduled(selectedDay) }
    val doneCount = allToday.count { selectedDay in it.days } + dayTasks.count { it.task.done }
    val totalCount = allToday.size + dayTasks.size

    val selHabits = selected.filter { it.startsWith("h") }.map { it.drop(1).toLong() }.toSet()
    val selTasks = selected.filter { it.startsWith("t") }.map { it.drop(1).toLong() }.toSet()
    val canMove = selHabits.isEmpty() != selTasks.isEmpty()

    fun clearSelection() { selected = emptySet(); moveMode = false }
    fun toggleSel(key: String) {
        selected = if (key in selected) selected - key else selected + key
        if (selected.isEmpty()) moveMode = false
    }
    fun longPress(key: String) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); toggleSel(key) }
    fun move(up: Boolean) {
        if (selTasks.isEmpty()) moveSelection(habits.map { it.habit.id }, dayHabits.map { it.habit.id }, selHabits, up)?.let { hvm.reorder(it) }
        else if (selHabits.isEmpty()) moveSelection(tasks.map { it.task.id }, dayTasks.map { it.task.id }, selTasks, up)?.let { tvm.reorder(it) }
    }
    fun edit() {
        val k = selected.singleOrNull() ?: return
        val id = k.drop(1).toLong()
        if (k.startsWith("h")) habits.firstOrNull { it.habit.id == id }?.let { habitSheet = Open(it.habit) }
        else tasks.firstOrNull { it.task.id == id }?.let { taskSheet = Open(it) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (selecting) TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationIcon = { IconButton({ clearSelection() }) { Icon(Icons.Filled.Close, "Exit selection") } },
                title = {
                    if (moveMode) Text("Move ${selected.size}")
                    else Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ edit() }, enabled = selected.size == 1) { Icon(Icons.Filled.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Edit") }
                        TextButton({ moveMode = true }, enabled = canMove) { Icon(Icons.Filled.SwapVert, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Move") }
                    }
                },
                actions = {
                    if (moveMode) {
                        IconButton({ move(true) }) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
                        IconButton({ move(false) }) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
                        TextButton({ moveMode = false }) { Text("Done") }
                    } else {
                        if (selected.size == 1 && selected.first().startsWith("h"))
                            IconButton({ nav.navigate("habit/${selected.first().drop(1)}") }) { Icon(Icons.Filled.Insights, "Habit details and history") }
                        IconButton({ confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete selected") }
                    }
                }
            ) else TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    if (searching) TextField(query, { query = it }, singleLine = true, placeholder = { Text("Search today") },
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                    else Text("Today", style = MaterialTheme.typography.headlineMedium)
                },
                actions = {
                    IconButton({ searching = !searching; if (!searching) query = "" }) { Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Search") }
                    Box {
                        IconButton({ filterMenu = true }) { Icon(Icons.Filled.FilterList, "Filter") }
                        DropdownMenu(filterMenu, { filterMenu = false }) {
                            listOf("All", "Pending", "Done").forEachIndexed { i, l ->
                                DropdownMenuItem({ Text(l) }, { filter = i; filterMenu = false }, trailingIcon = { if (filter == i) Icon(Icons.Filled.Check, null) })
                            }
                        }
                    }
                    IconButton({ jump = true }) { Icon(Icons.Filled.CalendarMonth, "Pick date") }
                    IconButton({ nav.navigate("settings") }) { Icon(Icons.Filled.Settings, "Settings") }
                }
            )
        },
        floatingActionButton = {
            if (!selecting) Box {
                FloatingActionButton(onClick = { fabMenu = true }, containerColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Filled.Add, "Add") }
                DropdownMenu(fabMenu, { fabMenu = false }) {
                    DropdownMenuItem({ Text("New habit") }, { fabMenu = false; habitSheet = Open() }, leadingIcon = { Icon(Icons.Filled.EmojiEvents, null) })
                    DropdownMenuItem({ Text("New task") }, { fabMenu = false; taskSheet = Open() }, leadingIcon = { Icon(Icons.Filled.TaskAlt, null) })
                }
            }
        }
    ) { pv ->
        Column(Modifier.padding(pv)) {
            DateStrip(selectedDay, today) { selectedDay = it }
            if (totalCount > 0) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("$doneCount of $totalCount done · ${Dates.fmtDate(selectedDay)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    val target = doneCount.toFloat() / totalCount
                    val p = if (LocalAnim.current) animateFloatAsState(target, label = "progress").value else target
                    LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth())
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                if (dayHabits.isEmpty() && dayTasks.isEmpty()) item {
                    EmptyState(Icons.Filled.EventAvailable, if (query.isNotBlank() || filter != 0) "Nothing matches your search or filter." else "Nothing scheduled for this day.\nTap + to add a habit or task.")
                }
                items(dayHabits, key = { "h${it.habit.id}" }) { ui ->
                    val key = "h${ui.habit.id}"
                    HabitRow(ui, selectedDay, today, key in selected,
                        onClick = { if (selecting) toggleSel(key) else if (selectedDay <= today) hvm.toggle(ui.habit.id, selectedDay) },
                        onLongClick = { longPress(key) })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
                items(dayTasks, key = { "t${it.task.id}" }) { t ->
                    val key = "t${t.task.id}"
                    TaskRow(t, key in selected,
                        onClick = { if (selecting) toggleSel(key) else tvm.setDone(t.task.id, !t.task.done) },
                        onLongClick = { longPress(key) })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }

    if (jump) {
        val st = rememberDatePickerState(initialSelectedDateMillis = selectedDay * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { jump = false },
            confirmButton = { TextButton({ st.selectedDateMillis?.let { selectedDay = Math.floorDiv(it, 86_400_000L) }; jump = false }) { Text("Go") } },
            dismissButton = { TextButton({ selectedDay = today; jump = false }) { Text("Today") } }
        ) { DatePicker(st) }
    }
    habitSheet?.let { s ->
        HabitEditorSheet(s.value, cats, { h, a -> hvm.save(h, a); habitSheet = null; clearSelection() },
            s.value?.let { h -> { hvm.delete(h.id); habitSheet = null; clearSelection() } }, { habitSheet = null })
    }
    taskSheet?.let { s ->
        TaskEditorSheet(s.value?.task, s.value?.subs.orEmpty(), habits.map { it.habit }, s.value?.focusMin ?: 0,
            { t, subs, a -> tvm.save(t, subs, a); taskSheet = null; clearSelection() },
            s.value?.let { u -> { tvm.delete(u.task.id); taskSheet = null; clearSelection() } },
            null, { taskSheet = null })
    }
    if (confirmDelete) ConfirmDialog("Delete ${selected.size} item(s)?", "Habits lose their history and tasks lose their sub-tasks.", "Delete",
        { hvm.deleteMany(selHabits); tvm.deleteMany(selTasks); confirmDelete = false; clearSelection() }, { confirmDelete = false })
}

@Composable
fun DateStrip(selected: Long, today: Long, onSelect: (Long) -> Unit) {
    val days = remember(today) { (today - 30..today + 60).toList() }
    val info = remember(today) { days.map { d -> LocalDate.ofEpochDay(d).let { Dates.dayShort[it.dayOfWeek.value - 1] to it.dayOfMonth } } }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (days.indexOf(selected) - 2).coerceAtLeast(0))
    LaunchedEffect(selected) { val i = days.indexOf(selected); if (i >= 0) state.animateScrollToItem((i - 2).coerceAtLeast(0)) }
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(days.size, key = { days[it] }) { idx ->
            val d = days[idx]
            val sel = d == selected
            val bg = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
            val fg = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            Column(
                Modifier.width(56.dp).clip(RoundedCornerShape(18.dp)).background(bg).clickable { onSelect(d) }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(info[idx].first, style = MaterialTheme.typography.labelLarge, color = fg.copy(alpha = .8f))
                Text("${info[idx].second}", style = MaterialTheme.typography.headlineSmall, color = fg)
                Box(Modifier.padding(top = 2.dp).size(width = 14.dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(if (d == today) fg else Color.Transparent))
            }
        }
    }
}
