@file:OptIn(ExperimentalMaterial3Api::class)

package com.habitlair.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.habitlair.app.data.*
import java.time.LocalDate

@Composable
fun HabitRow(ui: HabitUi, day: Long, today: Long, onToggle: () -> Unit, onClick: () -> Unit) {
    val h = ui.habit
    val done = day in ui.days
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = rowPad()), verticalAlignment = Alignment.CenterVertically) {
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
        CheckCircle(done, enabled = day <= today, onClick = onToggle)
    }
}

@Composable
fun TaskRow(t: TaskUi, day: Long?, onToggle: () -> Unit, onClick: (() -> Unit)?) {
    val task = t.task
    val prioColor = when (task.priority) { 2 -> Color(0xFFE0415F); 0 -> Color(0xFF7CB342); else -> Color(0xFFF2A33A) }
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = rowPad()), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(prioColor.copy(alpha = .2f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Flag, null, tint = prioColor)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(task.title, style = MaterialTheme.typography.titleMedium, textDecoration = if (task.done) TextDecoration.LineThrough else null)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag("Task", prioColor)
                val overdue = !task.done && task.dueEpochDay != null && day != null && task.dueEpochDay < Dates.today()
                if (overdue) Tag("Overdue", MaterialTheme.colorScheme.error)
                task.dueMinutes?.let { NeutralTag(Dates.fmtTime(it), Icons.Filled.NotificationsNone) }
                if (t.subs.isNotEmpty()) NeutralTag("${t.subs.count { it.done }}/${t.subs.size}")
            }
        }
        CheckCircle(task.done, enabled = true, onClick = onToggle)
    }
}

@Composable
fun TodayScreen(nav: NavController) {
    val hvm = appViewModel { HabitViewModel(it) }
    val tvm = appViewModel { TasksViewModel(it) }
    val habits by hvm.habits.collectAsStateWithLifecycle()
    val tasks by tvm.tasks.collectAsStateWithLifecycle()
    val cats by hvm.categories.collectAsStateWithLifecycle()
    val today = Dates.today()
    var selected by rememberSaveable { mutableLongStateOf(today) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var filterMenu by remember { mutableStateOf(false) }
    var fabMenu by remember { mutableStateOf(false) }
    var jump by remember { mutableStateOf(false) }
    var habitSheet by remember { mutableStateOf<Open<Habit>?>(null) }
    var taskSheet by remember { mutableStateOf<Open<TaskUi>?>(null) }

    val dayHabits = habits.filter { it.habit.isScheduled(selected) }
        .sortedWith(compareBy({ it.habit.timeMinutes ?: Int.MAX_VALUE }, { it.habit.sortOrder }))
        .filter { query.isBlank() || it.habit.name.contains(query, true) }
        .filter { when (filter) { 1 -> selected !in it.days; 2 -> selected in it.days; else -> true } }
    val dayTasks = tasks.filter { t ->
        val d = t.task.dueEpochDay
        (d == selected || (selected == today && d != null && d < today && !t.task.done)) &&
            (query.isBlank() || t.task.title.contains(query, true)) &&
            when (filter) { 1 -> !t.task.done; 2 -> t.task.done; else -> true }
    }
    val allToday = habits.filter { it.habit.isScheduled(selected) }
    val doneCount = allToday.count { selected in it.days } + dayTasks.count { it.task.done }
    val totalCount = allToday.size + dayTasks.size

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
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
            Box {
                FloatingActionButton(onClick = { fabMenu = true }, containerColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Filled.Add, "Add") }
                DropdownMenu(fabMenu, { fabMenu = false }) {
                    DropdownMenuItem({ Text("New habit") }, { fabMenu = false; habitSheet = Open() }, leadingIcon = { Icon(Icons.Filled.EmojiEvents, null) })
                    DropdownMenuItem({ Text("New task") }, { fabMenu = false; taskSheet = Open() }, leadingIcon = { Icon(Icons.Filled.TaskAlt, null) })
                }
            }
        }
    ) { pv ->
        Column(Modifier.padding(pv)) {
            DateStrip(selected, today) { selected = it }
            if (totalCount > 0) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("$doneCount of $totalCount done · ${Dates.fmtDate(selected)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    HabitRow(ui, selected, today, { hvm.toggle(ui.habit.id, selected) }, { nav.navigate("habit/${ui.habit.id}") })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
                items(dayTasks, key = { "t${it.task.id}" }) { t ->
                    TaskRow(t, selected, { tvm.setDone(t.task.id, !t.task.done) }, { taskSheet = Open(t) })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }

    if (jump) {
        val st = rememberDatePickerState(initialSelectedDateMillis = selected * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { jump = false },
            confirmButton = { TextButton({ st.selectedDateMillis?.let { selected = Math.floorDiv(it, 86_400_000L) }; jump = false }) { Text("Go") } },
            dismissButton = { TextButton({ selected = today; jump = false }) { Text("Today") } }
        ) { DatePicker(st) }
    }
    habitSheet?.let { s ->
        HabitEditorSheet(s.value, cats, { h, a -> hvm.save(h, a); habitSheet = null }, null, { habitSheet = null })
    }
    taskSheet?.let { s ->
        TaskEditorSheet(s.value?.task, s.value?.subs.orEmpty(), habits.map { it.habit }, s.value?.focusMin ?: 0,
            { t, subs, a -> tvm.save(t, subs, a); taskSheet = null },
            s.value?.let { u -> { tvm.delete(u.task.id); taskSheet = null } },
            null, { taskSheet = null })
    }
}

@Composable
fun DateStrip(selected: Long, today: Long, onSelect: (Long) -> Unit) {
    val days = remember(today) { (today - 30..today + 60).toList() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (days.indexOf(selected) - 2).coerceAtLeast(0))
    LaunchedEffect(selected) { val i = days.indexOf(selected); if (i >= 0) state.animateScrollToItem((i - 2).coerceAtLeast(0)) }
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(days, key = { it }) { d ->
            val sel = d == selected
            val ld = LocalDate.ofEpochDay(d)
            val bg = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
            val fg = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            Column(
                Modifier.width(56.dp).clip(RoundedCornerShape(18.dp)).background(bg).clickable { onSelect(d) }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(Dates.dayShort[ld.dayOfWeek.value - 1], style = MaterialTheme.typography.labelLarge, color = fg.copy(alpha = .8f))
                Text("${ld.dayOfMonth}", style = MaterialTheme.typography.headlineSmall, color = fg)
                Box(Modifier.padding(top = 2.dp).size(width = 14.dp, height = 3.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (d == today) fg else Color.Transparent))
            }
        }
    }
}
