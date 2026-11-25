package com.kaoyan.planner

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaoyan.planner.data.ExamPhaseEntity
import com.kaoyan.planner.data.PlanTaskEntity
import com.kaoyan.planner.data.PlannerDatabase
import com.kaoyan.planner.data.PlannerDao
import com.kaoyan.planner.data.PlanRevisionEntity
import com.kaoyan.planner.data.StudySessionEntity
import com.kaoyan.planner.data.SubjectEntity
import com.kaoyan.planner.data.SyncRepository
import com.kaoyan.planner.domain.reminder.ReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import kotlin.math.max

data class TimerSnapshot(
    val sessionId: String,
    val subjectId: String,
    val subjectName: String,
    val subjectColor: Long,
    val activityType: String,
    val planId: String?,
    val taskTitle: String,
    val plannedMinutes: Int,
    val startedAtEpochMs: Long,
    val pausedTotalMs: Long,
    val lastPausedAtEpochMs: Long?,
    val targetDurationSec: Int,
)

class PlannerViewModel(application: Application) : AndroidViewModel(application) {
    private val dao: PlannerDao = PlannerDatabase.get(application).plannerDao()
    private val syncRepository = SyncRepository(application, dao)
    private val preferences = application.getSharedPreferences("planner_state", Context.MODE_PRIVATE)
    private val _timer = MutableStateFlow(readTimer())
    val timer: StateFlow<TimerSnapshot?> = _timer.asStateFlow()

