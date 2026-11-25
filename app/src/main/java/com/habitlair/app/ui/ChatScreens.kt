@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.habitlair.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.habitlair.app.ai.AiConfig
import com.habitlair.app.ai.AiException
import com.habitlair.app.data.AppSettings
import com.habitlair.app.data.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.habitlair.app.ai.AiParser
import com.habitlair.app.ai.describe
import com.habitlair.app.data.ChatMessage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AiChatScreen(nav: NavController) {
    val vm = appViewModel { ChatViewModel(it) }
    val messages by vm.messages.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val configured by vm.configured.collectAsStateWithLifecycle()
    val appSettings: AppSettings? by LocalContainer.current.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    var showSetup by remember { mutableStateOf(false) }
    val habitNames by vm.habitNames.collectAsStateWithLifecycle()
    val taskNames by vm.taskNames.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val container = LocalContainer.current
    val handoff by container.chatPrompt.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(handoff) { handoff?.let { input = it; container.chatPrompt.value = null } }
    var original by remember { mutableStateOf<String?>(null) }
    var rewriting by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.toasts.collect { snack.showSnackbar(it) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshStatus() }
    // First time in the AI tab without a connected AI: ask the person to set one up
    LaunchedEffect(configured, appSettings?.aiSetupSeen) { if (configured == false && appSettings?.aiSetupSeen == false) showSetup = true }
    LaunchedEffect(messages.size, sending) {
        val n = messages.size + if (sending) 1 else 0
        if (n > 0) listState.animateScrollToItem(n - 1)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Column { Text("AI coach"); Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) } },
                actions = {
                    IconButton({ nav.navigate("aihistory") }) { Icon(Icons.Filled.History, "AI change history") }
                    IconButton({ confirmClear = true }, enabled = messages.isNotEmpty()) { Icon(Icons.Filled.DeleteSweep, "Clear conversation") }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (original != null) TextButton({ input = original ?: input; original = null }) { Icon(Icons.Filled.Undo, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Undo rewrite") }
                    Row(verticalAlignment = Alignment.Bottom) {
                        OutlinedTextField(
                            value = input, onValueChange = { input = it; original = null }, modifier = Modifier.weight(1f),
                            placeholder = { Text("Tell me about your routine or goals…") }, maxLines = 6,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                            trailingIcon = {
                              Row(verticalAlignment = Alignment.CenterVertically) {
                                DictateButton { input = appendSpoken(input, it); original = null }
                                if (rewriting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else IconButton({
                                    if (input.isBlank()) return@IconButton
                                    scope.launch {
                                        rewriting = true
                                        try {
                                            val before = input
                                            val r = vm.rewrite(before)
                                            if (r.isNotBlank()) { original = before; input = r }
                                        } catch (e: AiException) { snack.showSnackbar(e.describe()) }
                                        catch (e: Exception) { snack.showSnackbar("Rewrite failed: ${e.message}") }
                                        finally { rewriting = false }
                                    }
                                }, enabled = input.isNotBlank(), modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.AutoFixHigh, "Improve my prompt with AI", Modifier.size(20.dp)) }
                              }
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton({ vm.send(input); input = ""; original = null }, enabled = input.isNotBlank() && !sending && !rewriting) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                    }
                }
            }
        }
    ) { pv ->
      Column(Modifier.padding(pv)) {
        if (configured == false) NoAiBanner { showSetup = true }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (messages.isEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Chat with your coach", style = MaterialTheme.typography.titleLarge)
                    Text("Describe your routine and I'll give honest feedback first. I only create or change anything after you confirm, and every change can be undone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "Here's my daily routine: ",
                            "Help me build a study routine for ",
                            "Break down this task: ",
                            "Review my progress and streaks"
                        ).forEach { s -> SuggestionChip({ input = s }, { Text(s.trim().removeSuffix(":")) }) }
                    }
                }
            }
            items(messages, key = { it.id }) { m -> MessageBubble(m, vm, habitNames, taskNames) }
            if (sending) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                    Text("Thinking…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
      }
    }
    if (showSetup) AiSetupSheet { saved ->
        showSetup = false
        scope.launch { container.settings.update { it.copy(aiSetupSeen = true) } }
        if (saved) vm.refreshStatus()
    }
    if (confirmClear) ConfirmDialog("Clear conversation?", "Messages are removed. Changes the AI already applied stay, and can still be undone from AI change history.", "Clear",
        { vm.clearChat(); confirmClear = false }, { confirmClear = false })
}

@Composable
private fun MessageBubble(m: ChatMessage, vm: ChatViewModel, habitNames: Map<Long, String>, taskNames: Map<Long, String>) {
    val user = m.role == "user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (user) 18.dp else 4.dp, bottomEnd = if (user) 4.dp else 18.dp),
            color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 320.dp)
        ) { Text(m.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge) }
        if (!user && m.proposalJson != null) ProposalCard(m, vm, habitNames, taskNames)
    }
}

