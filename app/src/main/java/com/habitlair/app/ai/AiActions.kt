package com.habitlair.app.ai

import androidx.room.withTransaction
import com.habitlair.app.data.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

sealed interface AiAction
data class CreateHabit(val plan: HabitPlan) : AiAction
data class UpdateHabit(
    val id: Long, val name: String?, val timeMinutes: Int?, val clearTime: Boolean,
    val daysMask: Int?, val icon: String?, val notes: String?
) : AiAction
data class DeleteHabit(val id: Long) : AiAction
data class CreateTask(val plan: TaskPlan) : AiAction
data class UpdateTask(
    val id: Long, val title: String?, val priority: Int?, val dueInDays: Int?, val clearDue: Boolean,
    val done: Boolean?, val notes: String?
) : AiAction
data class DeleteTask(val id: Long) : AiAction
data class CreatePreset(val plan: PomodoroPlan) : AiAction

data class Proposal(val summary: String, val actions: List<AiAction>)
data class ChatReply(val reply: String, val proposal: Proposal?, val applyPending: Boolean)

fun AiAction.describe(habits: Map<Long, String>, tasks: Map<Long, String>): String = when (this) {
    is CreateHabit -> "New habit: ${plan.name} (${Dates.daysLabel(plan.daysMask)}${plan.timeMinutes?.let { " at " + Dates.fmtTime(it) } ?: ""})"
    is UpdateHabit -> "Change habit \"${habits[id] ?: "#$id"}\":" + listOfNotNull(
        name?.let { " rename to $it" }, if (clearTime) " remove time" else timeMinutes?.let { " time ${Dates.fmtTime(it)}" },
        daysMask?.let { " days ${Dates.daysLabel(it)}" }, icon?.let { " icon $it" }, notes?.let { " notes" }
    ).joinToString(",")
    is DeleteHabit -> "Delete habit \"${habits[id] ?: "#$id"}\" and its history"
    is CreateTask -> "New task: ${plan.title}" + if (plan.subtasks.isNotEmpty()) " (${plan.subtasks.size} sub-tasks)" else ""
    is UpdateTask -> "Change task \"${tasks[id] ?: "#$id"}\":" + listOfNotNull(
        title?.let { " rename to $it" }, priority?.let { " priority ${listOf("low", "medium", "high")[it.coerceIn(0, 2)]}" },
        dueInDays?.let { " due in $it d" }, if (clearDue) " remove due date" else null, done?.let { if (it) " mark done" else " reopen" }, notes?.let { " notes" }
    ).joinToString(",")
    is DeleteTask -> "Delete task \"${tasks[id] ?: "#$id"}\""
    is CreatePreset -> "New Pomodoro preset: ${plan.label} (${plan.focusMinutes}/${plan.breakMinutes} × ${plan.cycles})"
}

@Serializable
sealed interface Inverse

@Serializable @SerialName("delete_habit")
data class InvDeleteHabit(val id: Long) : Inverse

@Serializable @SerialName("restore_habit")
data class InvRestoreHabit(val habit: Habit, val completions: List<HabitCompletion> = emptyList()) : Inverse

@Serializable @SerialName("delete_task")
data class InvDeleteTask(val id: Long) : Inverse

@Serializable @SerialName("restore_task")
data class InvRestoreTask(val task: TaskItem, val subs: List<TaskItem> = emptyList()) : Inverse

@Serializable @SerialName("delete_preset")
data class InvDeletePreset(val id: Long) : Inverse

data class ApplyResult(val changeId: Long?, val applied: Int, val skipped: List<String>)

/** Applies confirmed proposals and records exactly what is needed to undo them. */
class AiApplier(private val repo: Repository) {
    private val db = repo.db
    private val scheduler = repo.scheduler
    private val json = Json { classDiscriminator = "op"; ignoreUnknownKeys = true; encodeDefaults = true }
    private val colors = listOf(0xFF7CB342, 0xFF9C6FE4, 0xFFD63DAA, 0xFF2FA37F, 0xFF3F8CF2, 0xFFF2A33A, 0xFFE0415F, 0xFF26A6C9)

