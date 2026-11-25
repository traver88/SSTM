package com.kaoyan.planner.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

class SyncRepository(context: Context, private val dao: PlannerDao) {
    private val appContext = context.applicationContext
    private val api = SupabaseApi(appContext)
    private val preferences = appContext.getSharedPreferences("planner_state", Context.MODE_PRIVATE)

    val isConfigured: Boolean
        get() = api.isConfigured

    val currentUserId: String?
        get() = api.currentUserId

    fun hasSession(): Boolean = api.hasSession()

    suspend fun signIn(email: String, password: String): String = withContext(Dispatchers.IO) {
        val priorUserId = api.currentUserId
        val active = api.signIn(email, password)
        try {
            attachAccount(active.userId)
            sync()
            active.userId
        } catch (error: Throwable) {
            if (priorUserId != active.userId) api.signOut()
            throw error
        }
    }

    suspend fun signUp(email: String, password: String): String? = withContext(Dispatchers.IO) {
        val priorUserId = api.currentUserId
        val active = api.signUp(email, password) ?: return@withContext null
        try {
            attachAccount(active.userId)
            sync()
            active.userId
        } catch (error: Throwable) {
            if (priorUserId != active.userId) api.signOut()
            throw error
        }
    }

    suspend fun signOut() {
        api.signOut()
    }

    suspend fun revertRevision(revisionId: String, cascadeNewer: Boolean = false): String = withContext(Dispatchers.IO) {
        val userId = api.currentUserId ?: throw IllegalStateException("请先登录 Supabase 账号")
        val response = api.rpc("rpc_revert_plan_revision_tx", JSONObject()
            .put("p_user_id", userId)
            .put("p_revision_id", revisionId)
            .put("p_cascade_newer", cascadeNewer))
        val result = runCatching { JSONObject(response) }.getOrNull()
        if (result?.has("ok") == true && !result.optBoolean("ok")) {
            throw IllegalStateException(result.optString("message", "撤回失败"))
        }
        sync()
        "已撤回计划变更并重新同步"
    }

    suspend fun createApiKey(): String = withContext(Dispatchers.IO) {
        check(api.currentUserId != null) { "请先登录 Supabase 账号" }
        val response = JSONObject(api.rpc("rpc_create_user_api_key", JSONObject()))
        val keyId = response.optString("id").takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Supabase 未返回 API Key ID")
        val rawKey = response.optString("api_key").takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Supabase 未返回 API Key")
        preferences.edit().putString(KEY_API_KEY_ID, keyId).apply()
        rawKey
    }

    suspend fun revokeApiKey(): String = withContext(Dispatchers.IO) {
        check(api.currentUserId != null) { "请先登录 Supabase 账号" }
        val keyId = preferences.getString(KEY_API_KEY_ID, null)
            ?: throw IllegalStateException("本机没有可撤销的 API Key ID")
        val result = JSONObject(api.rpc("rpc_revoke_user_api_key", JSONObject().put("p_key_id", keyId)))
        preferences.edit().remove(KEY_API_KEY_ID).apply()
        if (result.optBoolean("revoked")) "已吊销远程 MCP API Key" else "API Key 已不存在或已经吊销"
    }

    fun hasApiKeyId(): Boolean = !preferences.getString(KEY_API_KEY_ID, null).isNullOrBlank()

