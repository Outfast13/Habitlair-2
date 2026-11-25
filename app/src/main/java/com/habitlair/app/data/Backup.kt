package com.habitlair.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.habitlair.app.widget.updateAllWidgets
import com.habitlair.app.work.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

const val BACKUP_VERSION = 2

@Serializable
data class AttachmentBackup(val ownerType: String, val ownerId: Long, val kind: String, val name: String, val createdAt: Long, val file: String)

@Serializable
data class BackupFile(
    val app: String = "HabitLair",
    val version: Int = BACKUP_VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val settings: AppSettings = AppSettings(),
    val apiKeys: Map<Provider, String> = emptyMap(),
    val categories: List<Category> = emptyList(),
    val habits: List<Habit> = emptyList(),
    val completions: List<HabitCompletion> = emptyList(),
    val tasks: List<TaskItem> = emptyList(),
    val journal: List<JournalEntry> = emptyList(),
    val sessions: List<PomodoroSession> = emptyList(),
    val presets: List<PomodoroPreset> = emptyList(),
    val attachments: List<AttachmentBackup> = emptyList(),
    val chat: List<ChatMessage> = emptyList(),
    val aiChanges: List<AiChange> = emptyList()
)

class PendingBackup(val file: File, val backup: BackupFile, val isZip: Boolean)

/** Zip layout: backup.json + media/<id>_<name> for every attachment file. */
class BackupManager(
    private val ctx: Context,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val secure: SecureKeyStore,
    private val scheduler: ReminderScheduler,
    private val media: MediaFiles
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true; coerceInputValues = true }

    data class ExportSummary(val mediaFiles: Int, val keysIncluded: Boolean)

    suspend fun export(out: OutputStream, includeKeys: Boolean): ExportSummary = withContext(Dispatchers.IO) {
        val files = mutableListOf<Pair<String, File>>()
        val atts = db.attachments().all().mapNotNull { a ->
            val f = File(a.path)
            if (!f.exists()) null else {
                val entry = "media/${a.id}_${f.name}"
                files += entry to f
                AttachmentBackup(a.ownerType, a.ownerId, a.kind, a.name, a.createdAt, entry)
            }
        }
        val keys = if (includeKeys) Provider.entries.associateWith { secure.get(it) }.filterValues { it.isNotBlank() } else emptyMap()
        val backup = BackupFile(
            settings = settings.current(), apiKeys = keys,
            categories = db.categories().all(), habits = db.habits().all(), completions = db.habits().allCompletions(),
            tasks = db.tasks().all(), journal = db.journal().all(), sessions = db.pomodoro().allSessions(),
            presets = db.pomodoro().allPresets(), attachments = atts, chat = db.chat().all(), aiChanges = db.aiChanges().all()
        )
        ZipOutputStream(BufferedOutputStream(out)).use { z ->
            z.putNextEntry(ZipEntry("backup.json"))
            z.write(json.encodeToString(BackupFile.serializer(), backup).toByteArray())
            z.closeEntry()
            files.forEach { (entry, f) ->
                z.putNextEntry(ZipEntry(entry))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        ExportSummary(files.size, keys.isNotEmpty())
    }

    /** Copies the chosen file to cache, validates it, and returns a summary for the confirmation dialog. */
    suspend fun inspect(uri: Uri): PendingBackup = withContext(Dispatchers.IO) {
        val cache = File(ctx.cacheDir, "import_${System.currentTimeMillis()}.tmp")
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Couldn't open that file.")
        input.use { i -> cache.outputStream().use { o -> i.copyTo(o) } }
        try {
            val head = ByteArray(2)
            val n = cache.inputStream().use { it.read(head) }
            val zip = n == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            val text = if (zip) {
                ZipFile(cache).use { z ->
                    val e = z.getEntry("backup.json") ?: throw IllegalArgumentException("This zip has no backup.json, so it isn't a HabitLair backup.")
                    z.getInputStream(e).bufferedReader().use { it.readText() }
                }
            } else cache.readText()
            PendingBackup(cache, parse(text), zip)
        } catch (e: Exception) {
            cache.delete()
            throw if (e is IllegalArgumentException) e else IllegalArgumentException("Couldn't read that backup: ${e.message}")
        }
    }

    fun discard(p: PendingBackup) { p.file.delete() }

    private fun parse(text: String): BackupFile {
        val b = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("This isn't a valid HabitLair backup (${e.message?.lineSequence()?.firstOrNull()?.take(140)}).")
        }
        require(b.app == "HabitLair") { "This file wasn't created by HabitLair." }
        require(b.version in 1..BACKUP_VERSION) { "Backup version ${b.version} isn't supported by this app version." }
        require(b.habits.map { it.id }.toSet().size == b.habits.size) { "Backup contains duplicate habit IDs." }
        require(b.tasks.map { it.id }.toSet().size == b.tasks.size) { "Backup contains duplicate task IDs." }
        val habitIds = b.habits.map { it.id }.toSet()
        return b.copy(completions = b.completions.filter { it.habitId in habitIds })
    }

    suspend fun restore(p: PendingBackup) = withContext(Dispatchers.IO) {
        val b = p.backup
        db.habits().all().forEach { scheduler.cancel(Owner.HABIT, it.id) }
        db.tasks().all().forEach { scheduler.cancel(Owner.TASK, it.id) }
        db.attachments().all().forEach { File(it.path).delete() }

        val restored = mutableListOf<Attachment>()
        if (p.isZip) ZipFile(p.file).use { z ->
            b.attachments.forEach { a ->
                val e = z.getEntry(a.file) ?: return@forEach
                val dest = media.newFile(a.file.substringAfterLast('.', "bin"))
                z.getInputStream(e).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                restored += Attachment(ownerType = a.ownerType, ownerId = a.ownerId, kind = a.kind, path = dest.absolutePath, name = a.name, createdAt = a.createdAt)
            }
        }
        db.withTransaction {
            db.attachments().clear(); db.chat().clear(); db.aiChanges().clear()
            db.habits().clearCompletions(); db.habits().clear(); db.tasks().clear(); db.journal().clear()
            db.pomodoro().clearSessions(); db.pomodoro().clearPresets(); db.categories().clear()
            db.categories().insertAll(b.categories); db.habits().insertAll(b.habits); db.habits().insertCompletions(b.completions)
            db.tasks().insertAll(b.tasks); db.journal().insertAll(b.journal)
            db.pomodoro().insertSessions(b.sessions); db.pomodoro().insertPresets(b.presets)
            db.chat().insertAll(b.chat); db.aiChanges().insertAll(b.aiChanges)
            restored.forEach { db.attachments().insert(it) }
        }
        settings.replace(b.settings)
        b.apiKeys.forEach { (prov, key) -> secure.set(prov, key) }
        scheduler.rescheduleAll()
        updateAllWidgets(ctx)
        p.file.delete()
    }
}
