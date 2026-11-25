package com.kaoyan.planner.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "subjects")
data class SubjectEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val color: Long,
    val targetRatio: Int = 25,
    val dailyMaxMinutes: Int = 210,
    val archived: Boolean = false,
    val userId: String? = null,
    @ColumnInfo(defaultValue = "'SYNCED'") val syncStatus: String = "SYNCED",
)

@Entity(tableName = "daily_plans")
data class PlanTaskEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val planDate: String,
    val subjectId: String,
    val title: String,
    val activityType: String,
    val plannedMinutes: Int,
    val scheduledStartTime: String? = null,
    val status: String = "todo",
    val isLocked: Boolean = false,
    val deletedAt: Long? = null,
    val monthlyPlanId: String? = null,
    val userId: String? = null,
    @ColumnInfo(defaultValue = "1") val version: Int = 1,
    @ColumnInfo(defaultValue = "'manual'") val source: String = "manual",
    @ColumnInfo(defaultValue = "2") val priority: Int = 2,
    @ColumnInfo(defaultValue = "'detailed'") val granularity: String = "detailed",
    @ColumnInfo(defaultValue = "''") val tagsCsv: String = "",
    val aiReason: String? = null,
    @ColumnInfo(defaultValue = "'SYNCED'") val syncStatus: String = "SYNCED",
)

@Entity(tableName = "study_sessions")
data class StudySessionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val subjectId: String,
    val planId: String? = null,
    val activityType: String,
    val startedAtEpochMs: Long,
    val durationSeconds: Int,
    val focusScore: Int = 2,
    val note: String? = null,
    val source: String,
    val endedAtEpochMs: Long? = null,
    val userId: String? = null,
    @ColumnInfo(defaultValue = "'PENDING'") val syncStatus: String = "PENDING",
)

@Entity(tableName = "exam_phases")
data class ExamPhaseEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val startDate: String,
    val endDate: String,
    val targetHours: Int,
    val coreGoal: String,
    val status: String,
    @ColumnInfo(defaultValue = "1") val orderIndex: Int = 1,
    @ColumnInfo(defaultValue = "'[7,0]'") val remindBeforeDaysJson: String = "[7,0]",
    @ColumnInfo(defaultValue = "1") val reminderEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "1") val version: Int = 1,
    val deletedAt: Long? = null,
    val userId: String? = null,
    @ColumnInfo(defaultValue = "'SYNCED'") val syncStatus: String = "SYNCED",
)

@Entity(tableName = "phase_milestones")
data class MilestoneEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val phaseId: String,
    val subjectName: String,
    val text: String,
    val done: Boolean = false,
    @ColumnInfo(defaultValue = "'SYNCED'") val syncStatus: String = "SYNCED",
)

@Entity(tableName = "monthly_plans")
data class MonthlyPlanEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val phaseId: String?,
    val title: String,
    val startDate: String,
    val endDate: String,
    val status: String,
    val subjectHourQuotasJson: String,
    val weeklyMilestonesJson: String,
    val strategyNote: String?,
    val renewRemindBeforeDays: Int,
    val updatedAt: String?,
)

@Entity(tableName = "plan_revisions")
data class PlanRevisionEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val scope: String,
    val actor: String,
    val toolName: String,
    val changeSummary: String,
    val reason: String,
    val affectedDateRange: String,
    val isRead: Boolean,
    val isReverted: Boolean,
    val createdAt: String,
)

@Dao
interface PlannerDao {
    @Query("SELECT * FROM subjects WHERE archived = 0 ORDER BY name")
    fun observeSubjects(): Flow<List<SubjectEntity>>

    @Query("SELECT * FROM daily_plans WHERE planDate = :date AND deletedAt IS NULL ORDER BY CASE WHEN scheduledStartTime IS NULL THEN 1 ELSE 0 END, scheduledStartTime, title")
    fun observeTasksForDate(date: String): Flow<List<PlanTaskEntity>>

    @Query("SELECT * FROM exam_phases ORDER BY startDate")
    fun observePhases(): Flow<List<ExamPhaseEntity>>

    @Query("SELECT * FROM exam_phases WHERE deletedAt IS NULL AND reminderEnabled = 1 ORDER BY startDate")
    suspend fun getReminderPhases(): List<ExamPhaseEntity>

    @Query("SELECT * FROM phase_milestones ORDER BY phaseId, subjectName, text")
    fun observeAllMilestones(): Flow<List<MilestoneEntity>>

