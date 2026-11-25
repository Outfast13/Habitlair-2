@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.habitlair.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.habitlair.app.ai.*
import com.habitlair.app.data.*
import kotlinx.coroutines.launch

@Composable
fun AdvancedToggle(expanded: Boolean, onChange: (Boolean) -> Unit) {
    TextButton({ onChange(!expanded) }, Modifier.fillMaxWidth()) {
        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        Spacer(Modifier.width(6.dp))
        Text(if (expanded) "Hide advanced options" else "Advanced options")
    }
}

@Composable
fun HabitEditorSheet(
    initial: Habit?, categories: List<Category>,
    onSave: (Habit, AttachmentChanges) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit
) {
    val repo = LocalContainer.current.repo
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var icon by remember { mutableStateOf(initial?.icon ?: "check") }
    var color by remember { mutableLongStateOf(initial?.colorHex ?: Palette[0]) }
    var cat by remember { mutableStateOf(initial?.categoryId) }
    var time by remember { mutableStateOf(initial?.timeMinutes) }
    var days by remember { mutableIntStateOf(initial?.daysMask ?: 127) }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var reminder by remember { mutableStateOf(initial?.reminder ?: true) }
    var advanced by remember { mutableStateOf(initial != null && (initial.notes.isNotBlank() || initial.daysMask != 127 || initial.categoryId != null || initial.icon != "check")) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val draft = remember { AttachmentDraft() }
    LaunchedEffect(initial?.id) { if (initial != null) draft.load(repo.attachmentsFor(Owner.HABIT, initial.id)) }
    DisposableEffect(Unit) { onDispose { if (!saved) draft.discard() } }

    LairSheet(onDismiss) {
        Text(if (initial == null) "New habit" else "Edit habit", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(name, { name = it; error = null }, label = { Text("Habit") }, singleLine = true, isError = error != null,
            supportingText = error?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { DictateButton { name = appendSpoken(name, it) } })
        TimeField("Set a time", time) { time = it }

        AdvancedToggle(advanced) { advanced = it }
        AnimatedVisibility(advanced) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("Repeat")
                DaysPicker(days) { days = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Remind me at that time", Modifier.weight(1f))
                    Switch(reminder && time != null, { reminder = it }, enabled = time != null)
                }
                SectionLabel("Icon")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconKeys.all.forEach { k -> FilterChip(selected = icon == k, onClick = { icon = k }, label = { Icon(iconFor(k), k, Modifier.size(20.dp)) }) }
                }
                SectionLabel("Color")
                ColorRow(color) { color = it }
                SectionLabel("Category")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = cat == null, onClick = { cat = null }, label = { Text("None") })
                    categories.forEach { c -> FilterChip(selected = cat == c.id, onClick = { cat = c.id }, label = { Text(c.name) }) }
                }
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { DictateButton { notes = appendSpoken(notes, it) } })
                AttachmentSection(draft)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (onDelete != null) IconButton({ confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete habit") }
            Spacer(Modifier.weight(1f))
            TextButton(onDismiss) { Text("Cancel") }
            Button(onClick = {
                when {
                    name.isBlank() -> error = "Give the habit a name"
                    days == 0 -> { advanced = true; error = "Pick at least one day (Advanced options)" }
                    else -> {
                        saved = true
                        onSave((initial ?: Habit(name = "")).copy(name = name.trim(), icon = icon, colorHex = color, categoryId = cat,
                            timeMinutes = time, daysMask = days, notes = notes.trim(), reminder = reminder), draft.changes())
                    }
                }
            }) { Text("Save") }
        }
    }
    if (confirmDelete) ConfirmDialog("Delete habit?", "\"${initial?.name}\" and its history will be removed.", "Delete",
        { confirmDelete = false; onDelete?.invoke() }, { confirmDelete = false })
}

