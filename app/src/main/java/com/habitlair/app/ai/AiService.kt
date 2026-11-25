package com.habitlair.app.ai

import com.habitlair.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AiOutcome<T>(val value: T, val source: String, val note: String?, val offline: Boolean)

class AiService(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val secure: SecureKeyStore,
    private val client: AiClient
) {
    fun configFrom(s: AppSettings): AiConfig =
        AiConfig(s.provider, s.baseUrl, s.modelFor(s.provider), secure.get(s.provider), s.temperature)

    /** Null config means AI can't be used (no key). */
    private suspend fun activeConfig(): Pair<AppSettings, AiConfig?> = withContext(Dispatchers.IO) {
        val s = settings.current()
        val c = configFrom(s)   // reads the encrypted key store, so keep it off the main thread
        s to if (c.apiKey.isBlank() && c.provider != Provider.CUSTOM) null else c
    }

    suspend fun isConfigured(): Boolean = activeConfig().second != null

    suspend fun status(): String {
        val (s, c) = activeConfig()
        return if (c == null) "No API key for ${s.provider.label}: replies are built on-device (add a key in Settings for real conversation)."
        else "${s.provider.label} · ${c.model}"
    }

    suspend fun test(cfg: AiConfig): AiResult =
        client.chat(cfg, "You are a connectivity probe.", "Reply with the single word: OK")

    /** Tiny prompt improver used by the sparkle button in the chat box. */
    suspend fun rewritePrompt(text: String): String {
        val (_, cfg) = activeConfig()
            ?: throw AiException(AiException.Kind.NO_KEY, null, "Add an API key in Settings to use AI rewrite.")
        cfg ?: throw AiException(AiException.Kind.NO_KEY, null, "Add an API key in Settings to use AI rewrite.")
        val r = client.chat(cfg.copy(temperature = 0.4f), REWRITE_SYSTEM, text.trim())
        return r.text.trim().removeSurrounding("\"").trim()
    }

    suspend fun breakDown(title: String, notes: String): AiOutcome<List<SubtaskPlan>> {
        val (s, cfg) = activeConfig()
        if (cfg == null) return AiOutcome(OfflineGenerator.breakDown(title, notes), "On-device generator", "No API key set, so the on-device generator was used.", true)
        return try {
            val sys = s.systemPrompt.ifBlank { DEFAULT_PROMPT } + "\n\nRespond with ONLY one JSON object, no prose, no fences: " +
                """{"subtasks":[{"title":"string","estimateMinutes":15}]}"""
            val r = client.chat(cfg, sys, "Break this task into 3-10 ordered sub-tasks with minute estimates.\nTask: $title\nNotes: $notes", json = true)
            AiOutcome(AiParser.breakdown(r.text), "${s.provider.label} · ${r.model}", null, false)
        } catch (e: AiException) {
            AiOutcome(OfflineGenerator.breakDown(title, notes), "On-device generator", "AI request failed (${e.describe()}). Used the on-device generator.", true)
        } catch (e: AiParseException) {
            AiOutcome(OfflineGenerator.breakDown(title, notes), "On-device generator", "The AI reply couldn't be parsed. Used the on-device generator.", true)
        }
    }

    private suspend fun contextBlock(): String {
        val today = Dates.today()
        val habits = db.habits().all().filter { !it.archived }
        val done = db.habits().allCompletions().groupBy { it.habitId }.mapValues { m -> m.value.map { it.epochDay }.toSet() }
        val tasks = db.tasks().all().filter { !it.done && it.parentId == null }.take(25)
        val journal = db.journal().all().take(5)
        return buildString {
            appendLine("USER DATA (today is ${Dates.fmtDate(today)}). Use the ids when you propose update/delete actions.")
            appendLine("Habits:")
            if (habits.isEmpty()) appendLine("- (none)")
            habits.forEach {
                val d = done[it.id] ?: emptySet()
                val st = Streaks.compute(it, d, today)
                appendLine("- id=${it.id} \"${it.name}\" ${Dates.daysLabel(it.daysMask)}${it.timeMinutes?.let { t -> " at " + Dates.fmtTime(t) } ?: ""} streak=${st.current} best=${st.best} rate30=${st.rate30}% doneToday=${today in d}")
            }
            appendLine("Open tasks:")
            if (tasks.isEmpty()) appendLine("- (none)")
            tasks.forEach { appendLine("- id=${it.id} \"${it.title}\" priority=${it.priority}${it.dueEpochDay?.let { d -> " due " + Dates.fmtDate(d) } ?: ""}") }
            appendLine("Recent journal:")
            if (journal.isEmpty()) appendLine("- (none)")
            journal.forEach { appendLine("- ${Dates.fmtDate(it.epochDay)} mood=${it.mood}/5: ${(it.title + " " + it.body).trim().take(160).replace('\n', ' ')}") }
        }
    }

    /**
     * One conversational turn. [history] must NOT include the new [userText].
     * [pending] is the proposal awaiting confirmation (JSON), if any.
     */
    suspend fun chat(history: List<ChatMessage>, userText: String, pending: String?): AiOutcome<ChatReply> {
        val (s, cfg) = activeConfig()
        if (cfg == null) return offlineChat(userText, "No API key set, so I'm answering on-device.")
        return try {
            val sys = buildString {
                append(s.systemPrompt.ifBlank { DEFAULT_PROMPT })
                append("\n\n").append(CHAT_RULES)
                if (s.shareContext) append("\n\n").append(contextBlock())
                else append("\n\nThe user has turned context sharing off, so you cannot see their habits or tasks. Only propose create_* actions and say so if they ask about their data.")
                if (pending != null) append("\n\nPENDING PROPOSAL (shown to the user, not yet applied):\n").append(pending)
            }
            val turns = history.takeLast(14).map { ChatTurn(it.role, it.text) } + ChatTurn("user", userText)
            val r = client.chatTurns(cfg, sys, turns, json = true)
            AiOutcome(AiParser.chat(r.text), "${s.provider.label} · ${r.model}", null, false)
        } catch (e: AiException) {
            offlineChat(userText, "AI request failed (${e.describe()}), so I'm answering on-device.")
        }
    }

    private suspend fun offlineChat(text: String, note: String): AiOutcome<ChatReply> {
        val low = text.lowercase()
        val reply: ChatReply = when {
            Regex("break ?down|split (this|it|up)|sub-?tasks?|steps (for|to)").containsMatchIn(low) -> {
                val title = text.replace(Regex("(?i)^\\s*(please\\s+)?(break ?down|split)( up)?( this| the| my)?( task)?\\s*:?\\s*"), "").trim().ifBlank { text.trim() }
                val subs = OfflineGenerator.breakDown(title, "")
                ChatReply("Here's a first split of \"${title.take(60)}\". Tick what you want and tap Apply, or tell me what to change.",
                    Proposal("Break down: ${title.take(50)}", listOf(CreateTask(TaskPlan(title.take(120), 1, null, null, subs)))), false)
            }
            Regex("review|progress|streak|how am i|how'?s my").containsMatchIn(low) -> {
                val rep = OfflineGenerator.coach(snapshot())
                val body = buildString {
                    append(rep.summary)
                    rep.insights.forEach { append("\n• ").append(it) }
                    rep.suggestions.forEach { append("\n→ ").append(it.title).append(if (it.detail.isBlank()) "" else ": " + it.detail) }
                }
                ChatReply(body, rep.newHabits.takeIf { it.isNotEmpty() }?.let { Proposal("Add small versions of struggling habits", it.map { h -> CreateHabit(h) }) }, false)
            }
            text.trim().length >= 12 -> {
                val plan = OfflineGenerator.routine(text.trim())
                val actions = plan.habits.map { CreateHabit(it) } + plan.tasks.map { CreateTask(it) } + plan.pomodoro.map { CreatePreset(it) }
                ChatReply("Here's a starter plan I can set up from what you wrote. Tick what you want and tap Apply, or tell me what to change.",
                    Proposal("Starter routine: ${text.trim().take(40)}", actions), false)
            }
            else -> ChatReply("Tell me about your goal or current routine and I'll suggest a plan. You can also say \"break down: <task>\" or \"review my progress\".", null, false)
        }
        return AiOutcome(reply, "On-device", note, true)
    }

    private suspend fun snapshot(): OfflineGenerator.Snapshot {
        val today = Dates.today()
        val done = db.habits().allCompletions().groupBy { it.habitId }.mapValues { m -> m.value.map { it.epochDay }.toSet() }
        val stats = db.habits().all().filter { !it.archived }.map { h ->
            val d = done[h.id] ?: emptySet()
            val st = Streaks.compute(h, d, today)
            var sched = 0; var hit = 0
            for (x in today - 13..today) if (h.isScheduled(x)) { sched++; if (x in d) hit++ }
            OfflineGenerator.HabitStat(h.name, st.current, st.best, if (sched == 0) 100 else hit * 100 / sched)
        }
        val moods = db.journal().all().take(10).map { it.mood }
        val tasks = db.tasks().all().filter { !it.done && it.parentId == null }
        return OfflineGenerator.Snapshot(stats, moods.takeIf { it.isNotEmpty() }?.average(), moods.size, tasks.size,
            tasks.count { (it.dueEpochDay ?: Long.MAX_VALUE) < today })
    }

    private companion object {
        const val REWRITE_SYSTEM =
            "You improve a user's message to an AI habit and productivity coach. Rewrite it so it is clearer, more specific and better organised " +
                "while keeping the user's meaning, voice and every fact they gave. Do not invent details. Keep it under 120 words. " +
                "Output ONLY the rewritten message: no preface, no quotes, no explanation."

        const val HABIT_SHAPE = """{"name":"string","time":"HH:mm 24h or omit","days":["MON","TUE","WED","THU","FRI","SAT","SUN"],"icon":"one of check,health,study,rest,chat,fitness,book,water,sleep,code,run,music,heart,food,work,star","notes":"string"}"""

        val CHAT_RULES = """
HOW TO BEHAVE
You are in a chat with the user inside the HabitLair app. Hold a real conversation.
- When the user describes a routine, goal or problem, respond like a coach: give honest feedback, point out what may not work (too many habits, unrealistic times, conflicts), suggest better alternatives, and ask at most one short clarifying question when you genuinely need it.
- NEVER create or change anything on your own. Only when you have a concrete plan the user seems happy with, include a "proposal". The app shows it to the user with an Apply button; nothing changes until they confirm.
- If the user clearly confirms the PENDING PROPOSAL in their message (e.g. "yes", "do it", "go ahead"), set "apply_pending": true and write a short reply. If they ask for changes, send a new full proposal instead.
- Keep replies concise (a few short paragraphs or bullets). Plain text, no markdown headings.
- When no data change is needed, set "proposal" to null.

OUTPUT FORMAT: respond with ONLY one JSON object, no prose outside it, no markdown fences:
{"reply":"string","proposal":null,"apply_pending":false}
where proposal, when present, is:
{"summary":"short title","actions":[ ...up to 20 actions... ]}
and each action is one of:
{"op":"create_habit","name":"string","time":"HH:mm","days":["MON","TUE"],"icon":"check","notes":"string"}
{"op":"update_habit","id":123,"name":"optional","time":"HH:mm or none","days":["MON"],"icon":"optional","notes":"optional"}
{"op":"delete_habit","id":123}
{"op":"create_task","title":"string","priority":"LOW|MEDIUM|HIGH","dueInDays":0,"estimateMinutes":30,"subtasks":[{"title":"string","estimateMinutes":15}]}
{"op":"update_task","id":123,"title":"optional","priority":"optional","dueInDays":3,"clearDue":false,"done":false,"notes":"optional"}
{"op":"delete_task","id":123}
{"op":"create_pomodoro","label":"string","focusMinutes":25,"breakMinutes":5,"cycles":4}
Habit shape reference: $HABIT_SHAPE
Only use ids that appear in USER DATA. Prefer small realistic routines.
""".trimIndent()
    }
}