    suspend fun sync(): String = withContext(Dispatchers.IO) {
        val userId = api.currentUserId ?: throw IllegalStateException("请先登录 Supabase 账号")
        val storedUserId = preferences.getString(KEY_USER_ID, null)
        if (storedUserId != userId) attachAccount(userId)
        pushPendingExamDate(userId)
        val pendingTasks = dao.getPendingTasks(userId)
        val remotePendingRows = getRemoteTaskVersions(userId, pendingTasks)

        val profile = api.getArray("/rest/v1/user_profiles?select=id,exam_date,timezone&id=eq.${queryValue(userId)}&limit=1")
        if (profile.length() > 0) {
            val examDate = profile.getJSONObject(0).optString("exam_date")
            if (examDate.isNotBlank() && examDate != "null") {
                preferences.edit().putString(KEY_EXAM_DATE, examDate).apply()
            }
        }

        val subjects = api.getArray("/rest/v1/subjects?select=*&user_id=eq.${queryValue(userId)}&order=name&limit=1000")
        dao.upsertSubjects((0 until subjects.length()).map { subjects.getJSONObject(it).toSubject(userId) })

        val phases = api.getArray("/rest/v1/exam_phases?select=*&user_id=eq.${queryValue(userId)}&order=order_index&limit=1000")
        val phaseRows = ArrayList<ExamPhaseEntity>()
        val milestoneRows = ArrayList<MilestoneEntity>()
        val remotePhaseVersions = mutableMapOf<String, Int>()
        for (index in 0 until phases.length()) {
            val row = phases.getJSONObject(index)
            val phaseId = row.optString("id")
            if (!row.isNull("deleted_at")) {
                dao.deletePhase(phaseId)
                dao.deleteMilestonesForPhase(phaseId)
                continue
            }
            remotePhaseVersions[phaseId] = row.optInt("version", 1)
            phaseRows += row.toPhase(userId)
            val localMilestoneChanges = dao.getPendingMilestones(phaseId)
            dao.deleteMilestonesForPhase(phaseId)
            val milestones = row.optJSONArray("subject_milestones") ?: JSONArray()
            for (milestoneIndex in 0 until milestones.length()) {
                val milestone = milestones.optJSONObject(milestoneIndex) ?: continue
                val subjectId = milestone.optString("subject_id")
                val subjectName = (0 until subjects.length()).asSequence()
                    .map { subjects.getJSONObject(it) }
                    .firstOrNull { it.optString("id") == subjectId }
                    ?.optString("name")
                    .orEmpty()
                milestoneRows += MilestoneEntity(
                    id = milestone.optString("id").ifBlank { "${row.optString("id")}:$milestoneIndex" },
                    phaseId = row.optString("id"),
                    subjectName = subjectName,
                    text = milestone.optString("text"),
                    done = milestone.optBoolean("done"),
                )
            }
            milestoneRows += localMilestoneChanges
        }
        dao.insertPhases(phaseRows)
        dao.insertMilestones(milestoneRows)

        val monthlyPlans = api.getArray("/rest/v1/monthly_plans?select=*&user_id=eq.${queryValue(userId)}&order=start_date.desc&limit=1000")
        val monthlyRows = ArrayList<MonthlyPlanEntity>()
        for (index in 0 until monthlyPlans.length()) {
            val row = monthlyPlans.getJSONObject(index)
            if (!row.isNull("deleted_at")) {
                val planId = row.optString("id")
                dao.unlinkTasksFromMonthlyPlan(planId)
                dao.deleteMonthlyPlan(planId)
            } else {
                monthlyRows += row.toMonthlyPlan(userId)
            }
        }
        dao.upsertMonthlyPlans(monthlyRows)

        val today = LocalDate.now()
        val startDate = queryValue(today.minusDays(30).toString())
        val endDate = queryValue(today.plusDays(90).toString())
        val tasks = api.getArray("/rest/v1/daily_plans?select=*&user_id=eq.${queryValue(userId)}&plan_date=gte.$startDate&plan_date=lte.$endDate&order=plan_date,scheduled_start_time&limit=2000")
        for (index in 0 until tasks.length()) {
            val row = tasks.getJSONObject(index)
            val existing = dao.getTaskById(row.optString("id"))
            if (existing?.syncStatus == "PENDING") continue
            if (!row.isNull("deleted_at")) {
                dao.deleteTask(row.optString("id"))
            } else {
                dao.upsertTask(row.toTask(userId))
            }
        }

        val cutoff = queryValue(today.minusDays(365).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toString())
        val sessions = api.getArray("/rest/v1/study_sessions?select=*&user_id=eq.${queryValue(userId)}&started_at=gte.$cutoff&order=started_at.desc&limit=5000")
        dao.upsertSessions((0 until sessions.length()).map { sessions.getJSONObject(it).toSession(userId) })

        val revisions = api.getArray("/rest/v1/plan_revisions?select=id,user_id,scope,actor,tool_name,change_summary,reason,affected_date_range,is_read,is_reverted,created_at&user_id=eq.${queryValue(userId)}&actor=eq.mcp_agent&order=created_at.desc&limit=20")
        dao.clearRevisions()
        dao.upsertRevisions((0 until revisions.length()).map { revisions.getJSONObject(it).toRevision(userId) })

        pushPendingMilestones(remotePhaseVersions, userId)
        pushPending(userId, remotePendingRows)
        "同步完成 · ${subjects.length()} 科目 · ${phaseRows.size} 阶段 · ${tasks.length()} 项任务"
    }

