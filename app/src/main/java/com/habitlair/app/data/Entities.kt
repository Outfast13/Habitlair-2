package com.habitlair.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.time.LocalDate

object Owner { const val HABIT = "habit"; const val TASK = "task"; const val JOURNAL = "journal" }
object MediaKind { const val PHOTO = "PHOTO"; const val AUDIO = "AUDIO"; const val VIDEO = "VIDEO"; const val FILE = "FILE" }
object IconKeys {
    val all = listOf(
        "check", "health", "study", "rest", "chat", "fitness", "book", "water",
        "sleep", "code", "run", "music", "heart", "food", "work", "star"
    )
}

@Serializable
@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorHex: Long = 0xFF7CB342
)

@Serializable
@Entity(tableName = "habits")
data class Habit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String = "check",
    val colorHex: Long = 0xFF7CB342,
    val categoryId: Long? = null,
    val timeMinutes: Int? = null,
    /** Bit 0 = Monday … bit 6 = Sunday. */
    val daysMask: Int = 127,
    val notes: String = "",
    val reminder: Boolean = true,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(
    tableName = "habit_completions",
    foreignKeys = [ForeignKey(entity = Habit::class, parentColumns = ["id"], childColumns = ["habitId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["habitId", "epochDay"], unique = true)]
)
data class HabitCompletion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val habitId: Long,
    val epochDay: Long
)

@Serializable
@Entity(tableName = "tasks")
data class TaskItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String = "",
    /** 0 low, 1 medium, 2 high */
    val priority: Int = 1,
    val dueEpochDay: Long? = null,
    val dueMinutes: Int? = null,
    /** Comma separated. */
    val tags: String = "",
    val done: Boolean = false,
    val doneAt: Long? = null,
    val habitId: Long? = null,
    val parentId: Long? = null,
    val estimateMinutes: Int? = null,
    val reminder: Boolean = true,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(tableName = "journal")
data class JournalEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val title: String = "",
    val body: String = "",
    val mood: Int = 3,
    val tags: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(tableName = "pomodoro_sessions")
data class PomodoroSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val minutes: Int,
    /** FOCUS, SHORT or LONG */
    val kind: String,
    val habitId: Long? = null,
    val taskId: Long? = null,
    val completed: Boolean = true
)

@Serializable
@Entity(tableName = "pomodoro_presets")
data class PomodoroPreset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val focusMin: Int = 25,
    val breakMin: Int = 5,
    val cycles: Int = 4
)

@Entity(tableName = "attachments", indices = [Index(value = ["ownerType", "ownerId"])])
data class Attachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerType: String,
    val ownerId: Long,
    val kind: String,
    val path: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class AttachmentChanges(val added: List<Attachment> = emptyList(), val removed: List<Attachment> = emptyList())

fun Habit.isScheduled(epochDay: Long): Boolean {
    // 1970-01-01 was a Thursday, so (epochDay + 3) mod 7 gives Monday = 0 without allocating a LocalDate.
    val bit = Math.floorMod(epochDay + 3, 7L).toInt()
    return (daysMask and (1 shl bit)) != 0
}

fun String.tagList(): List<String> = split(',').map { it.trim() }.filter { it.isNotEmpty() }

@Serializable
@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "user" or "assistant" */
    val role: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val proposalJson: String? = null,
    /** none, pending, applied, dismissed, undone */
    val proposalStatus: String = "none",
    val changeId: Long? = null
)

@Serializable
@Entity(tableName = "ai_changes")
data class AiChange(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val summary: String,
    val inverseJson: String,
    val undone: Boolean = false
)
