package com.habitlair.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import com.habitlair.app.ai.ImportBundle
import com.habitlair.app.widget.updateAllWidgets
import com.habitlair.app.work.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** New habits slot in by time of day (before the first later-timed habit); otherwise they go to the end. */
suspend fun AppDatabase.placeHabitOrder(timeMinutes: Int?): Int {
    val all = habits().all().filter { !it.archived }.sortedWith(compareBy({ it.sortOrder }, { it.id }))
    val idx = if (timeMinutes == null) -1 else all.indexOfFirst { it.timeMinutes != null && it.timeMinutes > timeMinutes }
    return if (idx < 0) (all.maxOfOrNull { it.sortOrder } ?: -1) + 1 else { val pos = all[idx].sortOrder; habits().shiftFrom(pos); pos }
}

data class ImportResult(val habits: Int, val tasks: Int, val subtasks: Int, val presets: Int)

class MediaFiles(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "media").apply { mkdirs() }

    fun newFile(ext: String) = File(dir, "${UUID.randomUUID()}.$ext")
    fun uriFor(f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)

    fun attachmentFor(f: File, kind: String, name: String? = null) =
        Attachment(ownerType = "", ownerId = 0, kind = kind, path = f.absolutePath, name = name ?: f.name)

    fun copyIn(uri: Uri, kind: String): Attachment {
        val mime = ctx.contentResolver.getType(uri)
        val name = displayName(uri)
        val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?: name?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() && it.length <= 5 }
            ?: when (kind) { MediaKind.PHOTO -> "jpg"; MediaKind.VIDEO -> "mp4"; MediaKind.AUDIO -> "m4a"; else -> "bin" }
        val f = newFile(ext)
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Can't open the selected file")
        input.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return attachmentFor(f, kind, name)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()
}

