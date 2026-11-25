package com.habitlair.app.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habitlair.app.AppContainer
import com.habitlair.app.ai.*
import com.habitlair.app.data.*
import com.habitlair.app.work.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val lazily = SharingStarted.WhileSubscribed(5_000)

data class HabitUi(
    val habit: Habit, val current: Int, val best: Int, val total: Int, val rate30: Int,
    val days: Set<Long>, val category: Category?
)

class HabitViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.repo
    val categories = repo.categories.stateIn(viewModelScope, lazily, emptyList())

    val habits: StateFlow<List<HabitUi>> = combine(repo.habits, repo.completions, repo.categories) { hs, cs, cats ->
        val today = Dates.today()
        val byHabit = cs.groupBy { it.habitId }.mapValues { e -> e.value.map { it.epochDay }.toSet() }
        hs.map { h ->
            val d = byHabit[h.id] ?: emptySet()
            val s = Streaks.compute(h, d, today)
            HabitUi(h, s.current, s.best, s.total, s.rate30, d, cats.firstOrNull { it.id == h.categoryId })
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, lazily, emptyList())

    fun toggle(id: Long, day: Long) { viewModelScope.launch { repo.toggleHabit(id, day) } }
    fun save(h: Habit, changes: AttachmentChanges) { viewModelScope.launch { repo.saveHabit(h, changes) } }
    fun delete(id: Long) { viewModelScope.launch { repo.deleteHabit(id) } }
    fun deleteMany(ids: Collection<Long>) { viewModelScope.launch { repo.deleteHabits(ids) } }
    fun setDoneMany(ids: Collection<Long>, day: Long, done: Boolean) { viewModelScope.launch { repo.setHabitsDone(ids, day, done) } }
    fun reorder(ids: List<Long>) { viewModelScope.launch { repo.reorderHabits(ids) } }
    fun saveCategory(cat: Category) { viewModelScope.launch { repo.saveCategory(cat) } }
    fun deleteCategory(id: Long) { viewModelScope.launch { repo.deleteCategory(id) } }
    suspend fun attachments(id: Long) = repo.attachmentsFor(Owner.HABIT, id)
}

data class TaskUi(val task: TaskItem, val subs: List<TaskItem>, val focusMin: Int)

class TasksViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.repo
    val tasks: StateFlow<List<TaskUi>> = combine(repo.tasks, repo.sessions) { ts, ss ->
        val subs = ts.filter { it.parentId != null }.groupBy { it.parentId }
        val focus = ss.filter { it.kind == "FOCUS" && it.taskId != null }.groupBy { it.taskId }
            .mapValues { e -> e.value.sumOf { it.minutes } }
        ts.filter { it.parentId == null }.sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .map { TaskUi(it, subs[it.id].orEmpty().sortedWith(compareBy({ s -> s.sortOrder }, { s -> s.id })), focus[it.id] ?: 0) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, lazily, emptyList())

    fun setDone(id: Long, done: Boolean) { viewModelScope.launch { repo.setTaskDone(id, done) } }
    fun save(t: TaskItem, subs: List<TaskItem>, changes: AttachmentChanges) { viewModelScope.launch { repo.saveTask(t, subs, changes) } }
    fun delete(id: Long) { viewModelScope.launch { repo.deleteTask(id) } }
    fun deleteMany(ids: Collection<Long>) { viewModelScope.launch { repo.deleteTasks(ids) } }
    fun setDoneMany(ids: Collection<Long>, done: Boolean) { viewModelScope.launch { repo.setTasksDone(ids, done) } }
    fun reorder(ids: List<Long>) { viewModelScope.launch { repo.reorderTasks(ids) } }
    fun addSubtask(parentId: Long, title: String) { viewModelScope.launch { repo.addSubtask(parentId, title) } }
    suspend fun attachments(id: Long) = repo.attachmentsFor(Owner.TASK, id)
}

class JournalViewModel(private val c: AppContainer) : ViewModel() {
    val entries = c.repo.journal.stateIn(viewModelScope, lazily, emptyList())
    val withMedia = c.db.attachments().observeJournalOwners().map { it.toSet() }.stateIn(viewModelScope, lazily, emptySet<Long>())
    fun save(e: JournalEntry, changes: AttachmentChanges) { viewModelScope.launch { c.repo.saveJournal(e, changes) } }
    fun delete(id: Long) { viewModelScope.launch { c.repo.deleteJournal(id) } }
    suspend fun attachments(id: Long) = c.repo.attachmentsFor(Owner.JOURNAL, id)
}

