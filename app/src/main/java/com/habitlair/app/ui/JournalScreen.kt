@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.habitlair.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.habitlair.app.data.*

@Composable
fun JournalScreen(nav: NavController) {
    val vm = appViewModel { JournalViewModel(it) }
    val container = LocalContainer.current
    val entries by vm.entries.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var mood by rememberSaveable { mutableIntStateOf(0) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<Open<JournalEntry>?>(null) }
    var sheetAttach by remember { mutableStateOf<List<Attachment>>(emptyList()) }

    val tags = entries.flatMap { it.tags.tagList() }.distinct().sorted()
    val shown = entries.filter { mood == 0 || it.mood == mood }.filter { tag == null || tag in it.tags.tagList() }
        .filter { query.isBlank() || it.title.contains(query, true) || it.body.contains(query, true) || it.tags.contains(query, true) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(windowInsets = WindowInsets(0, 0, 0, 0), 
                title = { Text("Journal", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    IconButton({ container.chatPrompt.value = "Review my recent journal entries and habit streaks and suggest adjustments."; nav.goTab("chat") }) {
                        Icon(Icons.Filled.AutoAwesome, "Ask the AI coach about my journal and streaks")
                    }
                }
            )
        },
        floatingActionButton = { FloatingActionButton({ sheet = Open() }) { Icon(Icons.Filled.Add, "New entry") } }
    ) { pv ->
        Column(Modifier.padding(pv)) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search entries") }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Close, "Clear search") } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..5).forEach { m -> FilterChip(mood == m, { mood = if (mood == m) 0 else m }, { Text(moodEmoji(m)) }) }
                tags.forEach { t -> FilterChip(tag == t, { tag = if (tag == t) null else t }, { Text("#$t") }) }
            }
            LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (shown.isEmpty()) item { EmptyState(Icons.Filled.Book, if (entries.isEmpty()) "Your journal is empty. Tap + to write your first reflection." else "No entries match your filters.") }
                items(shown, key = { it.id }) { e ->
                    Card(Modifier.fillMaxWidth().clickable { sheet = Open(e) }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(moodEmoji(e.mood), style = MaterialTheme.typography.titleLarge); Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(e.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium)
                                    Text(Dates.fmtDate(e.epochDay), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (e.body.isNotBlank()) Text(e.body, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            if (e.tags.isNotBlank()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { e.tags.tagList().forEach { NeutralTag("#$it") } }
                        }
                    }
                }
            }
        }
    }
    sheet?.let { s ->
        JournalEditorSheet(s.value, { e, a -> vm.save(e, a); sheet = null }, s.value?.let { e -> { vm.delete(e.id); sheet = null } }, { sheet = null })
    }
}