    private suspend fun pushPending(userId: String, remoteTaskVersions: Map<String, RemoteTaskState>) {
        val tasks = dao.getPendingTasks(userId)
        if (tasks.isNotEmpty()) {
            tasks.forEach { task ->
                val remote = remoteTaskVersions[task.id]
                if (remote != null) {
                    val update = JSONObject()
                        .put("status", task.status)
                        .put("version", remote.version + 1)
                    if (task.deletedAt != null) update.put("deleted_at", Instant.ofEpochMilli(task.deletedAt).toString())
                    val updated = api.patchReturning(
                        "/rest/v1/daily_plans?id=eq.${queryValue(task.id)}&user_id=eq.${queryValue(userId)}&version=eq.${remote.version}",
                        update,
                    )
                    if (updated.length() == 0) throw IllegalStateException("ERR_VERSION_CONFLICT: 任务已在其他位置更新，保留本机状态并稍后重试。")
                    dao.markTaskSynced(task.id)
                    return@forEach
                }
                if (task.deletedAt != null) {
                    dao.deleteTask(task.id)
                    return@forEach
                }
                api.rpc("rpc_mobile_add_plan_task_tx", JSONObject()
                    .put("p_user_id", userId)
                    .put("p_task", JSONObject()
                        .put("id", task.id)
                        .put("monthly_plan_id", task.monthlyPlanId.jsonValue())
                        .put("plan_date", task.planDate)
                        .put("subject_id", task.subjectId)
                        .put("title", task.title)
                        .put("activity_type", task.activityType.toRemoteActivity())
                        .put("granularity", task.granularity)
                        .put("planned_minutes", task.plannedMinutes)
                        .put("scheduled_start_time", task.scheduledStartTime.jsonValue())
                        .put("priority", task.priority)
                        .put("tags", task.tagsCsv.split(',').filter(String::isNotBlank).toJsonArray())
                        .put("ai_reason", task.aiReason.jsonValue())))
                dao.markTaskSynced(task.id)
            }
        }

        val sessions = dao.getPendingSessions(userId)
        if (sessions.isNotEmpty()) {
            val rows = JSONArray()
            sessions.forEach { session ->
                val started = Instant.ofEpochMilli(session.startedAtEpochMs)
                val ended = session.endedAtEpochMs?.let { Instant.ofEpochMilli(it) } ?: started.plusSeconds(session.durationSeconds.toLong())
                rows.put(JSONObject()
                    .put("id", session.id)
                    .put("user_id", userId)
                    .put("subject_id", session.subjectId)
                    .put("plan_id", session.planId.jsonValue())
                    .put("activity_type", session.activityType.toRemoteActivity())
                    .put("started_at", started.toString())
                    .put("ended_at", ended.toString())
                    .put("duration_seconds", session.durationSeconds)
                    .put("focus_score", session.focusScore)
                    .put("note", session.note.jsonValue())
                    .put("source", session.source))
            }
            api.upsert("/rest/v1/study_sessions?on_conflict=id", rows)
            sessions.forEach { dao.markSessionSynced(it.id) }
        }
    }

    private suspend fun getRemoteTaskVersions(userId: String, tasks: List<PlanTaskEntity>): Map<String, RemoteTaskState> {
        if (tasks.isEmpty()) return emptyMap()
        val ids = tasks.joinToString(",") { it.id }
        val encodedIds = queryValue("($ids)")
        val rows = api.getArray("/rest/v1/daily_plans?select=id,version&user_id=eq.${queryValue(userId)}&id=in.$encodedIds&limit=1000")
        return (0 until rows.length()).associate { index ->
            val row = rows.getJSONObject(index)
            row.getString("id") to RemoteTaskState(version = row.optInt("version", 1))
        }
    }

