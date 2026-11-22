package com.habitlair.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY name COLLATE NOCASE") fun observe(): Flow<List<Category>>
    @Query("SELECT * FROM categories") suspend fun all(): List<Category>
    @Upsert suspend fun upsert(c: Category): Long
    @Insert suspend fun insertAll(l: List<Category>)
    @Query("DELETE FROM categories WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM categories") suspend fun clear()
}

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits WHERE archived = 0 ORDER BY sortOrder, id")
    fun observe(): Flow<List<Habit>>
    @Query("SELECT * FROM habits") suspend fun all(): List<Habit>
    @Query("SELECT MAX(sortOrder) FROM habits") suspend fun maxOrder(): Int?
    @Query("UPDATE habits SET sortOrder = :o WHERE id = :id") suspend fun setOrder(id: Long, o: Int)
    @Query("SELECT * FROM habits WHERE id = :id") suspend fun get(id: Long): Habit?
    @Upsert suspend fun upsert(h: Habit): Long
    @Insert suspend fun insertAll(l: List<Habit>)
    @Query("DELETE FROM habits WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM habits") suspend fun clear()
    @Query("UPDATE habits SET categoryId = NULL WHERE categoryId = :cid") suspend fun clearCategory(cid: Long)

    @Query("SELECT * FROM habit_completions") fun observeCompletions(): Flow<List<HabitCompletion>>
    @Query("SELECT * FROM habit_completions") suspend fun allCompletions(): List<HabitCompletion>
    @Query("SELECT habitId FROM habit_completions WHERE epochDay = :d") suspend fun doneIds(d: Long): List<Long>
    @Query("SELECT COUNT(*) FROM habit_completions WHERE habitId = :h AND epochDay = :d") suspend fun isDone(h: Long, d: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addCompletion(c: HabitCompletion): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertCompletions(l: List<HabitCompletion>)
    @Query("DELETE FROM habit_completions WHERE habitId = :h AND epochDay = :d") suspend fun removeCompletion(h: Long, d: Long)
    @Query("DELETE FROM habit_completions") suspend fun clearCompletions()
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks") fun observe(): Flow<List<TaskItem>>
    @Query("SELECT * FROM tasks") suspend fun all(): List<TaskItem>
    @Query("SELECT MAX(sortOrder) FROM tasks WHERE parentId IS NULL") suspend fun maxTopOrder(): Int?
    @Query("UPDATE tasks SET sortOrder = :o WHERE id = :id") suspend fun setOrder(id: Long, o: Int)
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun get(id: Long): TaskItem?
    @Query("SELECT * FROM tasks WHERE parentId = :id") suspend fun children(id: Long): List<TaskItem>
    @Upsert suspend fun upsert(t: TaskItem): Long
    @Insert suspend fun insertAll(l: List<TaskItem>)
    @Query("DELETE FROM tasks WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM tasks WHERE parentId = :id") suspend fun deleteChildren(id: Long)
    @Query("UPDATE tasks SET habitId = NULL WHERE habitId = :habitId") suspend fun unlinkHabit(habitId: Long)
    @Query("DELETE FROM tasks") suspend fun clear()
}

@Dao
interface JournalDao {
    @Query("SELECT * FROM journal ORDER BY epochDay DESC, createdAt DESC") fun observe(): Flow<List<JournalEntry>>
    @Query("SELECT * FROM journal ORDER BY epochDay DESC, createdAt DESC") suspend fun all(): List<JournalEntry>
    @Upsert suspend fun upsert(e: JournalEntry): Long
    @Insert suspend fun insertAll(l: List<JournalEntry>)
    @Query("DELETE FROM journal WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM journal") suspend fun clear()
}

@Dao
interface PomodoroDao {
    @Query("SELECT * FROM pomodoro_sessions ORDER BY startedAt DESC") fun observeSessions(): Flow<List<PomodoroSession>>
    @Query("SELECT * FROM pomodoro_sessions") suspend fun allSessions(): List<PomodoroSession>
    @Insert suspend fun insertSession(s: PomodoroSession): Long
    @Insert suspend fun insertSessions(l: List<PomodoroSession>)
    @Query("UPDATE pomodoro_sessions SET habitId = NULL WHERE habitId = :id") suspend fun unlinkHabit(id: Long)
    @Query("UPDATE pomodoro_sessions SET taskId = NULL WHERE taskId = :id") suspend fun unlinkTask(id: Long)
    @Query("DELETE FROM pomodoro_sessions") suspend fun clearSessions()