@Composable
fun TaskEditorSheet(
    initial: TaskItem?, existingSubs: List<TaskItem>, habits: List<Habit>, focusMin: Int,
    onSave: (TaskItem, List<TaskItem>, AttachmentChanges) -> Unit, onDelete: (() -> Unit)?,
    onStartFocus: ((TaskItem) -> Unit)?, onDismiss: () -> Unit, prefillTitle: String = ""
) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(initial?.title ?: prefillTitle) }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var priority by remember { mutableIntStateOf(initial?.priority ?: 1) }
    var due by remember { mutableStateOf(initial?.dueEpochDay) }
    var dueTime by remember { mutableStateOf(initial?.dueMinutes) }
    var tags by remember { mutableStateOf(initial?.tags ?: "") }
    var estimate by remember { mutableStateOf(initial?.estimateMinutes?.toString() ?: "") }
    var reminder by remember { mutableStateOf(initial?.reminder ?: true) }
    var habitId by remember { mutableStateOf(initial?.habitId) }
    var habitMenu by remember { mutableStateOf(false) }
    val subs = remember { mutableStateListOf<TaskItem>().apply { addAll(existingSubs) } }
    var newSub by remember { mutableStateOf("") }
    var menuIndex by remember { mutableIntStateOf(-1) }
    var advanced by remember {
        mutableStateOf(initial != null && (existingSubs.isNotEmpty() || initial.notes.isNotBlank() || initial.tags.isNotBlank() ||
            initial.priority != 1 || initial.estimateMinutes != null || initial.habitId != null))
    }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var aiLoading by remember { mutableStateOf(false) }
    var aiPreview by remember { mutableStateOf<Preview?>(null) }
    var saved by remember { mutableStateOf(false) }
    val draft = remember { AttachmentDraft() }
    LaunchedEffect(initial?.id) { if (initial != null) draft.load(c.repo.attachmentsFor(Owner.TASK, initial.id)) }
    DisposableEffect(Unit) { onDispose { if (!saved) draft.discard() } }

    LairSheet(onDismiss) {
        Text(if (initial == null) "New task" else "Edit task", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(title, { title = it; error = null }, label = { Text("Task") }, isError = error != null,
            supportingText = error?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { DictateButton { title = appendSpoken(title, it) } })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DateField("Date", due) { due = it; if (it == null) dueTime = null }
            TimeField("Time", dueTime) { dueTime = it; if (it != null && due == null) due = Dates.today() }
        }

        AdvancedToggle(advanced) { advanced = it }
        AnimatedVisibility(advanced) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("Priority")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Low", "Medium", "High").forEachIndexed { i, l ->
                        SegmentedButton(selected = priority == i, onClick = { priority = i }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Remind me at the due time", Modifier.weight(1f))
                    Switch(reminder && due != null, { reminder = it }, enabled = due != null)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(estimate, { estimate = it.filter(Char::isDigit).take(4) }, label = { Text("Minutes") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(tags, { tags = it }, label = { Text("Tags (a, b)") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { DictateButton { notes = appendSpoken(notes, it) } })
                Box {
                    OutlinedButton({ habitMenu = true }) {
                        Icon(Icons.Filled.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text("Habit: " + (habits.firstOrNull { it.id == habitId }?.name ?: "none"))
                    }
                    DropdownMenu(habitMenu, { habitMenu = false }) {
                        DropdownMenuItem({ Text("None") }, { habitId = null; habitMenu = false })
                        habits.forEach { h -> DropdownMenuItem({ Text(h.name) }, { habitId = h.id; habitMenu = false }) }
                    }
                }
                SectionLabel("Sub-tasks")
                subs.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(s.done, { subs[i] = s.copy(done = it) })
                        TextField(
                            value = s.title, onValueChange = { subs[i] = s.copy(title = it) }, singleLine = true, modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyLarge,
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent)
                        )
                        Box {
                            IconButton({ menuIndex = i }) { Icon(Icons.Filled.MoreVert, "Sub-task options") }
                            DropdownMenu(menuIndex == i, { menuIndex = -1 }) {
                                DropdownMenuItem({ Text("Move up") }, { if (i > 0) { val t = subs[i]; subs[i] = subs[i - 1]; subs[i - 1] = t }; menuIndex = -1 },
                                    enabled = i > 0, leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, null) })
                                DropdownMenuItem({ Text("Move down") }, { if (i < subs.lastIndex) { val t = subs[i]; subs[i] = subs[i + 1]; subs[i + 1] = t }; menuIndex = -1 },
                                    enabled = i < subs.lastIndex, leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, null) })
                                DropdownMenuItem({ Text("Delete") }, { subs.removeAt(i); menuIndex = -1 }, leadingIcon = { Icon(Icons.Filled.Delete, null) })
                            }
                        }
                    }
                }
                val addSub = {
                    if (newSub.isNotBlank()) { subs.add(TaskItem(title = newSub.trim(), parentId = initial?.id)); newSub = "" }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newSub, { newSub = it }, label = { Text("Add sub-task") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { addSub() }),
                        trailingIcon = { DictateButton { newSub = appendSpoken(newSub, it) } })
                    IconButton({ addSub() }) { Icon(Icons.Filled.Add, "Add sub-task") }
                }
                OutlinedButton(
                    enabled = !aiLoading,
                    onClick = {
                        if (title.isBlank()) { error = "Enter a title first so the AI knows what to break down"; return@OutlinedButton }
                        aiLoading = true
                        scope.launch {
                            val r = c.ai.breakDown(title.trim(), notes.trim())
                            aiLoading = false
                            aiPreview = Preview(ImportBundle(tasks = r.value.map { TaskPlan(it.title, 1, null, it.estimateMinutes) }, parentTaskId = initial?.id ?: -1), r.source, r.note)
                        }
                    }
                ) {
                    if (aiLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Break down with AI")
                }
                if (initial != null && focusMin > 0) Text("Focus time logged: $focusMin min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AttachmentSection(draft)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (onDelete != null) IconButton({ confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete task") }
            if (initial != null && onStartFocus != null) IconButton({ onStartFocus(initial) }) { Icon(Icons.Filled.Timer, "Start focus session") }
            Spacer(Modifier.weight(1f))
            TextButton(onDismiss) { Text("Cancel") }
            Button({
                if (title.isBlank()) error = "Give the task a title" else {
                    saved = true
                    onSave(
                        (initial ?: TaskItem(title = "")).copy(title = title.trim(), notes = notes.trim(), priority = priority, dueEpochDay = due,
                            dueMinutes = if (due != null) dueTime else null, tags = tags.tagList().joinToString(","), habitId = habitId,
                            estimateMinutes = estimate.toIntOrNull()?.takeIf { it > 0 }, reminder = reminder),
                        subs.toList(), draft.changes()
                    )
                }
            }) { Text("Save") }
        }
    }
    aiPreview?.let { p ->
        ImportPreviewSheet(p, onDismiss = { aiPreview = null }, onConfirm = { b ->
            advanced = true
            b.tasks.forEach { subs.add(TaskItem(title = it.title, estimateMinutes = it.estimateMinutes, parentId = initial?.id)) }
            aiPreview = null
        }, confirmLabel = "Add sub-tasks")
    }
    if (confirmDelete) ConfirmDialog("Delete task?", "\"${initial?.title}\" and its sub-tasks will be removed.", "Delete",
        { confirmDelete = false; onDelete?.invoke() }, { confirmDelete = false })
}