    private suspend fun pushPendingMilestones(remotePhaseVersions: Map<String, Int>, userId: String) {
        for ((phaseId, expectedVersion) in remotePhaseVersions) {
            val pendingMilestones = dao.getPendingMilestones(phaseId)
            if (pendingMilestones.isEmpty()) continue
            val phase = api.getArray("/rest/v1/exam_phases?select=subject_milestones,version&id=eq.${queryValue(phaseId)}&user_id=eq.${queryValue(userId)}&limit=1")
            if (phase.length() == 0) continue
            val currentVersion = phase.getJSONObject(0).optInt("version", expectedVersion)
            val currentMilestones = phase.getJSONObject(0).optJSONArray("subject_milestones") ?: JSONArray()
            val pendingById = pendingMilestones.associateBy { it.id }
            val updatedMilestones = JSONArray()
            val appliedMilestoneIds = mutableSetOf<String>()
            for (index in 0 until currentMilestones.length()) {
                val current = currentMilestones.optJSONObject(index) ?: continue
                val milestoneId = current.optString("id").ifBlank { "$phaseId:$index" }
                val local = pendingById[milestoneId]
                updatedMilestones.put(JSONObject(current.toString()).apply {
                    if (local != null) {
                        put("id", milestoneId)
                        put("done", local.done)
                        appliedMilestoneIds += local.id
                    }
                })
            }
            if (appliedMilestoneIds.isEmpty()) continue
            val updated = api.patchReturning(
                "/rest/v1/exam_phases?id=eq.${queryValue(phaseId)}&user_id=eq.${queryValue(userId)}&version=eq.$currentVersion",
                JSONObject().put("subject_milestones", updatedMilestones).put("version", currentVersion + 1),
            )
            if (updated.length() == 0) throw IllegalStateException("ERR_VERSION_CONFLICT: 阶段已被其他操作修改，请重新同步后重试。")
            appliedMilestoneIds.forEach { dao.markMilestoneSynced(it) }
        }
    }

    private suspend fun attachAccount(userId: String) {
        val priorUserId = preferences.getString(KEY_USER_ID, null)
        if (priorUserId != null && priorUserId != userId) {
            val pendingCount = dao.pendingTaskCount() + dao.pendingSessionCount() + dao.pendingMilestoneCount()
            check(pendingCount == 0) {
                "切换 Supabase 账号前，请先使用原账号联网同步本机待上传的任务、学习记录和里程碑。"
            }
            dao.clearSubjects()
            dao.clearTasks()
            dao.clearSessions()
            dao.clearPhases()
            dao.clearMilestones()
            dao.clearMonthlyPlans()
            dao.clearRevisions()
            preferences.edit().remove(KEY_EXAM_DATE).remove(KEY_PENDING_EXAM_DATE).remove(KEY_API_KEY_ID).apply()
        }
        preferences.edit().putString(KEY_USER_ID, userId).apply()
        dao.adoptLocalTasks(userId)
        dao.adoptLocalSessions(userId)
    }

    fun saveExamDate(value: String) {
        preferences.edit().putString(KEY_EXAM_DATE, value).putBoolean(KEY_PENDING_EXAM_DATE, true).apply()
    }

    private suspend fun pushPendingExamDate(userId: String) {
        if (!preferences.getBoolean(KEY_PENDING_EXAM_DATE, false)) return
        val examDate = preferences.getString(KEY_EXAM_DATE, null) ?: return
        api.rpc("rpc_upsert_profile_and_subjects_tx", JSONObject()
            .put("p_user_id", userId)
            .put("p_profile", JSONObject().put("exam_date", examDate))
            .put("p_subjects", JSONArray()))
        preferences.edit().remove(KEY_PENDING_EXAM_DATE).apply()
    }

    private fun JSONObject.toSubject(userId: String): SubjectEntity {
        val colorHex = optString("color", "#3B82F6").removePrefix("#")
        val colorLong = runCatching { 0xFF000000L or colorHex.toLong(16) }.getOrDefault(0xFF3B82F6)
        return SubjectEntity(
            id = getString("id"), name = getString("name"), color = colorLong,
            targetRatio = optInt("target_ratio", 25), dailyMaxMinutes = optInt("daily_max_minutes", 210),
            archived = optBoolean("is_archived"), userId = userId, syncStatus = "SYNCED",
        )
    }

