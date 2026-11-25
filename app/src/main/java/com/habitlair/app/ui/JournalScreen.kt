@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.habitlair.app.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.habitlair.app.data.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private fun wordCount(e: JournalEntry) = (e.title + " " + e.body).trim().split(Regex("\\s+")).count { it.isNotBlank() }

private fun highlight(text: String, terms: List<String>, bg: Color): AnnotatedString {
    val words = terms.filter { it.isNotBlank() && !it.startsWith("#") }
    if (words.isEmpty()) return AnnotatedString(text)
    val lower = text.lowercase()
    val mark = BooleanArray(text.length)
    for (w in words) {
        var i = lower.indexOf(w)
        while (i >= 0) { for (k in i until minOf(text.length, i + w.length)) mark[k] = true; i = lower.indexOf(w, i + w.length) }
    }
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            var j = i
            while (j < text.length && mark[j] == mark[i]) j++
            if (mark[i]) withStyle(SpanStyle(background = bg, fontWeight = FontWeight.Bold)) { append(text.substring(i, j)) } else append(text.substring(i, j))
            i = j
        }
    }
}

private fun snippet(body: String, terms: List<String>): String {
    val flat = body.replace('\n', ' ')
    val first = terms.filter { !it.startsWith("#") }.map { flat.lowercase().indexOf(it) }.filter { it >= 0 }.minOrNull()
    if (first == null) return flat.take(220)
    val start = (first - 50).coerceAtLeast(0)
    return (if (start > 0) "…" else "") + flat.substring(start, minOf(flat.length, start + 220))
}

private fun writingStreak(days: Set<Long>, today: Long): Int {
    var d = if (today in days) today else today - 1
    var n = 0
    while (d in days) { n++; d-- }
    return n
}