class TimerViewModel(private val c: AppContainer) : ViewModel() {
    val timer = c.pomodoro.state
    val sessions = c.repo.sessions.stateIn(viewModelScope, lazily, emptyList())
    val presets = c.repo.presets.stateIn(viewModelScope, lazily, emptyList())
    val habits = c.repo.habits.stateIn(viewModelScope, lazily, emptyList())
    val tasks = c.repo.tasks.map { l -> l.filter { !it.done && it.parentId == null } }.stateIn(viewModelScope, lazily, emptyList())
    val settings = c.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun start() = c.pomodoro.start()
    fun pause() = c.pomodoro.pause()
    fun reset() = c.pomodoro.reset()
    fun skip() = c.pomodoro.skip()
    fun link(habitId: Long?, taskId: Long?) = c.pomodoro.setLink(habitId, taskId)
    fun updateSettings(f: (AppSettings) -> AppSettings) { viewModelScope.launch { c.settings.update(f) } }
    fun applyPreset(p: PomodoroPreset) = updateSettings { it.copy(focusMin = p.focusMin, breakMin = p.breakMin, cyclesBeforeLong = p.cycles) }
    fun deletePreset(id: Long) { viewModelScope.launch { c.repo.deletePreset(id) } }
    fun clearLog() { viewModelScope.launch { c.repo.clearSessions() } }
}

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Success(val ms: Long, val model: String) : TestState
    data class Failure(val message: String) : TestState
}

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings?> = c.settings.flow.map<AppSettings, AppSettings?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val test = MutableStateFlow<TestState>(TestState.Idle)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val pendingBackup = MutableStateFlow<PendingBackup?>(null)
    val backupError = MutableStateFlow<String?>(null)

    fun update(reschedule: Boolean = false, f: (AppSettings) -> AppSettings) {
        viewModelScope.launch { c.settings.update(f); if (reschedule) c.scheduler.rescheduleAll() }
    }

    fun testConnection(cfg: AiConfig) {
        test.value = TestState.Running
        viewModelScope.launch {
            test.value = try {
                val r = c.ai.test(cfg)
                TestState.Success(r.ms, r.model)
            } catch (e: AiException) {
                TestState.Failure(e.describe())
            } catch (e: Exception) {
                TestState.Failure("Unexpected error: ${e.message}")
            }
        }
    }

    fun saveAi(provider: Provider, models: Map<Provider, String>, base: String, keys: Map<Provider, String>, prompt: String, temp: Float, share: Boolean) {
        viewModelScope.launch {
            keys.forEach { (p, k) -> c.secure.set(p, k) }
            c.settings.update {
                it.copy(provider = provider, models = Provider.entries.associateWith { p -> models[p]?.trim().orEmpty().ifBlank { p.defaultModel } },
                    customBaseUrl = base.trim(), systemPrompt = prompt.ifBlank { DEFAULT_PROMPT }, temperature = temp, shareContext = share)
            }
            messages.emit("AI settings saved")
        }
    }

    fun sendTestNotification() {
        viewModelScope.launch {
            val ok = Notifier.showTest(c.appContext, c.settings.current())
            messages.emit(if (ok) "Test notification sent" else "Notification permission is not granted")
        }
    }

    fun scheduleTestAlarm() {
        c.scheduler.setAt("test", 0, System.currentTimeMillis() + 10_000)
        viewModelScope.launch { messages.emit(if (c.scheduler.canExact()) "Test alarm will fire in 10 seconds" else "Alarm set (inexact: allow exact alarms for precise timing)") }
    }

    fun export(resolver: ContentResolver, uri: Uri, includeKeys: Boolean) {
        viewModelScope.launch {
            try {
                val sum = withContext(Dispatchers.IO) {
                    val out = resolver.openOutputStream(uri, "wt") ?: error("Couldn't open the destination file")
                    c.backup.export(out, includeKeys)
                }
                messages.emit("Backup exported (${sum.mediaFiles} media files${if (sum.keysIncluded) ", API keys included" else ""})")
            } catch (e: Exception) { messages.emit("Export failed: ${e.message}") }
        }
    }

    fun readBackup(uri: Uri) {
        viewModelScope.launch {
            try { pendingBackup.value = c.backup.inspect(uri) } catch (e: Exception) { backupError.value = e.message ?: "Couldn't read that backup." }
        }
    }

    fun cancelRestore() { pendingBackup.value?.let { c.backup.discard(it) }; pendingBackup.value = null }

    fun confirmRestore() {
        val p = pendingBackup.value ?: return
        pendingBackup.value = null
        viewModelScope.launch {
            try { c.backup.restore(p); messages.emit("Backup restored") } catch (e: Exception) { messages.emit("Restore failed: ${e.message}") }
        }
    }
}

