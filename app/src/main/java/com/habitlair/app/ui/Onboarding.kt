@file:OptIn(ExperimentalMaterial3Api::class)

package com.habitlair.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.habitlair.app.work.Notifier

private fun micGranted(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** First-run screen: explains each permission and lets the person allow it or skip it. Nothing is forced. */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalContainer.current
    var notif by remember { mutableStateOf(Notifier.canPost(ctx)) }
    var exact by remember { mutableStateOf(c.scheduler.canExact()) }
    var mic by remember { mutableStateOf(micGranted(ctx)) }
    var skippedNotif by remember { mutableStateOf(false) }
    var skippedExact by remember { mutableStateOf(false) }
    var skippedMic by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notif = Notifier.canPost(ctx); exact = c.scheduler.canExact(); mic = micGranted(ctx) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notif = Notifier.canPost(ctx); if (!notif) skippedNotif = true }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { mic = micGranted(ctx); if (!mic) skippedMic = true }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(Icons.Filled.EmojiEvents, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            Text("Welcome to HabitLair", style = MaterialTheme.typography.headlineMedium)
            Text("A few permissions make the app work properly. Allow what you like and skip the rest; you can change any of them later in Settings.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            PermissionCard(
                icon = Icons.Filled.Notifications, title = "Notifications",
                why = "Shows habit and task reminders, with Done and Snooze buttons. Without it you won't be reminded.",
                granted = notif, skipped = skippedNotif,
                note = if (Build.VERSION.SDK_INT < 33) "Already allowed on this Android version." else null,
                onAllow = { if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                onSkip = { skippedNotif = true }
            )
            PermissionCard(
                icon = Icons.Filled.Alarm, title = "Exact alarms",
                why = "Lets reminders fire at the exact minute. Without it they can arrive a few minutes late.",
                granted = exact, skipped = skippedExact,
                note = if (Build.VERSION.SDK_INT < 31) "Already allowed on this Android version." else "Opens a system page where you switch HabitLair on.",
                onAllow = {
                    if (Build.VERSION.SDK_INT >= 31) runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                    }
                },
                onSkip = { skippedExact = true }
            )
            PermissionCard(
                icon = Icons.Filled.Mic, title = "Microphone (optional)",
                why = "Only used when you record an audio note on a habit, task or journal entry.",
                granted = mic, skipped = skippedMic, note = null,
                onAllow = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onSkip = { skippedMic = true }
            )
            Text("Photos and videos use the system picker and camera app, so they need no permission.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Button(onFinish, Modifier.fillMaxWidth()) { Text("Continue") }
        }
    }
}

@Composable
private fun PermissionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, why: String,
    granted: Boolean, skipped: Boolean, note: String?, onAllow: () -> Unit, onSkip: () -> Unit
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp))
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (granted) { Icon(Icons.Filled.CheckCircle, "Allowed", tint = MaterialTheme.colorScheme.primary) }
            }
            Text(why, style = MaterialTheme.typography.bodyMedium)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (granted) Text("Allowed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            else if (skipped) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Skipped for now", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onAllow) { Text("Allow after all") }
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onAllow) { Text("Allow") }
                OutlinedButton(onSkip) { Text("Not now") }
            }
        }
    }
}
