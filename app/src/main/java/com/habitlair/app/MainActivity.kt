package com.habitlair.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.habitlair.app.data.AppSettings
import com.habitlair.app.ui.AppRoot
import com.habitlair.app.ui.HabitLairTheme
import com.habitlair.app.ui.LocalContainer
import com.habitlair.app.ui.OnboardingScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HabitLairApp).container
        setContent {
            // null until DataStore has loaded, so returning users never see a flash of the first-run screen
            val loaded: AppSettings? by container.settings.flow.collectAsStateWithLifecycle(initialValue = null)
            val scope = rememberCoroutineScope()
            val s = loaded
            HabitLairTheme(s ?: AppSettings()) {
                CompositionLocalProvider(LocalContainer provides container) {
                    when {
                        s == null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                        !s.onboarded -> OnboardingScreen { scope.launch { container.settings.update { it.copy(onboarded = true) } } }
                        else -> AppRoot()
                    }
                }
            }
        }
    }
}
