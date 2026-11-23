package com.habitlair.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.habitlair.app.ai.AiClient
import com.habitlair.app.ai.AiApplier
import com.habitlair.app.ai.AiService
import com.habitlair.app.data.*
import com.habitlair.app.timer.PomodoroEngine
import com.habitlair.app.work.MaintenanceWorker
import com.habitlair.app.work.Notifier
import com.habitlair.app.work.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class AppContainer(app: Application) {
    val appContext: android.content.Context = app
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.build(app)
    val settings = SettingsStore(app)
    val secure by lazy { SecureKeyStore(app) }
    val media = MediaFiles(app)
    val scheduler = ReminderScheduler(app, db, settings)
    val repo = Repository(app, db, settings, scheduler, media)
    val ai by lazy { AiService(db, settings, secure, AiClient()) }
    val pomodoro = PomodoroEngine(app, scope, repo, settings, scheduler)
    val applier by lazy { AiApplier(repo) }
    /** Text handed from other screens (e.g. Journal) to the AI chat input. */
    val chatPrompt = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val backup by lazy { BackupManager(app, db, settings, secure, scheduler, media) }
}

class HabitLairApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifier.ensureChannels(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "maintenance", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MaintenanceWorker>(6, TimeUnit.HOURS).build()
        )
        container.scope.launch(Dispatchers.IO) { runCatching { container.secure } }   // warm up the Keystore off the main thread
        container.scope.launch { container.scheduler.rescheduleAll() }
    }
}
