package com.habitlair.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("today", "Today", Icons.Filled.Today),
    Tab("tasks", "Tasks", Icons.Filled.TaskAlt),
    Tab("journal", "Journal", Icons.Filled.Book),
    Tab("timer", "Timer", Icons.Filled.Timer)
)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val anim = LocalAnim.current
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val enter = if (anim) fadeIn() else EnterTransition.None
    val exit = if (anim) fadeOut() else ExitTransition.None

    Scaffold(
        bottomBar = {
            if (route in tabs.map { it.route } && !imeOpen) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route, onClick = { nav.goTab(t.route) },
                        icon = { Icon(t.icon, t.label) }, label = { Text(t.label) }
                    )
                }
            }
        }
    ) { pv ->
        NavHost(nav, startDestination = "today", modifier = Modifier.padding(pv).consumeWindowInsets(pv).imePadding(),
            enterTransition = { enter }, exitTransition = { exit }, popEnterTransition = { enter }, popExitTransition = { exit }) {
            composable("today") { TodayScreen(nav) }
            composable("tasks") { TasksScreen(nav) }
            composable("journal") { JournalScreen(nav) }
            composable("timer") { TimerScreen() }
            composable("habits") { HabitsScreen(nav) }
            composable("categories") { CategoriesScreen(nav) }
            composable("habit/{id}", listOf(navArgument("id") { type = NavType.LongType })) { HabitDetailScreen(it.arguments?.getLong("id") ?: 0L, nav) }
            composable("chat?prompt={prompt}", listOf(navArgument("prompt") { type = NavType.StringType; defaultValue = "" })) {
                AiChatScreen(nav, it.arguments?.getString("prompt") ?: "")
            }
            composable("aihistory") { AiHistoryScreen(nav) }
            composable("settings") { SettingsScreen(nav) }
        }
    }
}