    @Query("SELECT * FROM monthly_plans WHERE status = 'active' ORDER BY startDate DESC LIMIT 1")
    suspend fun getActiveMonthlyPlan(): MonthlyPlanEntity?

    @Query("SELECT * FROM daily_plans WHERE planDate BETWEEN :startDate AND :endDate AND deletedAt IS NULL AND status IN ('todo', 'in_progress') AND scheduledStartTime IS NOT NULL")
    suspend fun getScheduledTasks(startDate: String, endDate: String): List<PlanTaskEntity>

    @Query("SELECT * FROM subjects WHERE id = :id AND archived = 0 LIMIT 1")
    suspend fun getSubject(id: String): SubjectEntity?

    @Query("SELECT COALESCE(SUM(durationSeconds), 0) FROM study_sessions WHERE subjectId = :subjectId AND startedAtEpochMs >= :fromMs AND startedAtEpochMs < :untilMs")
    suspend fun getSubjectStudySeconds(subjectId: String, fromMs: Long, untilMs: Long): Int

    @Query("SELECT * FROM plan_revisions WHERE isReverted = 0 ORDER BY createdAt DESC LIMIT 20")
    fun observeRecentRevisions(): Flow<List<PlanRevisionEntity>>

    @Query("SELECT * FROM phase_milestones WHERE phaseId = :phaseId AND syncStatus = 'PENDING'")
    suspend fun getPendingMilestones(phaseId: String): List<MilestoneEntity>

    @Query("SELECT COUNT(*) FROM phase_milestones WHERE syncStatus = 'PENDING'")
    suspend fun pendingMilestoneCount(): Int

    @Query("SELECT * FROM daily_plans WHERE syncStatus = 'PENDING' AND userId = :userId")
    suspend fun getPendingTasks(userId: String): List<PlanTaskEntity>

    @Query("SELECT COUNT(*) FROM daily_plans WHERE syncStatus = 'PENDING'")
    suspend fun pendingTaskCount(): Int

    @Query("SELECT * FROM study_sessions WHERE syncStatus = 'PENDING' AND userId = :userId")
    suspend fun getPendingSessions(userId: String): List<StudySessionEntity>

    @Query("SELECT COUNT(*) FROM study_sessions WHERE syncStatus = 'PENDING'")
    suspend fun pendingSessionCount(): Int

