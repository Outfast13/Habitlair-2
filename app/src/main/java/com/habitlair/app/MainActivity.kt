package com.habitlair.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.habitlair.app.data.AppSettings
import com.habitlair.app.ui.AppRoot
import com.habitlair.app.ui.HabitLairTheme
import com.habitlair.app.ui.LocalContainer
import com.habitlair.app.work.Notifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HabitLairApp).container
        setContent {
            val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
            val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33 && !Notifier.canPost(this@MainActivity)) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            HabitLairTheme(settings) {
                CompositionLocalProvider(LocalContainer provides container) { AppRoot() }
            }
        }
    }
}
