package com.kaoyan.planner.domain.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.kaoyan.planner.ReminderAlarmReceiver
import com.kaoyan.planner.TimerAlarmReceiver
import com.kaoyan.planner.TimerSnapshot
import com.kaoyan.planner.data.ExamPhaseEntity
import com.kaoyan.planner.data.MonthlyPlanEntity
import com.kaoyan.planner.data.PlanTaskEntity
import com.kaoyan.planner.data.PlannerDatabase
import com.kaoyan.planner.data.PlannerDao
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object ReminderScheduler {
    private const val PREFERENCES = "planner_reminders"
    private const val KEY_REGISTERED = "registered_reminder_keys"
    private const val KEY_ACTIVE_TIMER = "active_timer"
    private const val TIMER_REQUEST_CODE = 4431

    const val TYPE_PHASE = "phase"
    const val TYPE_MONTHLY = "monthly"
    private const val TYPE_DAILY = "daily"
    private const val TYPE_SUBJECT_OVERTIME = "subject_overtime"

    private data class Reminder(
        val key: String,
        val type: String,
        val title: String,
        val text: String,
        val triggerAtMillis: Long,
    )

    suspend fun rescheduleAll(context: Context) = withContext(Dispatchers.IO) {
        val applicationContext = context.applicationContext
        if (!applicationContext.getSharedPreferences("supabase_session", Context.MODE_PRIVATE)
                .contains("encrypted_session")
        ) {
            cancelAll(applicationContext)
            return@withContext
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            applicationContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            cancelAll(applicationContext)
            return@withContext
        }
        val dao = PlannerDatabase.get(applicationContext).plannerDao()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val reminders = linkedMapOf<String, Reminder>()

        dao.getReminderPhases().forEach { phase ->
            addPhaseReminders(reminders, phase, now, zone)
        }

        dao.getActiveMonthlyPlan()?.let { plan ->
            addMonthlyRenewal(reminders, plan, now, zone)
        }

        dao.getScheduledTasks(today.toString(), today.plusDays(1).toString()).forEach { task ->
            addTaskReminder(reminders, task, dao, now, zone)
        }

        val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val previousKeys = preferences.getStringSet(KEY_REGISTERED, emptySet()).orEmpty()
        val alarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        previousKeys.forEach { key -> cancelReminder(alarmManager, applicationContext, key) }

        reminders.values.forEach { scheduleReminder(alarmManager, applicationContext, it) }
        preferences.edit().putStringSet(KEY_REGISTERED, reminders.keys.toSet()).apply()

        readActiveTimer(applicationContext)?.takeIf { it.lastPausedAtEpochMs == null }?.let { timer ->
            scheduleSubjectOvertime(applicationContext, dao, timer)
        }
    }

    suspend fun scheduleSubjectOvertime(context: Context, dao: PlannerDao, timer: TimerSnapshot) = withContext(Dispatchers.IO) {
        val applicationContext = context.applicationContext
        if (timer.lastPausedAtEpochMs != null) {
            cancelSubjectOvertime(applicationContext, timer.subjectId)
            return@withContext
        }

        val subject = dao.getSubject(timer.subjectId)
        if (subject == null) {
            cancelSubjectOvertime(applicationContext, timer.subjectId)
            return@withContext
        }

        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val nextDayStart = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val loggedTodaySeconds = dao.getSubjectStudySeconds(timer.subjectId, dayStart, nextDayStart)
        val totalElapsedSeconds = elapsedSeconds(timer, now)
        val elapsedBeforeToday = if (timer.startedAtEpochMs < dayStart) {
            ((dayStart - timer.startedAtEpochMs - timer.pausedTotalMs).coerceAtLeast(0L) / 1000L)
        } else {
            0L
        }
        val elapsedToday = (totalElapsedSeconds - elapsedBeforeToday).coerceAtLeast(0L)
        val warningLimitSeconds = (subject.dailyMaxMinutes + OVERTIME_EXTRA_MINUTES) * 60L
        val remainingSeconds = warningLimitSeconds - loggedTodaySeconds - elapsedToday
        if (remainingSeconds <= 0L) {
            cancelSubjectOvertime(applicationContext, timer.subjectId)
            return@withContext
        }
        val reminder = Reminder(
            key = overtimeKey(timer.subjectId),
            type = TYPE_SUBJECT_OVERTIME,
            title = "单科学习时长提醒",
            text = "${subject.name} 今日累计学习已接近 ${subject.dailyMaxMinutes} 分钟上限。",
            triggerAtMillis = now + remainingSeconds * 1000L,
        )
        scheduleReminder(
            applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager,
            applicationContext,
            reminder,
        )
        addRegisteredKey(applicationContext, reminder.key)
    }

    fun cancelSubjectOvertime(context: Context, subjectId: String) {
        val applicationContext = context.applicationContext
        val key = overtimeKey(subjectId)
        cancelReminder(
            applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager,
            applicationContext,
            key,
            cancelNotification = true,
        )
        removeRegisteredKey(applicationContext, key)
    }

    fun cancelAll(context: Context) {
        val applicationContext = context.applicationContext
        val alarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val keys = preferences.getStringSet(KEY_REGISTERED, emptySet()).orEmpty()
        keys.forEach { key ->
            cancelReminder(alarmManager, applicationContext, key, cancelNotification = true)
        }
        alarmManager.cancel(timerPendingIntent(applicationContext))
        preferences.edit().remove(KEY_REGISTERED).apply()
    }

    suspend fun restoreAfterBoot(context: Context) = withContext(Dispatchers.IO) {
        val applicationContext = context.applicationContext
        val dao = PlannerDatabase.get(applicationContext).plannerDao()
        rescheduleAll(applicationContext)
        val timer = readActiveTimer(applicationContext) ?: return@withContext
        if (timer.lastPausedAtEpochMs != null) return@withContext

        val now = System.currentTimeMillis()
        val remainingMillis = (timer.targetDurationSec * 1000L - elapsedMillis(timer, now)).coerceAtLeast(0L)
        scheduleTimerFinished(applicationContext, timer.sessionId, now + remainingMillis)
        scheduleSubjectOvertime(applicationContext, dao, timer)
    }

    suspend fun rescheduleAfterDataSync(context: Context) {
        rescheduleAll(context)
    }

    fun scheduleTimerFinished(context: Context, sessionId: String, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = timerPendingIntent(context, sessionId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    private fun addPhaseReminders(
        output: MutableMap<String, Reminder>,
        phase: ExamPhaseEntity,
        nowMillis: Long,
        zone: ZoneId,
    ) {
        if (!phase.reminderEnabled || phase.status == "completed") return
        val startDate = runCatching { LocalDate.parse(phase.startDate) }.getOrNull() ?: return
        val daysBefore = runCatching {
            val json = JSONArray(phase.remindBeforeDaysJson)
            (0 until json.length()).map { json.optInt(it, -1) }.filter { it >= 0 }.distinct()
        }.getOrDefault(listOf(7, 0))

        daysBefore.forEach { days ->
            val reminderDate = startDate.minusDays(days.toLong())
            val time = if (days == 0) LocalTime.of(8, 0) else LocalTime.of(9, 0)
            val triggerAt = reminderDate.atTime(time).atZone(zone).toInstant().toEpochMilli()
            if (triggerAt <= nowMillis) return@forEach
            val key = "phase:${phase.id}:$days"
            val title = if (days == 0) "阶段开始提醒" else "阶段预热提醒"
            val text = if (days == 0) {
                "今天进入${phase.name}。${phase.coreGoal}"
            } else {
                "距离${phase.name}还有 $days 天。${phase.coreGoal}"
            }
            output[key] = Reminder(key, TYPE_PHASE, title, text, triggerAt)
        }
    }

    private fun addMonthlyRenewal(
        output: MutableMap<String, Reminder>,
        plan: MonthlyPlanEntity,
        nowMillis: Long,
        zone: ZoneId,
    ) {
        val endDate = runCatching { LocalDate.parse(plan.endDate) }.getOrNull() ?: return
        val daysBefore = plan.renewRemindBeforeDays.coerceAtLeast(0)
        val reminderDate = endDate.minusDays(daysBefore.toLong())
        val triggerAt = reminderDate.atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
        if (triggerAt <= nowMillis) return
        val key = "monthly-renew:${plan.id}"
        val text = if (daysBefore == 0) {
            "${plan.title}今天结束，请安排下一个月的计划。"
        } else {
            "${plan.title}还有 $daysBefore 天结束，请安排下一个月的计划。"
        }
        output[key] = Reminder(key, TYPE_MONTHLY, "月计划续期提醒", text, triggerAt)
    }

    private suspend fun addTaskReminder(
        output: MutableMap<String, Reminder>,
        task: PlanTaskEntity,
        dao: PlannerDao,
        nowMillis: Long,
        zone: ZoneId,
    ) {
        val planDate = runCatching { LocalDate.parse(task.planDate) }.getOrNull() ?: return
        val startTime = runCatching { LocalTime.parse(task.scheduledStartTime?.take(5) ?: return) }.getOrNull() ?: return
        val startAt = planDate.atTime(startTime).atZone(zone).toInstant().toEpochMilli()
        val reminderAt = startAt - TASK_REMINDER_LEAD_MILLIS
        if (reminderAt <= nowMillis) return
        val subjectName = dao.getSubject(task.subjectId)?.name ?: "备考任务"
        val key = "task-start:${task.id}"
        output[key] = Reminder(
            key = key,
            type = TYPE_DAILY,
            title = "${startTime} 学习时间快到了",
            text = "$subjectName · ${task.title}（${task.plannedMinutes} 分钟）",
            triggerAtMillis = reminderAt,
        )
    }

    private fun scheduleReminder(alarmManager: AlarmManager, context: Context, reminder: Reminder) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(reminder.key),
            Intent(context, ReminderAlarmReceiver::class.java)
                .setAction(ReminderAlarmReceiver.ACTION_REMIND)
                .setData(Uri.parse("yantu://reminder/${Uri.encode(reminder.key)}"))
                .putExtra(ReminderAlarmReceiver.EXTRA_KEY, reminder.key)
                .putExtra(ReminderAlarmReceiver.EXTRA_TYPE, reminder.type)
                .putExtra(ReminderAlarmReceiver.EXTRA_TITLE, reminder.title)
                .putExtra(ReminderAlarmReceiver.EXTRA_TEXT, reminder.text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMillis, pendingIntent)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMillis, pendingIntent)
        }
    }

    private fun cancelReminder(
        alarmManager: AlarmManager,
        context: Context,
        key: String,
        cancelNotification: Boolean = false,
    ) {
        alarmManager.cancel(reminderPendingIntent(context, key))
        if (cancelNotification) NotificationManagerCompat.from(context).cancel(requestCode(key))
    }

    private fun reminderPendingIntent(context: Context, key: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode(key),
        Intent(context, ReminderAlarmReceiver::class.java)
            .setAction(ReminderAlarmReceiver.ACTION_REMIND)
            .setData(Uri.parse("yantu://reminder/${Uri.encode(key)}")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun timerPendingIntent(context: Context, sessionId: String? = null): PendingIntent = PendingIntent.getBroadcast(
        context,
        TIMER_REQUEST_CODE,
        Intent(context, TimerAlarmReceiver::class.java).apply {
            if (sessionId != null) putExtra("session_id", sessionId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun addRegisteredKey(context: Context, key: String) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val keys = preferences.getStringSet(KEY_REGISTERED, emptySet()).orEmpty().toMutableSet()
        keys += key
        preferences.edit().putStringSet(KEY_REGISTERED, keys).apply()
    }

    private fun removeRegisteredKey(context: Context, key: String) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val keys = preferences.getStringSet(KEY_REGISTERED, emptySet()).orEmpty().toMutableSet()
        keys.remove(key)
        preferences.edit().putStringSet(KEY_REGISTERED, keys).apply()
    }

    private fun readActiveTimer(context: Context): TimerSnapshot? {
        val raw = context.getSharedPreferences("planner_state", Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_TIMER, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            TimerSnapshot(
                sessionId = json.getString("sessionId"),
                subjectId = json.getString("subjectId"),
                subjectName = json.getString("subjectName"),
                subjectColor = json.getLong("subjectColor"),
                activityType = json.getString("activityType"),
                planId = json.optString("planId").takeUnless { it.isBlank() || it == "null" },
                taskTitle = json.getString("taskTitle"),
                plannedMinutes = json.getInt("plannedMinutes"),
                startedAtEpochMs = json.getLong("startedAtEpochMs"),
                pausedTotalMs = json.getLong("pausedTotalMs"),
                lastPausedAtEpochMs = if (json.isNull("lastPausedAtEpochMs")) null else json.getLong("lastPausedAtEpochMs"),
                targetDurationSec = json.getInt("targetDurationSec"),
            )
        }.getOrNull()
    }

    private fun elapsedSeconds(timer: TimerSnapshot, now: Long): Long {
        val until = timer.lastPausedAtEpochMs ?: now
        return ((until - timer.startedAtEpochMs - timer.pausedTotalMs).coerceAtLeast(0L)) / 1000L
    }

    private fun elapsedMillis(timer: TimerSnapshot, now: Long): Long {
        val until = timer.lastPausedAtEpochMs ?: now
        return (until - timer.startedAtEpochMs - timer.pausedTotalMs).coerceAtLeast(0L)
    }

    private fun overtimeKey(subjectId: String): String = "overtime:$subjectId"

    private fun requestCode(key: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return (ByteBuffer.wrap(digest).int and 0x7fffffff).coerceAtLeast(1)
    }

    private const val TASK_REMINDER_LEAD_MILLIS = 5 * 60 * 1000L
    private const val OVERTIME_EXTRA_MINUTES = 30
}
