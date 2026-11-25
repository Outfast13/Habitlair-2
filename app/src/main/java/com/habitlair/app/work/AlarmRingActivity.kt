package com.habitlair.app.work

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.habitlair.app.HabitLairApp
import com.habitlair.app.data.AppSettings
import com.habitlair.app.data.Dates
import com.habitlair.app.data.Owner
import com.habitlair.app.data.ThemeMode
import com.habitlair.app.ui.HabitLairTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime

/** The screen that takes over when an alarm rings, even over the lock screen. */
class AlarmRingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        enableEdgeToEdge()

        val type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_TEST
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Alarm"
        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        val app = application as HabitLairApp
        val c = app.container

        setContent {
            HabitLairTheme(AppSettings(themeMode = ThemeMode.DARK, dynamicColor = false)) {
                AlarmScreen(
                    title = title, text = text, canComplete = type == Owner.HABIT || type == Owner.TASK,
                    onDismiss = { AlarmService.stop(this); finish() },
                    onSnooze = {
                        c.scope.launch {
                            val m = c.settings.current().snoozeMinutes
                            if (type != TYPE_TEST) c.scheduler.snooze(type, id, m) else c.scheduler.setAt(TYPE_TEST, 0, System.currentTimeMillis() + m * 60_000L)
                        }
                        AlarmService.stop(this); finish()
                    },
                    onDone = {
                        c.scope.launch {
                            if (type == Owner.HABIT) c.repo.setHabitDone(id, Dates.today(), true)
                            else if (type == Owner.TASK) c.repo.setTaskDone(id, true)
                        }
                        AlarmService.stop(this); finish()
                    },
                    snoozeMinutes = 10
                )
            }
        }
    }
}

@Composable
private fun AlarmScreen(
    title: String, text: String, canComplete: Boolean, snoozeMinutes: Int,
    onDismiss: () -> Unit, onSnooze: () -> Unit, onDone: () -> Unit
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val t = LocalTime.now()
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.9f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "scale"
    )
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Filled.Alarm, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp).scale(pulse))
            Spacer(Modifier.height(16.dp))
            Text(Dates.fmtTime(t.hour * 60 + t.minute) + if (now < 0) "" else "", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            if (text.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
            Spacer(Modifier.height(40.dp))
            if (canComplete) {
                Button(onDone, Modifier.fillMaxWidth().height(56.dp)) { Text("Done") }
                Spacer(Modifier.height(10.dp))
            }
            OutlinedButton(onSnooze, Modifier.fillMaxWidth().height(56.dp)) { Text("Snooze") }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onDismiss, Modifier.fillMaxWidth().height(56.dp)) { Text("Dismiss") }
        }
    }
}
