package com.habitlair.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.habitlair.app.HabitLairApp
import com.habitlair.app.MainActivity
import com.habitlair.app.R
import com.habitlair.app.data.Dates
import com.habitlair.app.data.isScheduled
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

const val ACTION_WIDGET_TOGGLE = "com.habitlair.WIDGET_TOGGLE"
const val EXTRA_ITEM_ID = "item_id"
const val EXTRA_ITEM_TASK = "item_is_task"

data class WidgetItem(val id: Long, val isTask: Boolean, val title: String, val subtitle: String, val done: Boolean) {
    val listId: Long get() = if (isTask) id + (1L shl 40) else id
}

private class Palette(
    val bg: ColorProvider, val text: ColorProvider, val muted: ColorProvider,
    val accent: ColorProvider, val track: ColorProvider, val outline: ColorProvider
)

@Composable
private fun paletteFor(style: Int): Palette = when (style) {
    1 -> Palette( // Midnight
        ColorProvider(Color(0xFF0F1B2D)), ColorProvider(Color(0xFFEAF2FF)), ColorProvider(Color(0xFF9FB3CC)),
        ColorProvider(Color(0xFF4DA3FF)), ColorProvider(Color(0xFF22344D)), ColorProvider(Color(0xFF6C84A3))
    )
    2 -> Palette( // Sunrise
        ColorProvider(Color(0xFFFFF4E6)), ColorProvider(Color(0xFF3B2A1A)), ColorProvider(Color(0xFF8A6D52)),
        ColorProvider(Color(0xFFF2762E)), ColorProvider(Color(0xFFFFD9B3)), ColorProvider(Color(0xFFC9A27E))
    )
    else -> Palette( // Material You: follows the wallpaper
        GlanceTheme.colors.widgetBackground, GlanceTheme.colors.onSurface, GlanceTheme.colors.onSurfaceVariant,
        GlanceTheme.colors.primary, GlanceTheme.colors.secondaryContainer, GlanceTheme.colors.outline
    )
}

/** Shared by the three widgets; only the colour palette differs. Resizes from 2 icons wide upwards. */
abstract class BaseHabitWidget(private val style: Int) : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(110.dp, 110.dp), DpSize(180.dp, 140.dp), DpSize(260.dp, 200.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = (context.applicationContext as HabitLairApp).container
        val day = Dates.today()
        val doneIds = c.db.habits().doneIds(day).toSet()
        val habits = c.db.habits().all()
            .filter { !it.archived && it.isScheduled(day) }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .map { h ->
                val sub = if (h.timeMinutes != null) "Habit · " + Dates.fmtTime(h.timeMinutes) else "Habit"
                WidgetItem(h.id, false, h.name, sub, h.id in doneIds)
            }
        val tasks = c.db.tasks().all()
            .filter { it.parentId == null && it.dueEpochDay != null && (it.dueEpochDay == day || (it.dueEpochDay < day && !it.done)) }
            .sortedBy { it.sortOrder }
            .map { t ->
                val sub = if (t.dueEpochDay != null && t.dueEpochDay < day) "Task · overdue" else "Task · due today"
                WidgetItem(t.id, true, t.title, sub, t.done)
            }
        val items = habits + tasks
        val dateText = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
        provideContent { GlanceTheme { WidgetContent(context, items, dateText, paletteFor(style)) } }
    }
}

class DynamicWidget : BaseHabitWidget(0)
class MidnightWidget : BaseHabitWidget(1)
class SunriseWidget : BaseHabitWidget(2)

class DynamicWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = DynamicWidget() }
class MidnightWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = MidnightWidget() }
class SunriseWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = SunriseWidget() }

suspend fun updateAllWidgets(ctx: Context) {
    for (w in listOf<GlanceAppWidget>(DynamicWidget(), MidnightWidget(), SunriseWidget())) {
        try { w.updateAll(ctx) } catch (e: Exception) { /* widget type not placed, or being removed */ }
    }
}

@Composable
private fun WidgetContent(context: Context, items: List<WidgetItem>, dateText: String, p: Palette) {
    val compact = LocalSize.current.width < 170.dp
    val total = items.size
    val done = items.count { it.done }
    val openApp = actionStartActivity(Intent(context, MainActivity::class.java))
    Column(
        modifier = GlanceModifier.fillMaxSize().background(p.bg).cornerRadius(22.dp).padding(if (compact) 10.dp else 14.dp)
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth().clickable(openApp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text("Today", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = if (compact) 16.sp else 20.sp, color = p.text))
                if (!compact) Text(dateText, style = TextStyle(fontSize = 12.sp, color = p.muted))
            }
            Text("$done/$total", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = if (compact) 16.sp else 22.sp, color = p.accent))
        }
        Spacer(modifier = GlanceModifier.height(6.dp))
        LinearProgressIndicator(
            progress = if (total == 0) 0f else done.toFloat() / total,
            modifier = GlanceModifier.fillMaxWidth(), color = p.accent, backgroundColor = p.track
        )
        Spacer(modifier = GlanceModifier.height(4.dp))
        if (items.isEmpty()) {
            Text("Nothing scheduled today", style = TextStyle(fontSize = 13.sp, color = p.muted))
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(items, itemId = { it.listId }) { item -> WidgetRow(context, item, p, compact) }
            }
        }
    }
}

@Composable
private fun WidgetRow(context: Context, item: WidgetItem, p: Palette, compact: Boolean) {
    // An explicit broadcast to our own receiver: one tap anywhere on the row toggles the item.
    val toggleIntent = Intent(context, WidgetActionReceiver::class.java)
        .setAction(ACTION_WIDGET_TOGGLE)
        .setData(Uri.parse("habitlair://toggle/" + (if (item.isTask) "t" else "h") + "/" + item.id))
        .putExtra(EXTRA_ITEM_ID, item.id)
        .putExtra(EXTRA_ITEM_TASK, item.isTask)
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = if (compact) 5.dp else 6.dp).clickable(actionSendBroadcast(toggleIntent)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = item.title,
                maxLines = if (compact) 2 else 1,
                style = TextStyle(
                    fontSize = if (compact) 13.sp else 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (item.done) p.muted else p.text,
                    textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None
                )
            )
            if (!compact) Text(item.subtitle, maxLines = 1, style = TextStyle(fontSize = 11.sp, color = p.muted))
        }
        Spacer(modifier = GlanceModifier.width(8.dp))
        Image(
            provider = ImageProvider(if (item.done) R.drawable.ic_check_on else R.drawable.ic_check_off),
            contentDescription = if (item.done) "Completed. Tap to undo" else "Not done. Tap to complete",
            modifier = GlanceModifier.size(if (compact) 24.dp else 28.dp),
            colorFilter = ColorFilter.tint(if (item.done) p.accent else p.outline)
        )
    }
}

class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WIDGET_TOGGLE) return
        val id = intent.getLongExtra(EXTRA_ITEM_ID, -1L)
        if (id < 0) return
        val isTask = intent.getBooleanExtra(EXTRA_ITEM_TASK, false)
        val pending = goAsync()
        val c = (context.applicationContext as HabitLairApp).container
        c.scope.launch {
            try {
                if (isTask) {
                    val t = c.db.tasks().get(id)
                    if (t != null) c.repo.setTaskDone(id, !t.done)
                } else {
                    c.repo.toggleHabit(id, Dates.today())
                }
                updateAllWidgets(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