    private fun JSONObject.toPhase(userId: String): ExamPhaseEntity = ExamPhaseEntity(
        id = getString("id"), name = getString("name"), startDate = getString("start_date"), endDate = getString("end_date"),
        targetHours = optInt("target_hours"), coreGoal = optString("core_goal"), status = optString("status"),
        orderIndex = optInt("order_index", 1), remindBeforeDaysJson = (optJSONArray("remind_before_days") ?: JSONArray("[7,0]")).toString(),
        reminderEnabled = optBoolean("reminder_enabled", true), version = optInt("version", 1), userId = userId,
        syncStatus = "SYNCED",
    )

    private fun JSONObject.toMonthlyPlan(userId: String) = MonthlyPlanEntity(
        id = getString("id"), userId = userId, phaseId = nullableString("phase_id"), title = optString("title"),
        startDate = optString("start_date"), endDate = optString("end_date"), status = optString("status"),
        subjectHourQuotasJson = optJSONObject("subject_hour_quotas")?.toString() ?: "{}",
        weeklyMilestonesJson = optJSONArray("weekly_milestones")?.toString() ?: "[]",
        strategyNote = nullableString("strategy_note"), renewRemindBeforeDays = optInt("renew_remind_before_days", 3),
        updatedAt = nullableString("updated_at"),
    )

    private fun JSONObject.toTask(userId: String) = PlanTaskEntity(
        id = getString("id"), planDate = getString("plan_date"), subjectId = getString("subject_id"),
        title = optString("title"), activityType = optString("activity_type").toLocalActivity(),
        plannedMinutes = optInt("planned_minutes"), scheduledStartTime = nullableString("scheduled_start_time")?.take(5),
        status = optString("status", "todo"), isLocked = optBoolean("is_locked"),
        monthlyPlanId = nullableString("monthly_plan_id"), userId = userId, version = optInt("version", 1),
        source = optString("source", "manual"), priority = optInt("priority", 2), granularity = optString("granularity", "detailed"),
        tagsCsv = (optJSONArray("tags") ?: JSONArray()).toStringList().joinToString(","),
        aiReason = nullableString("ai_reason"), syncStatus = "SYNCED",
    )

    private fun JSONObject.toSession(userId: String) = StudySessionEntity(
        id = getString("id"), subjectId = getString("subject_id"), planId = nullableString("plan_id"),
        activityType = optString("activity_type").toLocalActivity(),
        startedAtEpochMs = parseInstant(getString("started_at")).toEpochMilli(),
        durationSeconds = optInt("duration_seconds"), focusScore = optInt("focus_score", 2), note = nullableString("note"),
        source = optString("source", "timer"), endedAtEpochMs = parseInstant(getString("ended_at")).toEpochMilli(),
        userId = userId, syncStatus = "SYNCED",
    )

    private fun JSONObject.toRevision(userId: String) = PlanRevisionEntity(
        id = getString("id"), userId = userId, scope = optString("scope"), actor = optString("actor"),
        toolName = optString("tool_name"), changeSummary = optString("change_summary"), reason = optString("reason"),
        affectedDateRange = optString("affected_date_range"), isRead = optBoolean("is_read"),
        isReverted = optBoolean("is_reverted"), createdAt = optString("created_at"),
    )

    private fun JSONObject.nullableString(name: String): String? = if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)

    private fun String.toLocalActivity(): String = when (this) {
        "input" -> "听课"
        "output" -> "刷题"
        "review" -> "复盘"
        else -> this
    }

    private fun String.toRemoteActivity(): String = when (this) {
        "听课" -> "input"
        "刷题" -> "output"
        "复盘" -> "review"
        else -> this
    }

    private fun String?.jsonValue(): Any = this ?: JSONObject.NULL

    private fun List<String>.toJsonArray(): JSONArray = JSONArray().also { array -> forEach { value -> array.put(value) } }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }

    private fun parseInstant(value: String): Instant = runCatching { Instant.parse(value) }
        .recoverCatching { OffsetDateTime.parse(value).toInstant() }
        .getOrElse { throw IllegalStateException("Supabase returned an invalid timestamp: $value") }

    companion object {
        private const val KEY_USER_ID = "supabase_user_id"
        private const val KEY_EXAM_DATE = "exam_date"
        private const val KEY_PENDING_EXAM_DATE = "exam_date_pending"
        private const val KEY_API_KEY_ID = "remote_mcp_api_key_id"
    }

    private data class RemoteTaskState(val version: Int)
}
