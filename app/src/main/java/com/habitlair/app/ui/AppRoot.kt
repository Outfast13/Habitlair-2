package com.habitlair.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideIntoContainer
import androidx.compose.animation.slideOutOfContainer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.navigation.NavBackStackEntry
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
    Tab("timer", "Timer", Icons.Filled.Timer),
    Tab("chat", "AI", Icons.Filled.AutoAwesome)
)

private const val SLIDE_MS = 420

private fun tabIndex(route: String?): Int = tabs.indexOfFirst { it.route == route?.substringBefore("?") }

private typealias NavScope = AnimatedContentTransitionScope<NavBackStackEntry>

/** Tab → tab slides in the direction of the tab order; going deeper slides in from the right; back reverses it. */
private fun NavScope.enterSlide(anim: Boolean): EnterTransition {
    if (!anim) return EnterTransition.None
    val from = tabIndex(initialState.destination.route)
    val to = tabIndex(targetState.destination.route)
    val dir = if (from >= 0 && to >= 0 && to < from) AnimatedContentTransitionScope.SlideDirection.Right else AnimatedContentTransitionScope.SlideDirection.Left
    return slideIntoContainer(dir, tween(SLIDE_MS, easing = FastOutSlowInEasing)) + fadeIn(tween(SLIDE_MS))
}

private fun NavScope.exitSlide(anim: Boolean): ExitTransition {
    if (!anim) return ExitTransition.None
    val from = tabIndex(initialState.destination.route)
    val to = tabIndex(targetState.destination.route)
    val bothTabs = from >= 0 && to >= 0
    val dir = if (bothTabs && to < from) AnimatedContentTransitionScope.SlideDirection.Right else AnimatedContentTransitionScope.SlideDirection.Left
    return if (bothTabs) slideOutOfContainer(dir, tween(SLIDE_MS, easing = FastOutSlowInEasing)) + fadeOut(tween(SLIDE_MS))
    else slideOutOfContainer(dir, tween(SLIDE_MS, easing = FastOutSlowInEasing), targetOffset = { it / 4 }) + fadeOut(tween(SLIDE_MS))
}

private fun NavScope.popEnterSlide(anim: Boolean): EnterTransition {
    if (!anim) return EnterTransition.None
    return slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(SLIDE_MS, easing = FastOutSlowInEasing), initialOffset = { it / 4 }) + fadeIn(tween(SLIDE_MS))
}

private fun NavScope.popExitSlide(anim: Boolean): ExitTransition {
    if (!anim) return ExitTransition.None
    return slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(SLIDE_MS, easing = FastOutSlowInEasing)) + fadeOut(tween(SLIDE_MS))
}

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route?.substringBefore("?")
    val anim = LocalAnim.current
    val density = LocalDensity.current
    val imeOpen by remember { derivedStateOf { WindowInsets.ime.getBottom(density) > 0 } }

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
        NavHost(
            nav, startDestination = "today", modifier = Modifier.padding(pv).consumeWindowInsets(pv).imePadding(),
            enterTransition = { enterSlide(anim) }, exitTransition = { exitSlide(anim) },
            popEnterTransition = { popEnterSlide(anim) }, popExitTransition = { popExitSlide(anim) }
        ) {
            composable("today") { TodayScreen(nav) }
            composable("tasks") { TasksScreen(nav) }
            composable("journal") { JournalScreen(nav) }
            composable("timer") { TimerScreen() }
            composable("chat") { AiChatScreen(nav) }
            composable("categories") { CategoriesScreen(nav) }
            composable("habit/{id}", listOf(navArgument("id") { type = NavType.LongType })) { HabitDetailScreen(it.arguments?.getLong("id") ?: 0L, nav) }
            composable("aihistory") { AiHistoryScreen(nav) }
            composable("settings") { SettingsScreen(nav) }
        }
    }
}