data class Preview(val bundle: ImportBundle, val source: String, val note: String?)

class ChatViewModel(private val c: AppContainer) : ViewModel() {
    private val chatDao = c.db.chat()
    val messages = chatDao.observe().stateIn(viewModelScope, lazily, emptyList())
    val changes = c.db.aiChanges().observe().stateIn(viewModelScope, lazily, emptyList())
    val habitNames = c.repo.habits.map { l -> l.associate { it.id to it.name } }.stateIn(viewModelScope, lazily, emptyMap())
    val taskNames = c.repo.tasks.map { l -> l.associate { it.id to it.title } }.stateIn(viewModelScope, lazily, emptyMap())
    val sending = MutableStateFlow(false)
    val status = MutableStateFlow("")
    /** null while loading; false means no API key is set */
    val configured = MutableStateFlow<Boolean?>(null)
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)

    init { refreshStatus() }
    fun refreshStatus() { viewModelScope.launch { status.value = c.ai.status(); configured.value = c.ai.isConfigured() } }

    suspend fun rewrite(text: String): String = c.ai.rewritePrompt(text)

    private val confirmRegex = Regex("^\\s*(?:(?:yes|yep|yeah|ok|okay|sure|confirm(?:ed)?|do it|go ahead|apply(?: it| that| them| all)?|looks good|sounds good|let'?s do it|please do|please|approved?|perfect|great)[\\s,.!]*)+$", RegexOption.IGNORE_CASE)

    fun send(text: String) {
        val msg = text.trim()
        if (msg.isEmpty() || sending.value) return
        sending.value = true
        viewModelScope.launch {
            try {
                val history = chatDao.all()
                chatDao.insert(ChatMessage(role = "user", text = msg))
                val pending = chatDao.pending()
                if (pending != null && confirmRegex.matches(msg)) { applyMessage(pending.id, null, "Done. "); return@launch }
                val out = c.ai.chat(history, msg, pending?.proposalJson)
                val r = out.value
                if (r.applyPending && pending != null && r.proposal == null) {
                    applyMessage(pending.id, null, if (r.reply.isNotBlank()) r.reply + "\n\n" else "")
                } else {
                    if (r.proposal != null) chatDao.dismissPending()
                    val text2 = (r.reply.ifBlank { if (r.proposal != null) "Here's what I'd suggest." else "…" }) + (out.note?.let { "\n\n⚠ $it" } ?: "")
                    chatDao.insert(ChatMessage(role = "assistant", text = text2, proposalJson = r.proposal?.let { AiParser.encode(it) },
                        proposalStatus = if (r.proposal != null) "pending" else "none"))
                }
            } catch (e: Exception) {
                chatDao.insert(ChatMessage(role = "assistant", text = "Something went wrong: ${e.message}"))
            } finally { sending.value = false }
        }
    }

    private suspend fun applyMessage(id: Long, selected: Set<Int>?, prefix: String) {
        val m = chatDao.get(id) ?: return
        val p = m.proposalJson?.let { AiParser.proposalFromJson(it) } ?: return
        val r = c.applier.apply(p, selected ?: p.actions.indices.toSet())
        chatDao.setStatus(id, if (r.changeId != null) "applied" else "dismissed", r.changeId)
        val text = buildString {
            append(prefix)
            if (r.applied == 0) append("Nothing was changed.") else append("Applied ${r.applied} change(s). You can undo this from the card above or from AI change history.")
            if (r.skipped.isNotEmpty()) append("\nSkipped: " + r.skipped.joinToString("; "))
        }
        chatDao.insert(ChatMessage(role = "assistant", text = text))
    }

    fun apply(messageId: Long, selected: Set<Int>) { viewModelScope.launch { applyMessage(messageId, selected, "") } }

    fun dismiss(messageId: Long) { viewModelScope.launch { chatDao.setStatus(messageId, "dismissed", null) } }

    fun undo(changeId: Long) {
        viewModelScope.launch {
            val ok = try { c.applier.undo(changeId) } catch (e: Exception) { toasts.emit("Undo failed: ${e.message}"); false }
            if (ok) { chatDao.insert(ChatMessage(role = "assistant", text = "Undone. Everything that change touched is back as it was.")); toasts.emit("Change undone") }
        }
    }

    fun clearChat() { viewModelScope.launch { chatDao.clear() } }
}