@Composable
fun JournalScreen(nav: NavController) {
    val vm = appViewModel { JournalViewModel(it) }
    val container = LocalContainer.current
    val entries by vm.entries.collectAsStateWithLifecycle()
    val withMedia by vm.withMedia.collectAsStateWithLifecycle()
    val today = Dates.today()

    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var range by rememberSaveable { mutableIntStateOf(0) }          // 0 all, 1 today, 2 7 days, 3 30 days, 4 this month, 5 custom
    var customFrom by rememberSaveable { mutableLongStateOf(today - 30) }
    var customTo by rememberSaveable { mutableLongStateOf(today) }
    var moods by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var tagFilter by remember { mutableStateOf<Set<String>>(emptySet()) }
    var mediaOnly by rememberSaveable { mutableStateOf(false) }
    var oldestFirst by rememberSaveable { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var pickRange by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Open<JournalEntry>?>(null) }

    val allTags = entries.flatMap { it.tags.tagList() }.distinct().sorted()
    val terms = query.trim().lowercase().split(' ').filter { it.isNotBlank() }
    val month = LocalDate.ofEpochDay(today)
    val (from, to) = when (range) {
        1 -> today to today
        2 -> (today - 6) to today
        3 -> (today - 29) to today
        4 -> month.withDayOfMonth(1).toEpochDay() to today
        5 -> minOf(customFrom, customTo) to maxOf(customFrom, customTo)
        else -> Long.MIN_VALUE to Long.MAX_VALUE
    }
    val filtered = entries.filter { it.epochDay in from..to }
        .filter { moods.isEmpty() || it.mood in moods }
        .filter { tagFilter.isEmpty() || it.tags.tagList().any { t -> t in tagFilter } }
        .filter { !mediaOnly || it.id in withMedia }
        .filter { e ->
            terms.all { w ->
                if (w.startsWith("#")) e.tags.lowercase().contains(w.drop(1))
                else e.title.lowercase().contains(w) || e.body.lowercase().contains(w) || e.tags.lowercase().contains(w)
            }
        }
        .let { if (oldestFirst) it.sortedWith(compareBy({ e -> e.epochDay }, { e -> e.createdAt })) else it }
    val activeFilters = (if (range != 0) 1 else 0) + (if (moods.isNotEmpty()) 1 else 0) + (if (tagFilter.isNotEmpty()) 1 else 0) + (if (mediaOnly) 1 else 0) + (if (oldestFirst) 1 else 0)
    val noFilters = activeFilters == 0 && terms.isEmpty()
    val byMonth = filtered.groupBy { YearMonth.from(LocalDate.ofEpochDay(it.epochDay)) }
    val monthFmt = remember { DateTimeFormatter.ofPattern("MMMM yyyy") }
    val dayFmt = remember { DateTimeFormatter.ofPattern("EEE d MMM") }

    val streak = writingStreak(entries.map { it.epochDay }.toSet(), today)
    val avgMood = entries.takeIf { it.isNotEmpty() }?.map { it.mood }?.average()
    val onThisDay = entries.filter { val d = LocalDate.ofEpochDay(it.epochDay); d.monthValue == month.monthValue && d.dayOfMonth == month.dayOfMonth && d.year < month.year }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    if (searching) TextField(query, { query = it }, singleLine = true, placeholder = { Text("Search words, #tags") },
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                        trailingIcon = { DictateButton { query = appendSpoken(query, it) } })
                    else Text("Journal", style = MaterialTheme.typography.headlineMedium)
                },
                actions = {
                    IconButton({ searching = !searching; if (!searching) query = "" }) { Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Search") }
                    IconButton({ showFilters = true }) { BadgedBox({ if (activeFilters > 0) Badge { Text("$activeFilters") } }) { Icon(Icons.Filled.FilterList, "Filters") } }
                    IconButton({ container.chatPrompt.value = "Review my recent journal entries and habit streaks and suggest adjustments."; nav.goTab("chat") }) {
                        Icon(Icons.Filled.AutoAwesome, "Ask the AI coach about my journal and streaks")
                    }
                }
            )
        },
        floatingActionButton = { FloatingActionButton({ sheet = Open() }) { Icon(Icons.Filled.Add, "New entry") } }
    ) { pv ->
        LazyColumn(Modifier.padding(pv), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (entries.isNotEmpty()) item("stats") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("Entries", "${entries.size}", Modifier.weight(1f))
                    StatCard("Day streak", "$streak", Modifier.weight(1f))
                    StatCard("Avg mood", avgMood?.let { "%.1f".format(it) } ?: "–", Modifier.weight(1f))
                }
            }
            if (noFilters && onThisDay.isNotEmpty()) item("otd") {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(14.dp).clickable { sheet = Open(onThisDay.first()) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("On this day", style = MaterialTheme.typography.titleSmall)
                        onThisDay.take(2).forEach { Text("${LocalDate.ofEpochDay(it.epochDay).year}: ${(it.title.ifBlank { it.body }).replace('\n', ' ').take(90)}", maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            if (!noFilters) item("summary") {
                Text("${filtered.size} of ${entries.size} entries", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (filtered.isEmpty()) item("empty") {
                EmptyState(Icons.Filled.Book, if (entries.isEmpty()) "Your journal is empty. Tap + to write your first reflection." else "No entries match your search or filters.")
            }
            byMonth.forEach { (ym, list) ->
                item("m_$ym") { Text("${ym.format(monthFmt)} · ${list.size}", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                items(list, key = { it.id }) { e ->
                    Card(Modifier.fillMaxWidth().clickable { sheet = Open(e) }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(moodEmoji(e.mood), style = MaterialTheme.typography.titleLarge); Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(highlight(e.title.ifBlank { "Untitled" }, terms, MaterialTheme.colorScheme.tertiaryContainer), style = MaterialTheme.typography.titleMedium)
                                    Text(LocalDate.ofEpochDay(e.epochDay).format(dayFmt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (e.id in withMedia) Icon(Icons.Filled.AttachFile, "Has attachments", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                            if (e.body.isNotBlank()) Text(highlight(snippet(e.body, terms), terms, MaterialTheme.colorScheme.tertiaryContainer), maxLines = 4, overflow = TextOverflow.Ellipsis)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                NeutralTag("${wordCount(e)} words")
                                e.tags.tagList().forEach { NeutralTag("#$it") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFilters) LairSheet({ showFilters = false }) {
        Text("Journal filters", style = MaterialTheme.typography.titleLarge)
        SectionLabel("When")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All time", "Today", "Last 7 days", "Last 30 days", "This month").forEachIndexed { i, l -> FilterChip(range == i, { range = i }, { Text(l) }) }
            FilterChip(range == 5, { pickRange = true }, {
                Text(if (range == 5) "${Dates.fmtDate(minOf(customFrom, customTo))} – ${Dates.fmtDate(maxOf(customFrom, customTo))}" else "Custom range")
            }, leadingIcon = { Icon(Icons.Filled.DateRange, null, Modifier.size(18.dp)) })
        }
        SectionLabel("Mood")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { m -> FilterChip(m in moods, { moods = if (m in moods) moods - m else moods + m }, { Text(moodEmoji(m)) }) }
        }
        if (allTags.isNotEmpty()) {
            SectionLabel("Tags")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                allTags.forEach { t -> FilterChip(t in tagFilter, { tagFilter = if (t in tagFilter) tagFilter - t else tagFilter + t }, { Text("#$t") }) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Only entries with attachments", Modifier.weight(1f)); Switch(mediaOnly, { mediaOnly = it }) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Oldest first", Modifier.weight(1f)); Switch(oldestFirst, { oldestFirst = it }) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            TextButton({ range = 0; moods = emptySet(); tagFilter = emptySet(); mediaOnly = false; oldestFirst = false }) { Text("Reset") }
            Button({ showFilters = false }) { Text("Show ${filtered.size}") }
        }
    }
    if (pickRange) {
        val st = rememberDateRangePickerState(initialSelectedStartDateMillis = customFrom * 86_400_000L, initialSelectedEndDateMillis = customTo * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { pickRange = false },
            confirmButton = {
                TextButton({
                    val a = st.selectedStartDateMillis; val b = st.selectedEndDateMillis ?: a
                    if (a != null && b != null) { customFrom = Math.floorDiv(a, 86_400_000L); customTo = Math.floorDiv(b, 86_400_000L); range = 5 }
                    pickRange = false
                }) { Text("Apply") }
            },
            dismissButton = { TextButton({ pickRange = false }) { Text("Cancel") } }
        ) { DateRangePicker(st, Modifier.height(520.dp)) }
    }
    sheet?.let { s ->
        JournalEditorSheet(s.value, { e, a -> vm.save(e, a); sheet = null }, s.value?.let { e -> { vm.delete(e.id); sheet = null } }, { sheet = null })
    }
}