    @Query("SELECT * FROM daily_plans WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: String): PlanTaskEntity?

    @Query("SELECT COALESCE(SUM(durationSeconds), 0) FROM study_sessions WHERE planId = :planId")
    suspend fun loggedSecondsForTask(planId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSubjects(subjects: List<SubjectEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: PlanTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: StudySessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhases(phases: List<ExamPhaseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMilestones(milestones: List<MilestoneEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMonthlyPlans(plans: List<MonthlyPlanEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRevisions(revisions: List<PlanRevisionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSessions(sessions: List<StudySessionEntity>)

    @Query("UPDATE daily_plans SET syncStatus = 'SYNCED' WHERE id = :taskId")
    suspend fun markTaskSynced(taskId: String)

    @Query("UPDATE study_sessions SET syncStatus = 'SYNCED' WHERE id = :sessionId")
    suspend fun markSessionSynced(sessionId: String)

    @Query("UPDATE phase_milestones SET syncStatus = 'SYNCED' WHERE id = :milestoneId")
    suspend fun markMilestoneSynced(milestoneId: String)

    @Query("DELETE FROM daily_plans WHERE id = :taskId")
    suspend fun deleteTask(taskId: String)

    @Query("DELETE FROM exam_phases WHERE id = :phaseId")
    suspend fun deletePhase(phaseId: String)

    @Query("DELETE FROM monthly_plans WHERE id = :planId")
    suspend fun deleteMonthlyPlan(planId: String)

    @Query("UPDATE daily_plans SET monthlyPlanId = NULL WHERE monthlyPlanId = :planId")
    suspend fun unlinkTasksFromMonthlyPlan(planId: String)

    @Query("DELETE FROM phase_milestones WHERE phaseId = :phaseId")
    suspend fun deleteMilestonesForPhase(phaseId: String)

    @Query("DELETE FROM subjects")
    suspend fun clearSubjects()

    @Query("DELETE FROM daily_plans")
    suspend fun clearTasks()

    @Query("DELETE FROM study_sessions")
    suspend fun clearSessions()

    @Query("DELETE FROM exam_phases")
    suspend fun clearPhases()

    @Query("DELETE FROM phase_milestones")
    suspend fun clearMilestones()

    @Query("DELETE FROM monthly_plans")
    suspend fun clearMonthlyPlans()

    @Query("DELETE FROM plan_revisions")
    suspend fun clearRevisions()

    @Query("UPDATE daily_plans SET userId = :userId, syncStatus = 'PENDING' WHERE userId IS NULL AND subjectId IN (SELECT id FROM subjects WHERE userId = :userId)")
    suspend fun adoptLocalTasks(userId: String)

    @Query("UPDATE study_sessions SET userId = :userId, syncStatus = 'PENDING' WHERE userId IS NULL AND subjectId IN (SELECT id FROM subjects WHERE userId = :userId)")
    suspend fun adoptLocalSessions(userId: String)

    @Query("UPDATE daily_plans SET status = :status, syncStatus = :syncStatus, version = version + 1 WHERE id = :taskId")
    suspend fun setTaskStatus(taskId: String, status: String, syncStatus: String = "PENDING")

    @Query("UPDATE phase_milestones SET done = :done, syncStatus = 'PENDING' WHERE id = :milestoneId")
    suspend fun setMilestoneDone(milestoneId: String, done: Boolean)

}

@Database(
    entities = [SubjectEntity::class, PlanTaskEntity::class, StudySessionEntity::class, ExamPhaseEntity::class, MilestoneEntity::class, MonthlyPlanEntity::class, PlanRevisionEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class PlannerDatabase : RoomDatabase() {
    abstract fun plannerDao(): PlannerDao

    companion object {
        @Volatile private var instance: PlannerDatabase? = null

        fun get(context: Context): PlannerDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PlannerDatabase::class.java,
                "yantu-planner.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subjects ADD COLUMN userId TEXT")
                db.execSQL("ALTER TABLE subjects ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'SYNCED'")
                db.execSQL("ALTER TABLE daily_plans ADD COLUMN monthlyPlanId TEXT")
                db.execSQL("ALTER TABLE daily_plans ADD COLUMN userId TEXT")
                db.execSQL("ALTER TABLE daily_plans ADD COLUMN version INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE daily_plans ADD COLUMN source TEXT NOT NULL DEFAULT 'manual'")
                db.execSQL("ALTER TABLE daily_plans ADD COLUMN priority INTEGER NOT NULL DEFAULT 2")
                db.execSQL("ALTER TABLE daily_plans ADD COLUMN granularity TEXT NOT NULL DEFAULT 'detailed'")
                db.execSQL("ALTER TABLE daily_plans ADD COLUMN tagsCsv TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE daily_plans ADD COLUMN aiReason TEXT")
                db.execSQL("ALTER TABLE daily_plans ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'SYNCED'")
                db.execSQL("ALTER TABLE study_sessions ADD COLUMN endedAtEpochMs INTEGER")
                db.execSQL("ALTER TABLE study_sessions ADD COLUMN userId TEXT")
                db.execSQL("ALTER TABLE study_sessions ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE exam_phases ADD COLUMN orderIndex INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE exam_phases ADD COLUMN remindBeforeDaysJson TEXT NOT NULL DEFAULT '[7,0]'")
                db.execSQL("ALTER TABLE exam_phases ADD COLUMN reminderEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE exam_phases ADD COLUMN version INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE exam_phases ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE exam_phases ADD COLUMN userId TEXT")
                db.execSQL("ALTER TABLE exam_phases ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'SYNCED'")
                db.execSQL("ALTER TABLE phase_milestones ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'SYNCED'")
                db.execSQL("CREATE TABLE IF NOT EXISTS monthly_plans (id TEXT NOT NULL PRIMARY KEY, userId TEXT NOT NULL, phaseId TEXT, title TEXT NOT NULL, startDate TEXT NOT NULL, endDate TEXT NOT NULL, status TEXT NOT NULL, subjectHourQuotasJson TEXT NOT NULL, weeklyMilestonesJson TEXT NOT NULL, strategyNote TEXT, renewRemindBeforeDays INTEGER NOT NULL, updatedAt TEXT)")
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS plan_revisions (id TEXT NOT NULL PRIMARY KEY, userId TEXT NOT NULL, scope TEXT NOT NULL, actor TEXT NOT NULL, toolName TEXT NOT NULL, changeSummary TEXT NOT NULL, reason TEXT NOT NULL, affectedDateRange TEXT NOT NULL, isRead INTEGER NOT NULL, isReverted INTEGER NOT NULL, createdAt TEXT NOT NULL)")
            }
        }
    }
}
