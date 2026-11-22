package com.habitlair.app.ai

/** Deterministic, on-device generators used when there is no API key or the AI call fails. */
object OfflineGenerator {
    private data class Tpl(val name: String, val icon: String, val offset: Int, val notes: String)

    private val topics: List<Pair<List<String>, List<Tpl>>> = listOf(
        listOf("exercise", "gym", "fit", "workout", "run", "weight", "muscle", "strength") to listOf(
            Tpl("Workout", "fitness", 0, "45 minutes of focused movement"),
            Tpl("Stretch and mobility", "run", 60, "10 relaxed minutes"),
            Tpl("Drink water", "water", 120, "Refill your bottle")
        ),
        listOf("study", "exam", "learn", "course", "school", "class", "homework", "language", "arabic") to listOf(
            Tpl("Focused study block", "study", 0, "One topic, phone away"),
            Tpl("Short break", "rest", 60, "Walk or stretch"),
            Tpl("Review notes", "study", 70, "Recall without looking, then check")
        ),
        listOf("read", "book") to listOf(Tpl("Read 20 pages", "book", 0, "Same chair, same time")),
        listOf("sleep", "rest", "energy", "tired", "insomnia") to listOf(
            Tpl("Wind-down routine", "sleep", 0, "Screens off, dim lights"),
            Tpl("Lights out", "sleep", 45, "Consistent bedtime")
        ),
        listOf("meditat", "mindful", "stress", "calm", "anxi", "relax") to listOf(Tpl("Meditate 10 minutes", "rest", 0, "Breathing or guided audio")),
        listOf("code", "program", "develop", "project", "work", "career", "business", "startup") to listOf(
            Tpl("Deep work block", "code", 0, "Hardest task first, notifications off"),
            Tpl("Plan tomorrow", "work", 240, "Pick the top 3 tasks")
        ),
        listOf("write", "journal", "blog") to listOf(Tpl("Write 200 words", "book", 0, "Quantity over polish")),
        listOf("diet", "eat", "food", "nutrition", "meal", "water", "hydrat") to listOf(
            Tpl("Drink water", "water", 0, "A glass on waking"),
            Tpl("Prep a healthy meal", "food", 300, "Veg and protein first")
        )
    )

    private fun explicitTime(g: String): Int? {
        val m = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)").find(g) ?: return null
        var h = m.groupValues[1].toInt()
        if (h !in 1..12) return null
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (m.groupValues[3] == "pm" && h < 12) h += 12
        if (m.groupValues[3] == "am" && h == 12) h = 0
        return h * 60 + min
    }

    fun routine(goal: String): RoutinePlan {
        val g = goal.lowercase()
        val base = explicitTime(g) ?: when {
            "evening" in g || "night" in g -> 19 * 60 + 30
            "afternoon" in g -> 15 * 60
            "lunch" in g -> 12 * 60 + 30
            else -> 7 * 60 + 30
        }
        val days = when {
            "weekday" in g -> 31
            "weekend" in g -> 96
            else -> 127
        }
        val matched = topics.filter { (keys, _) -> keys.any { it in g } }.flatMap { it.second }.distinctBy { it.name }.take(6)
        val tpls = matched.ifEmpty {
            listOf(
                Tpl("Work on: ${goal.trim().take(40)}", "star", 0, "One focused session"),
                Tpl("Quick review", "check", 90, "Note what moved forward")
            )
        }
        val habits = tpls.map { HabitPlan(it.name, (base + it.offset).coerceIn(0, 23 * 60 + 59), days, it.icon, it.notes) } +
            HabitPlan("Weekly review", 18 * 60, 64, "check", "What worked, what to change next week")
        val short = goal.trim().take(50)
        val tasks = listOf(
            TaskPlan(
                "Define success for: $short", 2, 1, 15,
                listOf(SubtaskPlan("Write the goal in one sentence", 5), SubtaskPlan("List 3 measurable milestones", 10))
            ),
            TaskPlan("Prepare tools and environment", 1, 2, 30, emptyList())
        )
        val focusy = listOf("study", "learn", "code", "work", "read", "write", "project", "exam").any { it in g }
        val pomo = if (focusy) listOf(PomodoroPlan("Deep focus", 25, 5, 4)) else listOf(PomodoroPlan("Light focus", 15, 3, 3))
        return RoutinePlan(habits, tasks, pomo)
    }

    fun breakDown(title: String, notes: String): List<SubtaskPlan> {
        val text = "$title. $notes".trim()
        val parts = text.split(Regex("[;\\n,]| and then | then | and |\\d+[.)]\\s"))
            .map { it.trim().trim('.', ' ') }.filter { it.length > 2 }.distinct()
        if (parts.size in 3..10) return parts.map { SubtaskPlan(it.replaceFirstChar(Char::uppercase).take(120), 20) }
        val t = title.trim().take(60)
        return listOf(
            SubtaskPlan("Clarify the outcome for \"$t\"", 10),
            SubtaskPlan("Gather what you need", 15),
            SubtaskPlan("Do a rough first pass", 30),
            SubtaskPlan("Refine and fill the gaps", 20),
            SubtaskPlan("Final check and finish", 10)
        )
    }

    data class HabitStat(val name: String, val current: Int, val best: Int, val rate: Int)
    data class Snapshot(val habits: List<HabitStat>, val avgMood: Double?, val moodCount: Int, val openTasks: Int, val overdue: Int)

    fun coach(s: Snapshot): CoachReport {
        val insights = mutableListOf<String>()
        val suggestions = mutableListOf<Suggestion>()
        val newHabits = mutableListOf<HabitPlan>()
        if (s.habits.isEmpty()) {
            return CoachReport(
                "You have no habits yet. Start with one small routine you can do in under five minutes.",
                emptyList(), listOf(Suggestion("Start tiny", "Pick one habit and anchor it to something you already do daily.")), emptyList()
            )
        }
        val avgRate = s.habits.map { it.rate }.average().toInt()
        s.habits.filter { it.current >= 7 }.maxByOrNull { it.current }?.let {
            insights += "Best streak right now: ${it.name} at ${it.current} days. Protect it."
        }
        val weak = s.habits.filter { it.rate < 50 }.sortedBy { it.rate }.take(3)
        weak.forEach {
            insights += "${it.name} was completed ${it.rate}% of scheduled days in the last two weeks."
            suggestions += Suggestion("Shrink \"${it.name}\"", "Make the minimum version take two minutes, or move it to a time you are reliably free.")
            newHabits += HabitPlan("Tiny: ${it.name}".take(60), null, 127, "check", "Two-minute version of ${it.name}")
        }
        s.avgMood?.let { m ->
            insights += "Average mood across ${s.moodCount} recent entries: ${"%.1f".format(m)} / 5."
            if (m < 3.0) suggestions += Suggestion("Add a recovery habit", "Low recent mood: a short walk or an early bedtime protects the rest of your routine.")
        }
        if (s.overdue > 0) suggestions += Suggestion("Clear overdue tasks", "${s.overdue} tasks are overdue. Reschedule or drop them so your list stays honest.")
        if (suggestions.isEmpty()) suggestions += Suggestion("Raise the bar slightly", "Things are steady. Add one small stretch goal to your strongest habit.")
        val summary = "Across ${s.habits.size} habits your recent completion rate is about $avgRate%. " +
            "${s.openTasks} tasks are open${if (s.overdue > 0) ", ${s.overdue} overdue" else ""}."
        return CoachReport(summary, insights, suggestions, newHabits)
    }
}