    val subjects = dao.observeSubjects()
    val phases = dao.observePhases()
    val milestones = dao.observeAllMilestones()
    val revisions = dao.observeRecentRevisions()
    private val _signedIn = MutableStateFlow(syncRepository.hasSession())
    val signedIn = _signedIn.asStateFlow()
    private val _onlineReady = MutableStateFlow(false)
    val onlineReady = _onlineReady.asStateFlow()
    private val _syncStatus = MutableStateFlow(if (syncRepository.hasSession()) "已连接" else "仅本机模式")
    val syncStatus = _syncStatus.asStateFlow()
    private val _syncing = MutableStateFlow(false)
    val syncing = _syncing.asStateFlow()
    private val _createdApiKey = MutableStateFlow<String?>(null)
    val createdApiKey = _createdApiKey.asStateFlow()
    val hasApiKeyId: Boolean get() = syncRepository.hasApiKeyId()

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            _syncing.value = true
            _onlineReady.value = false
            try {
                syncRepository.signIn(email, password)
                ReminderScheduler.rescheduleAfterDataSync(getApplication())
                _signedIn.value = true
                _onlineReady.value = true
                _syncStatus.value = "登录并同步完成"
            } catch (error: Throwable) {
                _signedIn.value = syncRepository.hasSession()
                _onlineReady.value = false
                _syncStatus.value = error.message ?: "登录失败"
            } finally {
                _syncing.value = false
            }
        }
    }

    fun signUp(email: String, password: String) {
        viewModelScope.launch {
            _syncing.value = true
            _onlineReady.value = false
            try {
                val userId = syncRepository.signUp(email, password)
                if (userId == null) {
                    _signedIn.value = false
                    _syncStatus.value = "注册请求已发送，请先完成邮箱验证，再登录。"
                } else {
                    ReminderScheduler.rescheduleAfterDataSync(getApplication())
                    _signedIn.value = true
                    _onlineReady.value = true
                    _syncStatus.value = "注册并同步完成"
                }
            } catch (error: Throwable) {
                _signedIn.value = syncRepository.hasSession()
                _onlineReady.value = false
                _syncStatus.value = error.message ?: "注册失败"
            } finally {
                _syncing.value = false
            }
        }
    }

    fun synchronize() {
        if (!syncRepository.hasSession()) {
            _signedIn.value = false
            _onlineReady.value = false
            _syncStatus.value = "请登录 Supabase 账号后继续"
            return
        }
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            // 不提前清 onlineReady：已在线用户的日常同步在后台进行，不切回门禁页。
            val wasOnline = _onlineReady.value
            try {
                _syncStatus.value = syncRepository.sync()
                ReminderScheduler.rescheduleAfterDataSync(getApplication())
                _signedIn.value = true
                _onlineReady.value = true
            } catch (error: Throwable) {
                // 首次进入（未在线）或会话失效时回门禁页；其余失败保留缓存数据继续使用。
                if (!wasOnline || error.message?.contains("登录状态已失效") == true) {
                    _signedIn.value = syncRepository.hasSession()
                    _onlineReady.value = false
                }
                _syncStatus.value = error.message ?: "同步失败"
                // 后台同步失败时发系统通知，避免用户无感知地长时间用旧数据
                if (wasOnline) notifySyncFailure(_syncStatus.value ?: "同步失败")
            } finally {
                _syncing.value = false
            }
        }
    }

    fun revertRevision(revision: PlanRevisionEntity) {
        viewModelScope.launch {
            if (_syncing.value) return@launch
            _syncing.value = true
            try {
                _syncStatus.value = syncRepository.revertRevision(revision.id)
                ReminderScheduler.rescheduleAfterDataSync(getApplication())
            } catch (error: Throwable) {
                _syncStatus.value = error.message ?: "撤回失败"
            } finally {
                _syncing.value = false
            }
        }
    }

    fun createApiKey() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                _createdApiKey.value = syncRepository.createApiKey()
                _syncStatus.value = "API Key 已生成，只会显示这一次。"
            } catch (error: Throwable) {
                _syncStatus.value = error.message ?: "生成 API Key 失败"
            } finally {
                _syncing.value = false
            }
        }
    }

    fun clearCreatedApiKey() {
        _createdApiKey.value = null
    }

    fun mcpClientConfiguration(apiKey: String): String {
        val server = JSONObject()
            .put("url", BuildConfig.MCP_HTTP_URL)
            .put("headers", JSONObject().put("Authorization", "Bearer $apiKey"))
        return JSONObject()
            .put("mcpServers", JSONObject().put("yantu-planner", server))
            .toString(2)
    }

    fun revokeApiKey() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                _syncStatus.value = syncRepository.revokeApiKey()
            } catch (error: Throwable) {
                _syncStatus.value = error.message ?: "吊销 API Key 失败"
            } finally {
                _syncing.value = false
            }
        }
    }

    fun signOut() {
        if (_timer.value != null) {
            _syncStatus.value = "请先结束当前计时，再退出登录。"
            return
        }
        if (_syncing.value) {
            _syncStatus.value = "正在同步云端数据，请等待同步结束后再退出登录。"
            return
        }
        viewModelScope.launch {
            syncRepository.signOut()
            ReminderScheduler.cancelAll(getApplication())
            _signedIn.value = false
            _onlineReady.value = false
            _syncStatus.value = "已退出登录，本机缓存仍保留"
        }
    }

    /** 后台同步失败时的系统通知。固定通知 ID，重复失败只更新不堆叠。 */
    private fun notifySyncFailure(message: String) {
        val context = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val notification = NotificationCompat.Builder(context, PlannerApplication.CHANNEL_SYNC)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("云端同步失败")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).apply {
            runCatching { notify(NOTIFICATION_ID_SYNC_FAILURE, notification) }
        }
    }

    fun isCloudConfigured(): Boolean = syncRepository.isConfigured

    fun tasksFor(date: String) = dao.observeTasksForDate(date)

    fun toggleTask(task: PlanTaskEntity) {
        viewModelScope.launch {
            dao.setTaskStatus(task.id, if (task.status == "done") "todo" else "done")
            ReminderScheduler.rescheduleAll(getApplication())
        }
    }

    fun addTask(task: PlanTaskEntity) {
        viewModelScope.launch {
            val userId = syncRepository.currentUserId
            dao.upsertTask(task.copy(userId = userId, syncStatus = if (userId == null) "SYNCED" else "PENDING"))
            ReminderScheduler.rescheduleAll(getApplication())
        }
    }

    fun toggleMilestone(id: String, done: Boolean) {
        viewModelScope.launch { dao.setMilestoneDone(id, !done) }
    }

    fun startTask(task: PlanTaskEntity, subject: SubjectEntity) {
        viewModelScope.launch {
            val logged = dao.loggedSecondsForTask(task.id)
            val remaining = max(300, task.plannedMinutes * 60 - logged)
            startTimer(subject, task.activityType, task.id, task.title, task.plannedMinutes, remaining)
        }
    }

    fun startFreeTimer(subject: SubjectEntity, activityType: String, minutes: Int) {
        startTimer(subject, activityType, null, "自由计时", minutes, minutes * 60)
    }

    private fun startTimer(
        subject: SubjectEntity,
        activityType: String,
        planId: String?,
        taskTitle: String,
        plannedMinutes: Int,
        targetDurationSec: Int,
    ) {
        if (_timer.value != null) return
        val now = System.currentTimeMillis()
        val snapshot = TimerSnapshot(
            sessionId = UUID.randomUUID().toString(),
            subjectId = subject.id,
            subjectName = subject.name,
            subjectColor = subject.color,
            activityType = activityType,
            planId = planId,
            taskTitle = taskTitle,
            plannedMinutes = plannedMinutes,
            startedAtEpochMs = now,
            pausedTotalMs = 0L,
            lastPausedAtEpochMs = null,
            targetDurationSec = targetDurationSec,
        )
        persist(snapshot)
        _timer.value = snapshot
        scheduleFinishAlarm(snapshot, targetDurationSec * 1000L)
        viewModelScope.launch {
            ReminderScheduler.scheduleSubjectOvertime(getApplication(), dao, snapshot)
        }
    }

    fun pauseTimer() {
        val timer = _timer.value ?: return
        if (timer.lastPausedAtEpochMs != null) return
        cancelFinishAlarm()
        ReminderScheduler.cancelSubjectOvertime(getApplication(), timer.subjectId)
        val paused = timer.copy(lastPausedAtEpochMs = System.currentTimeMillis())
        persist(paused)
        _timer.value = paused
    }

    fun resumeTimer() {
        val timer = _timer.value ?: return
        val pausedAt = timer.lastPausedAtEpochMs ?: return
        val now = System.currentTimeMillis()
        val updated = timer.copy(
            pausedTotalMs = timer.pausedTotalMs + now - pausedAt,
            lastPausedAtEpochMs = null,
        )
        persist(updated)
        _timer.value = updated
        val remaining = max(0L, timer.targetDurationSec * 1000L - elapsedMillis(updated, now))
        scheduleFinishAlarm(updated, remaining)
        viewModelScope.launch {
            ReminderScheduler.scheduleSubjectOvertime(getApplication(), dao, updated)
        }
    }

    fun refreshTimer() {
        val timer = _timer.value ?: return
        if (timer.lastPausedAtEpochMs == null && elapsedMillis(timer, System.currentTimeMillis()) >= timer.targetDurationSec * 1000L) {
            finishTimer()
        }
    }

    fun finishTimer() {
        val timer = _timer.value ?: return
        cancelFinishAlarm()
        ReminderScheduler.cancelSubjectOvertime(getApplication(), timer.subjectId)
        val now = System.currentTimeMillis()
        val seconds = (elapsedMillis(timer, now) / 1000L).toInt().coerceAtLeast(0)
        preferences.edit().remove(KEY_TIMER).commit()
        _timer.value = null
        if (seconds >= 300) {
            viewModelScope.launch {
                dao.insertSession(
                    StudySessionEntity(
                        id = timer.sessionId,
                        subjectId = timer.subjectId,
                        planId = timer.planId,
                        activityType = timer.activityType,
                        startedAtEpochMs = timer.startedAtEpochMs,
                        durationSeconds = seconds,
                        focusScore = 2,
                        source = "timer",
                        endedAtEpochMs = now,
                        userId = syncRepository.currentUserId,
                        syncStatus = if (syncRepository.currentUserId == null) "SYNCED" else "PENDING",
                    ),
                )
                val planId = timer.planId
                if (planId != null && dao.loggedSecondsForTask(planId) >= timer.plannedMinutes * 60 * 0.8) {
                    dao.setTaskStatus(planId, "done")
                }
                ReminderScheduler.rescheduleAll(getApplication())
            }
        }
    }

    fun addManualSession(subject: SubjectEntity, activityType: String, minutes: Int, note: String?) {
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            dao.insertSession(
                StudySessionEntity(
                    subjectId = subject.id,
                    activityType = activityType,
                    startedAtEpochMs = now - minutes * 60_000L,
                    durationSeconds = minutes * 60,
                    focusScore = 2,
                    note = note?.take(50)?.ifBlank { null },
                    source = "manual",
                    endedAtEpochMs = now,
                    userId = syncRepository.currentUserId,
                    syncStatus = if (syncRepository.currentUserId == null) "SYNCED" else "PENDING",
                ),
            )
        }
    }

    fun elapsedSeconds(timer: TimerSnapshot, now: Long = System.currentTimeMillis()): Long = elapsedMillis(timer, now) / 1000L

    fun examDate(): String = preferences.getString(KEY_EXAM_DATE, "") ?: ""

    fun saveExamDate(value: String) {
        preferences.edit().putString(KEY_EXAM_DATE, value).apply()
        syncRepository.saveExamDate(value)
        synchronize()
    }

    private fun elapsedMillis(timer: TimerSnapshot, now: Long): Long {
        val until = timer.lastPausedAtEpochMs ?: now
        return max(0L, until - timer.startedAtEpochMs - timer.pausedTotalMs)
    }

    private fun persist(timer: TimerSnapshot) {
        val json = JSONObject().apply {
            put("sessionId", timer.sessionId)
            put("subjectId", timer.subjectId)
            put("subjectName", timer.subjectName)
            put("subjectColor", timer.subjectColor)
            put("activityType", timer.activityType)
            put("planId", timer.planId)
            put("taskTitle", timer.taskTitle)
            put("plannedMinutes", timer.plannedMinutes)
            put("startedAtEpochMs", timer.startedAtEpochMs)
            put("pausedTotalMs", timer.pausedTotalMs)
            put("lastPausedAtEpochMs", timer.lastPausedAtEpochMs)
            put("targetDurationSec", timer.targetDurationSec)
        }
        preferences.edit().putString(KEY_TIMER, json.toString()).commit()
    }

    private fun readTimer(): TimerSnapshot? {
        val raw = preferences.getString(KEY_TIMER, null) ?: return null
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

    private fun scheduleFinishAlarm(timer: TimerSnapshot, delayMillis: Long) {
        val context = getApplication<Application>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = Intent(context, TimerAlarmReceiver::class.java).putExtra("session_id", timer.sessionId)
        val pending = android.app.PendingIntent.getBroadcast(
            context,
            TIMER_REQUEST_CODE,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = System.currentTimeMillis() + max(0L, delayMillis)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    private fun cancelFinishAlarm() {
        val context = getApplication<Application>()
        val pending = android.app.PendingIntent.getBroadcast(
            context,
            TIMER_REQUEST_CODE,
            Intent(context, TimerAlarmReceiver::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        (context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).cancel(pending)
    }

    companion object {
        private const val KEY_TIMER = "active_timer"
        private const val KEY_EXAM_DATE = "exam_date"
        private const val TIMER_REQUEST_CODE = 4431
        private const val NOTIFICATION_ID_SYNC_FAILURE = 9001
    }
}
