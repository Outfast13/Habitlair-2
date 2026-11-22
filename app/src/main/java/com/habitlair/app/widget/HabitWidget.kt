package com.habitlair.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
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
import com.habitlair.app.HabitLairApp
import com.habitlair.app.MainActivity
import com.habitlair.app.R
import com.habitlair.app.data.Dates
import com.habitlair.app.data.isScheduled
import java.time.LocalDate
import java.time.format.DateTimeFormatter

val ItemIdKey = ActionParameters.Key<Long>("itemId")
val ItemIsTaskKey = ActionParameters.Key<Boolean>("itemIsTask")

data class WidgetItem(val id: Long, val isTask: Boolean, val title: String, val subtitle: String, val done: Boolean) {
    val listId: Long get() = if (isTask) id + (1L shl 40) else id
}

class HabitWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = (context.applicationContext as HabitLairApp).container
        val day = Dates.today()
        val doneIds = c.db.habits().doneIds(day).toSet()
        val habits = c.db.habits().all()
            .filter { !it.archived && it.isScheduled(day) }
            .sortedWith(compareBy({ it.timeMinutes ?: Int.MAX_VALUE }, { it.sortOrder }))
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
        provideContent { GlanceTheme { WidgetContent(context, items, dateText) } }
    }
}

@Composable
private fun WidgetContent(context: Context, items: List<WidgetItem>, dateText: String) {
    val total = items.size
    val done = items.count { it.done }
    val openApp = actionStartActivity(Intent(context, MainActivity::class.java))
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(24.dp)
            .padding(14.dp)
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().clickable(openApp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "Today",
                    style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, color = GlanceTheme.colors.onSurface)
                )
                Text(
                    text = dateText,
                    style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant)
                )
            }
            Text(
                text = "$done/$total",
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, color = GlanceTheme.colors.primary)
            )
        }
        Spacer(modifier = GlanceModifier.height(8.dp))
        LinearProgressIndicator(
            progress = if (total == 0) 0f else done.toFloat() / total,
            modifier = GlanceModifier.fillMaxWidth(),
            color = GlanceTheme.colors.primary,
            backgroundColor = GlanceTheme.colors.secondaryContainer
        )
        Spacer(modifier = GlanceModifier.height(6.dp))
        if (items.isEmpty()) {
            Text(
                text = "Nothing scheduled today",
                style = TextStyle(fontSize = 14.sp, color = GlanceTheme.colors.onSurfaceVariant)
            )
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(items, itemId = { it.listId }) { item -> WidgetRow(item) }
            }
        }
    }
}

@Composable
private fun WidgetRow(item: WidgetItem) {
    val toggle = actionRunCallback<ToggleItemAction>(
        actionParametersOf(ItemIdKey to item.id, ItemIsTaskKey to item.isTask)
    )
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            provider = ImageProvider(if (item.done) R.drawable.ic_check_on else R.drawable.ic_check_off),
            contentDescription = if (item.done) "Completed. Tap to undo" else "Not done. Tap to complete",
            modifier = GlanceModifier.size(26.dp).clickable(toggle),
            colorFilter = ColorFilter.tint(if (item.done) GlanceTheme.colors.primary else GlanceTheme.colors.outline)
        )
        Spacer(modifier = GlanceModifier.width(10.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = item.title,
                maxLines = 1,
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (item.done) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface,
                    textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None
                )
            )
            Text(
                text = item.subtitle,
                maxLines = 1,
                style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant)
            )
        }
    }
}

class ToggleItemAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[ItemIdKey] ?: return
        val isTask = parameters[ItemIsTaskKey] ?: false
        val c = (context.applicationContext as HabitLairApp).container
        if (isTask) {
            val t = c.db.tasks().get(id) ?: return
            c.repo.setTaskDone(id, !t.done)
        } else {
            c.repo.toggleHabit(id, Dates.today())
        }
    }
}

class HabitWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HabitWidget()
}