@Composable
private fun ProposalCard(m: ChatMessage, vm: ChatViewModel, habitNames: Map<Long, String>, taskNames: Map<Long, String>) {
    val proposal = remember(m.proposalJson) { m.proposalJson?.let { AiParser.proposalFromJson(it) } } ?: return
    val checks = remember(m.id) { mutableStateListOf<Boolean>().apply { repeat(proposal.actions.size) { add(true) } } }
    val pending = m.proposalStatus == "pending"
    val count = checks.count { it }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(proposal.summary, style = MaterialTheme.typography.titleSmall)
            Text(
                when (m.proposalStatus) { "pending" -> "Proposal: nothing has changed yet"; "applied" -> "Applied"; "undone" -> "Applied, then undone"; else -> "Not applied" },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary
            )
            proposal.actions.forEachIndexed { i, a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pending) Checkbox(checks[i], { checks[i] = it })
                    Text((if (pending) "" else "• ") + a.describe(habitNames, taskNames), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (pending) {
                    TextButton({ vm.dismiss(m.id) }) { Text("Not now") }
                    Button({ vm.apply(m.id, checks.indices.filter { checks[it] }.toSet()) }, enabled = count > 0) { Text("Apply ($count)") }
                } else if (m.proposalStatus == "applied" && m.changeId != null) {
                    OutlinedButton({ vm.undo(m.changeId) }) { Icon(Icons.Filled.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Undo") }
                }
            }
        }
    }
}

@Composable
fun AiHistoryScreen(nav: NavController) {
    val vm = appViewModel { ChatViewModel(it) }
    val changes by vm.changes.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    var confirm by remember { mutableStateOf<Long?>(null) }
    val fmt = remember { SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()) }
    LaunchedEffect(Unit) { vm.toasts.collect { snack.showSnackbar(it) } }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0), title = { Text("AI change history") },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        }
    ) { pv ->
        LazyColumn(Modifier.padding(pv)) {
            if (changes.isEmpty()) item { EmptyState(Icons.Filled.History, "No AI changes yet. When you apply a proposal from the chat it is saved here so you can undo it.") }
            items(changes, key = { it.id }) { c ->
                ListItem(
                    headlineContent = { Text(c.summary) },
                    supportingContent = { Text(fmt.format(Date(c.createdAt)) + if (c.undone) " · undone" else "") },
                    trailingContent = { if (!c.undone) OutlinedButton({ confirm = c.id }) { Text("Undo") } }
                )
                HorizontalDivider()
            }
        }
    }
    confirm?.let { id ->
        ConfirmDialog("Undo this change?", "Everything this change created is removed, and anything it edited or deleted is restored to how it was.", "Undo",
            { vm.undo(id); confirm = null }, { confirm = null })
    }
}

@Composable
private fun NoAiBanner(onSetup: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CloudOff, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("No AI is connected", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                Text("Replies come from simple built-in rules, not a real model, so there is no real conversation yet. Connect your own AI to unlock it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            TextButton(onSetup) { Text("Set up") }
        }
    }
}

/** Compact first-run setup: pick a provider, paste a key, test it, save it. */
@Composable
private fun AiSetupSheet(onClose: (saved: Boolean) -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(Provider.GEMINI) }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(Provider.GEMINI.defaultModel) }
    var base by remember { mutableStateOf(Provider.CUSTOM.defaultBase) }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    suspend fun save() {
        withContext(Dispatchers.IO) { c.secure.set(provider, key.trim()) }
        c.settings.update { it.copy(provider = provider, models = it.models + (provider to model.trim()), customBaseUrl = if (provider == Provider.CUSTOM) base.trim() else it.customBaseUrl, aiSetupSeen = true) }
    }

    LairSheet({ onClose(false) }) {
        Text("Connect your AI", style = MaterialTheme.typography.titleLarge)
        Text("HabitLair uses your own AI account (bring your own key). The key is stored encrypted on this phone only.", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Provider.entries.forEach { p -> FilterChip(provider == p, { provider = p; model = p.defaultModel; message = null; failed = false }, { Text(p.label) }) }
        }
        if (provider == Provider.CUSTOM) OutlinedTextField(base, { base = it }, label = { Text("Base URL (OpenAI-compatible)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            key, { key = it.trim() }, label = { Text("API key" + if (provider == Provider.CUSTOM) " (optional)" else "") }, singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = { IconButton({ show = !show }) { Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (show) "Hide key" else "Show key") } }
        )
        if (provider.models.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            provider.models.take(4).forEach { m -> FilterChip(model == m, { model = m }, { Text(m) }) }
        }
        OutlinedTextField(model, { model = it }, label = { Text("Model ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        message?.let {
            Text(it, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            enabled = !busy && (key.isNotBlank() || provider == Provider.CUSTOM) && model.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true; message = null; failed = false
                scope.launch {
                    try {
                        val cfg = AiConfig(provider, if (provider == Provider.CUSTOM) base.trim() else provider.defaultBase, model.trim(), key.trim(), 0.7f)
                        val r = c.ai.test(cfg)
                        save()
                        message = "Connected · ${r.model} · ${r.ms} ms"
                        busy = false
                        onClose(true)
                    } catch (e: AiException) {
                        failed = true; message = e.describe(); busy = false
                    } catch (e: Exception) {
                        failed = true; message = "Unexpected error: ${e.message}"; busy = false
                    }
                }
            }
        ) {
            if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
            Text(if (busy) "Testing…" else "Test and save")
        }
        if (failed) OutlinedButton({ scope.launch { save(); onClose(true) } }, Modifier.fillMaxWidth()) { Text("Save anyway") }
        TextButton({ onClose(false) }, Modifier.fillMaxWidth()) { Text("Skip for now") }
    }
}