    @Query("SELECT * FROM pomodoro_presets ORDER BY id DESC") fun observePresets(): Flow<List<PomodoroPreset>>
    @Query("SELECT * FROM pomodoro_presets") suspend fun allPresets(): List<PomodoroPreset>
    @Upsert suspend fun upsertPreset(p: PomodoroPreset): Long
    @Insert suspend fun insertPresets(l: List<PomodoroPreset>)
    @Query("DELETE FROM pomodoro_presets WHERE id = :id") suspend fun deletePreset(id: Long)
    @Query("DELETE FROM pomodoro_presets") suspend fun clearPresets()
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE ownerType = :type AND ownerId = :id ORDER BY id") suspend fun forOwner(type: String, id: Long): List<Attachment>
    @Query("SELECT * FROM attachments") suspend fun all(): List<Attachment>
    @Insert suspend fun insert(a: Attachment): Long
    @Query("DELETE FROM attachments WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM attachments WHERE ownerType = :type AND ownerId = :id") suspend fun deleteForOwner(type: String, id: Long)
    @Query("DELETE FROM attachments") suspend fun clear()
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages ORDER BY id") fun observe(): Flow<List<ChatMessage>>
    @Query("SELECT * FROM chat_messages ORDER BY id") suspend fun all(): List<ChatMessage>
    @Query("SELECT * FROM chat_messages WHERE id = :id") suspend fun get(id: Long): ChatMessage?
    @Query("SELECT * FROM chat_messages WHERE proposalStatus = 'pending' ORDER BY id DESC LIMIT 1") suspend fun pending(): ChatMessage?
    @Insert suspend fun insert(m: ChatMessage): Long
    @Insert suspend fun insertAll(l: List<ChatMessage>)
    @Query("UPDATE chat_messages SET proposalStatus = :s, changeId = :c WHERE id = :id") suspend fun setStatus(id: Long, s: String, c: Long?)
    @Query("UPDATE chat_messages SET proposalStatus = 'dismissed' WHERE proposalStatus = 'pending'") suspend fun dismissPending()
    @Query("UPDATE chat_messages SET proposalStatus = 'undone' WHERE changeId = :cid") suspend fun markUndone(cid: Long)
    @Query("DELETE FROM chat_messages") suspend fun clear()
}

@Dao
interface AiChangeDao {
    @Query("SELECT * FROM ai_changes ORDER BY id DESC") fun observe(): Flow<List<AiChange>>
    @Query("SELECT * FROM ai_changes") suspend fun all(): List<AiChange>
    @Query("SELECT * FROM ai_changes WHERE id = :id") suspend fun get(id: Long): AiChange?
    @Insert suspend fun insert(c: AiChange): Long
    @Insert suspend fun insertAll(l: List<AiChange>)
    @Query("UPDATE ai_changes SET undone = 1 WHERE id = :id") suspend fun markUndone(id: Long)
    @Query("DELETE FROM ai_changes") suspend fun clear()
}

@Database(
    entities = [Category::class, Habit::class, HabitCompletion::class, TaskItem::class, JournalEntry::class,
        PomodoroSession::class, PomodoroPreset::class, Attachment::class, ChatMessage::class, AiChange::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categories(): CategoryDao
    abstract fun habits(): HabitDao
    abstract fun tasks(): TaskDao
    abstract fun journal(): JournalDao
    abstract fun pomodoro(): PomodoroDao
    abstract fun attachments(): AttachmentDao
    abstract fun chat(): ChatDao
    abstract fun aiChanges(): AiChangeDao

    companion object {
        fun build(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx, AppDatabase::class.java, "habitlair.db").addMigrations(MIGRATION_1_2).build()
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE habits ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tasks ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
        // keep the existing visual order: by creation (id)
        db.execSQL("UPDATE habits SET sortOrder = id")
        db.execSQL("UPDATE tasks SET sortOrder = id")
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL, proposalJson TEXT, proposalStatus TEXT NOT NULL, changeId INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS ai_changes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, createdAt INTEGER NOT NULL, summary TEXT NOT NULL, inverseJson TEXT NOT NULL, undone INTEGER NOT NULL)")
    }
}