val reflectionPrompts = listOf(
    "What went well today, and what made it possible?",
    "Which habit felt hardest today, and what got in the way?",
    "What is one small thing I can do tomorrow to make my routine easier?",
    "What am I grateful for right now?",
    "How did my energy change through the day?",
    "What did I avoid, and what would a tiny first step look like?",
    "What am I proud of this week?"
)

@Composable
fun JournalEditorSheet(initial: JournalEntry?, onSave: (JournalEntry, AttachmentChanges) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    val repo = LocalContainer.current.repo
    var day by remember { mutableStateOf<Long?>(initial?.epochDay ?: Dates.today()) }
    var mood by remember { mutableIntStateOf(initial?.mood ?: 3) }
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var body by remember { mutableStateOf(initial?.body ?: "") }
    var tags by remember { mutableStateOf(initial?.tags ?: "") }
    var prompt by remember { mutableStateOf(reflectionPrompts.random()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val draft = remember { AttachmentDraft() }
    LaunchedEffect(initial?.id) { if (initial != null) draft.load(repo.attachmentsFor(Owner.JOURNAL, initial.id)) }
    DisposableEffect(Unit) { onDispose { if (!saved) draft.discard() } }

    LairSheet(onDismiss) {
        Text(if (initial == null) "New journal entry" else "Edit entry", style = MaterialTheme.typography.titleLarge)
        DateField("Pick date", day) { day = it ?: Dates.today() }
        SectionLabel("Mood")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { m -> FilterChip(selected = mood == m, onClick = { mood = m }, label = { Text(moodEmoji(m)) }) }
        }
        OutlinedTextField(title, { title = it }, label = { Text("Title (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { DictateButton { title = appendSpoken(title, it) } })
        Card(Modifier.fillMaxWidth().clickable { prompt = reflectionPrompts.filter { it != prompt }.random() },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lightbulb, null); Spacer(Modifier.width(8.dp))
                Text(prompt, Modifier.weight(1f)); Icon(Icons.Filled.Refresh, "New prompt")
            }
        }
        OutlinedTextField(body, { body = it; error = null }, label = { Text("Reflection") }, minLines = 5, isError = error != null,
            supportingText = error?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { DictateButton { body = appendSpoken(body, it) } })
        OutlinedTextField(tags, { tags = it }, label = { Text("Tags (a, b)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        AttachmentSection(draft)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (onDelete != null) IconButton({ confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete entry") }
            Spacer(Modifier.weight(1f))
            TextButton(onDismiss) { Text("Cancel") }
            Button({
                if (body.isBlank() && title.isBlank() && draft.items.isEmpty()) error = "Write something first" else {
                    saved = true
                    onSave((initial ?: JournalEntry(epochDay = 0)).copy(epochDay = day ?: Dates.today(), mood = mood, title = title.trim(),
                        body = body.trim(), tags = tags.tagList().joinToString(",")), draft.changes())
                }
            }) { Text("Save") }
        }
    }
    if (confirmDelete) ConfirmDialog("Delete entry?", "This journal entry will be removed.", "Delete", { confirmDelete = false; onDelete?.invoke() }, { confirmDelete = false })
}

/** Preview-before-import: the person picks exactly which AI-generated items are written to the database. */
@Composable
fun ImportPreviewSheet(preview: Preview, onDismiss: () -> Unit, onConfirm: (ImportBundle) -> Unit, confirmLabel: String = "Import selected") {
    val b = preview.bundle
    val h = remember { mutableStateListOf<Boolean>().apply { repeat(b.habits.size) { add(true) } } }
    val t = remember { mutableStateListOf<Boolean>().apply { repeat(b.tasks.size) { add(true) } } }
    val p = remember { mutableStateListOf<Boolean>().apply { repeat(b.presets.size) { add(true) } } }
    val count = h.count { it } + t.count { it } + p.count { it }
    LairSheet(onDismiss) {
        Text("Review before importing", style = MaterialTheme.typography.titleLarge)
        Text("Source: ${preview.source}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        preview.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (b.isEmpty) Text("Nothing to import.")
        if (b.habits.isNotEmpty()) SectionLabel("Habits")
        b.habits.forEachIndexed { i, x ->
            PreviewRow(h[i], { h[i] = it }, x.name, "${Dates.daysLabel(x.daysMask)}${x.timeMinutes?.let { " · " + Dates.fmtTime(it) } ?: ""}")
        }
        if (b.tasks.isNotEmpty()) SectionLabel(if (b.parentTaskId != null) "Sub-tasks" else "Tasks")
        b.tasks.forEachIndexed { i, x ->
            val extra = buildList {
                x.estimateMinutes?.let { add("${it} min") }
                x.dueInDays?.let { add(if (it == 0) "due today" else "due in $it d") }
                if (x.subtasks.isNotEmpty()) add(x.subtasks.joinToString(", ") { it.title }.take(120))
            }.joinToString(" · ")
            PreviewRow(t[i], { t[i] = it }, x.title, extra)
        }
        if (b.presets.isNotEmpty()) SectionLabel("Pomodoro presets")
        b.presets.forEachIndexed { i, x -> PreviewRow(p[i], { p[i] = it }, x.label, "${x.focusMinutes}/${x.breakMinutes} min × ${x.cycles}") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            TextButton(onDismiss) { Text("Discard") }
            Button(enabled = count > 0, onClick = {
                onConfirm(b.copy(habits = b.habits.filterIndexed { i, _ -> h[i] }, tasks = b.tasks.filterIndexed { i, _ -> t[i] }, presets = b.presets.filterIndexed { i, _ -> p[i] }))
            }) { Text("$confirmLabel ($count)") }
        }
    }
}

@Composable
private fun PreviewRow(checked: Boolean, onChange: (Boolean) -> Unit, title: String, sub: String) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Column(Modifier.weight(1f)) {
            Text(title)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