class Repository(
    private val ctx: Context,
    val db: AppDatabase,
    val settings: SettingsStore,
    val scheduler: ReminderScheduler,
    val media: MediaFiles
) {
    val habits = db.habits().observe()
    val completions = db.habits().observeCompletions()
    val categories = db.categories().observe()
    val tasks = db.tasks().observe()
    val journal = db.journal().observe()
    val sessions = db.pomodoro().observeSessions()
    val presets = db.pomodoro().observePresets()

    suspend fun refreshWidget() { withContext(Dispatchers.Default) { updateAllWidgets(ctx) } }

    // ---------- attachments ----------
    suspend fun attachmentsFor(type: String, id: Long): List<Attachment> = db.attachments().forOwner(type, id)

    private suspend fun commit(type: String, id: Long, c: AttachmentChanges) {
        c.removed.forEach { db.attachments().delete(it.id); File(it.path).delete() }
        c.added.forEach { db.attachments().insert(it.copy(id = 0, ownerType = type, ownerId = id)) }
    }

    private suspend fun purge(type: String, id: Long) {
        db.attachments().forOwner(type, id).forEach { File(it.path).delete() }
        db.attachments().deleteForOwner(type, id)
    }

    // ---------- habits ----------
    suspend fun saveHabit(h: Habit, att: AttachmentChanges? = null): Long {
        val toSave = if (h.id == 0L) h.copy(sortOrder = db.placeHabitOrder(h.timeMinutes)) else h
        val id = if (h.id == 0L) db.habits().upsert(toSave) else { db.habits().upsert(toSave); h.id }
        att?.let { commit(Owner.HABIT, id, it) }
        scheduler.syncHabit(id)
        refreshWidget()
        return id
    }

    suspend fun deleteHabit(id: Long) {
        scheduler.cancel(Owner.HABIT, id)
        purge(Owner.HABIT, id)
        db.tasks().unlinkHabit(id)
        db.pomodoro().unlinkHabit(id)
        db.habits().delete(id)
        refreshWidget()
    }

    suspend fun deleteHabits(ids: Collection<Long>) { ids.forEach { deleteHabit(it) } }

    suspend fun setHabitDone(id: Long, day: Long, done: Boolean) {
        if (done) db.habits().addCompletion(HabitCompletion(habitId = id, epochDay = day)) else db.habits().removeCompletion(id, day)
        refreshWidget()
    }

    suspend fun toggleHabit(id: Long, day: Long): Boolean {
        val now = db.habits().isDone(id, day) == 0
        setHabitDone(id, day, now)
        return now
    }

    suspend fun setHabitsDone(ids: Collection<Long>, day: Long, done: Boolean) {
        db.withTransaction {
            ids.forEach { id ->
                if (done) db.habits().addCompletion(HabitCompletion(habitId = id, epochDay = day)) else db.habits().removeCompletion(id, day)
            }
        }
        refreshWidget()
    }

    /** Persists the exact order of [ids] (index becomes sortOrder). */
    suspend fun reorderHabits(ids: List<Long>) {
        db.withTransaction { ids.forEachIndexed { i, id -> db.habits().setOrder(id, i) } }
        refreshWidget()
    }

    // ---------- tasks ----------
    suspend fun saveTask(t: TaskItem, subs: List<TaskItem>? = null, att: AttachmentChanges? = null): Long {
        val toSave = if (t.id == 0L && t.parentId == null) t.copy(sortOrder = (db.tasks().maxTopOrder() ?: -1) + 1) else t
        val id = if (t.id == 0L) db.tasks().upsert(toSave) else { db.tasks().upsert(toSave); t.id }
        if (subs != null) {
            val keep = subs.map { it.id }.filter { it != 0L }.toSet()
            db.tasks().children(id).filter { it.id !in keep }.forEach { db.tasks().delete(it.id) }
            subs.forEachIndexed { i, s -> db.tasks().upsert(s.copy(parentId = id, sortOrder = i)) }
        }
        att?.let { commit(Owner.TASK, id, it) }
        scheduler.syncTask(id)
        refreshWidget()
        return id
    }

    suspend fun addSubtask(parentId: Long, title: String) {
        val next = (db.tasks().children(parentId).maxOfOrNull { it.sortOrder } ?: -1) + 1
        db.tasks().upsert(TaskItem(title = title.trim(), parentId = parentId, sortOrder = next))
    }

    suspend fun deleteTask(id: Long) {
        db.tasks().children(id).forEach { scheduler.cancel(Owner.TASK, it.id) }
        db.tasks().deleteChildren(id)
        scheduler.cancel(Owner.TASK, id)
        purge(Owner.TASK, id)
        db.pomodoro().unlinkTask(id)
        db.tasks().delete(id)
        refreshWidget()
    }

    suspend fun deleteTasks(ids: Collection<Long>) { ids.forEach { deleteTask(it) } }

    suspend fun setTaskDone(id: Long, done: Boolean) {
        val t = db.tasks().get(id) ?: return
        db.tasks().upsert(t.copy(done = done, doneAt = if (done) System.currentTimeMillis() else null))
        scheduler.syncTask(id)
        refreshWidget()
    }

    suspend fun setTasksDone(ids: Collection<Long>, done: Boolean) { ids.forEach { setTaskDone(it, done) } }

    suspend fun reorderTasks(ids: List<Long>) {
        db.withTransaction { ids.forEachIndexed { i, id -> db.tasks().setOrder(id, i) } }
    }

    // ---------- journal ----------
    suspend fun saveJournal(e: JournalEntry, att: AttachmentChanges? = null): Long {
        val entry = e.copy(updatedAt = System.currentTimeMillis())
        val id = if (e.id == 0L) db.journal().upsert(entry) else { db.journal().upsert(entry); e.id }
        att?.let { commit(Owner.JOURNAL, id, it) }
        return id
    }

    suspend fun deleteJournal(id: Long) { purge(Owner.JOURNAL, id); db.journal().delete(id) }

    // ---------- categories / pomodoro ----------
    suspend fun saveCategory(c: Category) { db.categories().upsert(c) }
    suspend fun deleteCategory(id: Long) { db.habits().clearCategory(id); db.categories().delete(id) }
    suspend fun logSession(s: PomodoroSession) { db.pomodoro().insertSession(s) }
    suspend fun clearSessions() = db.pomodoro().clearSessions()
    suspend fun deletePreset(id: Long) = db.pomodoro().deletePreset(id)

    // ---------- import from the task editor's AI preview ----------
    private val importColors = listOf(0xFF7CB342, 0xFF9C6FE4, 0xFFD63DAA, 0xFF2FA37F, 0xFF3F8CF2, 0xFFF2A33A, 0xFFE0415F, 0xFF26A6C9)

    suspend fun importBundle(b: ImportBundle): ImportResult {
        var subCount = 0
        db.withTransaction {
            b.habits.forEachIndexed { i, p ->
                db.habits().upsert(Habit(name = p.name, icon = p.icon, colorHex = importColors[i % importColors.size], timeMinutes = p.timeMinutes,
                    daysMask = p.daysMask, notes = p.notes, sortOrder = db.placeHabitOrder(p.timeMinutes)))
            }
            val today = Dates.today()
            var tOrder = (db.tasks().maxTopOrder() ?: -1) + 1
            b.tasks.forEachIndexed { idx, p ->
                val pid = db.tasks().upsert(
                    TaskItem(title = p.title, priority = p.priority, estimateMinutes = p.estimateMinutes, parentId = b.parentTaskId,
                        sortOrder = if (b.parentTaskId == null) tOrder++ else idx,
                        dueEpochDay = if (b.parentTaskId == null) p.dueInDays?.let { today + it } else null)
                )
                p.subtasks.forEachIndexed { j, s ->
                    db.tasks().upsert(TaskItem(title = s.title, estimateMinutes = s.estimateMinutes, parentId = pid, sortOrder = j)); subCount++
                }
                if (b.parentTaskId != null) subCount++
            }
            b.presets.forEach { db.pomodoro().upsertPreset(PomodoroPreset(label = it.label, focusMin = it.focusMinutes, breakMin = it.breakMinutes, cycles = it.cycles)) }
        }
        scheduler.rescheduleAll()
        refreshWidget()
        return ImportResult(b.habits.size, if (b.parentTaskId == null) b.tasks.size else 0, subCount, b.presets.size)
    }
}
