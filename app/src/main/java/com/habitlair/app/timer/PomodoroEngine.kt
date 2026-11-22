package com.habitlair.app.timer

import com.habitlair.app.data.*
import com.habitlair.app.work.Notifier
import com.habitlair.app.work.ReminderScheduler
import com.habitlair.app.work.TYPE_POMODORO
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class Phase(val label: String, val kind: String) {
    FOCUS("Focus", "FOCUS"), SHORT("Short break", "SHORT"), LONG("Long break", "LONG")
}

data class TimerState(
    val phase: Phase = Phase.FOCUS,
    val running: Boolean = false,
    val remainingMs: Long = 25 * 60_000L,
    val totalMs: Long = 25 * 60_000L,
    val focusDone: Int = 0,
    val habitId: Long? = null,
    val taskId: Long? = null
)

/** App-scoped so the timer keeps running while you navigate between screens. */
class PomodoroEngine(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val repo: Repository,
    private val settings: SettingsStore,
    private val scheduler: ReminderScheduler
) {
    private val _state = MutableStateFlow(TimerState())
    val state: StateFlow<TimerState> = _state.asStateFlow()
    private var cfg = AppSettings()
    private var endAt = 0L
    private var job: Job? = null

    init {
        scope.launch {
            settings.flow.collect {
                cfg = it
                val s = _state.value
                if (!s.running && s.remainingMs == s.totalMs) {
                    val t = lengthFor(s.phase)
                    _state.value = s.copy(remainingMs = t, totalMs = t)
                }
            }
        }
    }

    private fun lengthFor(p: Phase): Long = 60_000L * when (p) {
        Phase.FOCUS -> cfg.focusMin
        Phase.SHORT -> cfg.breakMin
        Phase.LONG -> cfg.longBreakMin
    }

    fun setLink(habitId: Long?, taskId: Long?) { _state.update { it.copy(habitId = habitId, taskId = taskId) } }

    fun start() {
        val s = _state.value
        if (s.running) return
        endAt = System.currentTimeMillis() + s.remainingMs
        _state.value = s.copy(running = true)
        scheduler.setAt(TYPE_POMODORO, 0, endAt)
        job = scope.launch {
            while (isActive) {
                val rem = endAt - System.currentTimeMillis()
                if (rem <= 0) { finish(); break }
                _state.update { it.copy(remainingMs = rem) }
                delay(250)
            }
        }
    }

    fun pause() {
        if (!_state.value.running) return
        job?.cancel()
        scheduler.cancel(TYPE_POMODORO, 0)
        _state.update { it.copy(running = false, remainingMs = (endAt - System.currentTimeMillis()).coerceAtLeast(0)) }
    }

    fun reset() {
        job?.cancel()
        scheduler.cancel(TYPE_POMODORO, 0)
        val t = lengthFor(_state.value.phase)
        _state.update { it.copy(running = false, remainingMs = t, totalMs = t) }
    }

    fun skip() {
        val s = _state.value
        job?.cancel()
        scheduler.cancel(TYPE_POMODORO, 0)
        val elapsedMin = ((s.totalMs - s.remainingMs) / 60_000).toInt()
        if (s.phase == Phase.FOCUS && elapsedMin >= 1) log(s, elapsedMin, false)
        advance(completedFocus = false)
    }

    private suspend fun finish() {
        val s = _state.value
        scheduler.cancel(TYPE_POMODORO, 0)
        log(s, (s.totalMs / 60_000).toInt(), true)
        Notifier.show(ctx, cfg, scheduler.notifId(TYPE_POMODORO, 0), "${s.phase.label} finished",
            if (s.phase == Phase.FOCUS) "Nice work. Time for a break." else "Break's over. Ready to focus?")
        advance(completedFocus = s.phase == Phase.FOCUS)
    }

    private fun log(s: TimerState, minutes: Int, completed: Boolean) {
        scope.launch {
            repo.logSession(PomodoroSession(startedAt = System.currentTimeMillis() - minutes * 60_000L, minutes = minutes,
                kind = s.phase.kind, habitId = s.habitId, taskId = s.taskId, completed = completed))
        }
    }

    private fun advance(completedFocus: Boolean) {
        val s = _state.value
        var done = s.focusDone
        val next = if (s.phase == Phase.FOCUS) {
            if (completedFocus) { done++; if (done % cfg.cyclesBeforeLong.coerceAtLeast(1) == 0) Phase.LONG else Phase.SHORT } else Phase.SHORT
        } else Phase.FOCUS
        val t = lengthFor(next)
        _state.value = s.copy(phase = next, running = false, remainingMs = t, totalMs = t, focusDone = done)
    }
}