    suspend fun apply(p: Proposal, selected: Set<Int>): ApplyResult {
        val inverses = mutableListOf<Inverse>()
        val skipped = mutableListOf<String>()
        val habitsTouched = mutableSetOf<Long>()
        val tasksTouched = mutableSetOf<Long>()
        val removedHabits = mutableSetOf<Long>()
        val removedTasks = mutableSetOf<Long>()
        var applied = 0
        val today = Dates.today()

        db.withTransaction {
            p.actions.forEachIndexed { idx, a ->
                if (idx !in selected) return@forEachIndexed
                when (a) {
                    is CreateHabit -> {
                        val order = (db.habits().maxOrder() ?: -1) + 1
                        val id = db.habits().upsert(Habit(name = a.plan.name, icon = a.plan.icon, colorHex = colors[order % colors.size],
                            timeMinutes = a.plan.timeMinutes, daysMask = a.plan.daysMask, notes = a.plan.notes, sortOrder = order))
                        inverses += InvDeleteHabit(id); habitsTouched += id; applied++
                    }
                    is UpdateHabit -> {
                        val h = db.habits().get(a.id)
                        if (h == null) skipped += "Habit #${a.id} no longer exists" else {
                            inverses += InvRestoreHabit(h)
                            db.habits().upsert(h.copy(
                                name = a.name ?: h.name, timeMinutes = if (a.clearTime) null else (a.timeMinutes ?: h.timeMinutes),
                                daysMask = a.daysMask ?: h.daysMask, icon = a.icon ?: h.icon, notes = a.notes ?: h.notes))
                            habitsTouched += a.id; applied++
                        }
                    }
                    is DeleteHabit -> {
                        val h = db.habits().get(a.id)
                        if (h == null) skipped += "Habit #${a.id} no longer exists" else {
                            inverses += InvRestoreHabit(h, db.habits().allCompletions().filter { it.habitId == a.id })
                            db.tasks().unlinkHabit(a.id); db.habits().delete(a.id)
                            removedHabits += a.id; applied++
                        }
                    }
                    is CreateTask -> {
                        val order = (db.tasks().maxTopOrder() ?: -1) + 1
                        val id = db.tasks().upsert(TaskItem(title = a.plan.title, priority = a.plan.priority, estimateMinutes = a.plan.estimateMinutes,
                            dueEpochDay = a.plan.dueInDays?.let { today + it }, sortOrder = order))
                        a.plan.subtasks.forEachIndexed { i, s -> db.tasks().upsert(TaskItem(title = s.title, estimateMinutes = s.estimateMinutes, parentId = id, sortOrder = i)) }
                        inverses += InvDeleteTask(id); tasksTouched += id; applied++
                    }
                    is UpdateTask -> {
                        val t = db.tasks().get(a.id)
                        if (t == null) skipped += "Task #${a.id} no longer exists" else {
                            inverses += InvRestoreTask(t)
                            db.tasks().upsert(t.copy(
                                title = a.title ?: t.title, priority = a.priority ?: t.priority, notes = a.notes ?: t.notes,
                                dueEpochDay = if (a.clearDue) null else (a.dueInDays?.let { today + it } ?: t.dueEpochDay),
                                done = a.done ?: t.done, doneAt = if (a.done == true) System.currentTimeMillis() else if (a.done == false) null else t.doneAt))
                            tasksTouched += a.id; applied++
                        }
                    }
                    is DeleteTask -> {
                        val t = db.tasks().get(a.id)
                        if (t == null) skipped += "Task #${a.id} no longer exists" else {
                            inverses += InvRestoreTask(t, db.tasks().children(a.id))
                            db.tasks().deleteChildren(a.id); db.tasks().delete(a.id)
                            removedTasks += a.id; applied++
                        }
                    }
                    is CreatePreset -> {
                        val id = db.pomodoro().upsertPreset(PomodoroPreset(label = a.plan.label, focusMin = a.plan.focusMinutes, breakMin = a.plan.breakMinutes, cycles = a.plan.cycles))
                        inverses += InvDeletePreset(id); applied++
                    }
                }
            }
        }
        removedHabits.forEach { scheduler.cancel(Owner.HABIT, it) }
        removedTasks.forEach { scheduler.cancel(Owner.TASK, it) }
        habitsTouched.forEach { scheduler.syncHabit(it) }
        tasksTouched.forEach { scheduler.syncTask(it) }
        repo.refreshWidget()
        if (applied == 0) return ApplyResult(null, 0, skipped)
        val change = db.aiChanges().insert(AiChange(summary = p.summary, inverseJson = json.encodeToString(ListSerializer(Inverse.serializer()), inverses)))
        return ApplyResult(change, applied, skipped)
    }

    /** Restores the exact before-state of everything the change touched. */
    suspend fun undo(changeId: Long): Boolean {
        val ch = db.aiChanges().get(changeId) ?: return false
        if (ch.undone) return false
        val list = json.decodeFromString(ListSerializer(Inverse.serializer()), ch.inverseJson)
        db.withTransaction {
            list.asReversed().forEach { inv ->
                when (inv) {
                    is InvDeleteHabit -> { scheduler.cancel(Owner.HABIT, inv.id); db.habits().delete(inv.id) }
                    is InvRestoreHabit -> { db.habits().upsert(inv.habit); db.habits().insertCompletions(inv.completions) }
                    is InvDeleteTask -> { scheduler.cancel(Owner.TASK, inv.id); db.tasks().deleteChildren(inv.id); db.tasks().delete(inv.id) }
                    is InvRestoreTask -> { db.tasks().upsert(inv.task); inv.subs.forEach { db.tasks().upsert(it) } }
                    is InvDeletePreset -> db.pomodoro().deletePreset(inv.id)
                }
            }
            db.aiChanges().markUndone(changeId)
            db.chat().markUndone(changeId)
        }
        scheduler.rescheduleAll()
        repo.refreshWidget()
        return true
    }
}
