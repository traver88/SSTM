import { z } from "zod";

export const uuid = z.string().uuid();
export const isoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, "日期应为 YYYY-MM-DD")
  .refine((value) => {
    const date = new Date(`${value}T00:00:00Z`);
    return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value;
  }, "日期无效");
export const activityType = z.enum(["input", "output", "review"]);
export const scheduledTime = z.string().regex(/^([01]\d|2[0-3]):[0-5]\d(:[0-5]\d)?$/).nullable().optional();
export const subjectFields = {
  subject_id: uuid.optional(),
  subject_name: z.string().trim().min(1).optional(),
};

export const subjectSelector = z.object(subjectFields).refine((value) => Boolean(value.subject_id) !== Boolean(value.subject_name), {
  message: "subject_id 与 subject_name 必须且只能提供一个",
});

const milestone = z.object({
  id: z.string().optional(),
  subject_id: uuid.optional(),
  subject_name: z.string().trim().min(1).optional(),
  text: z.string().trim().min(1),
  done: z.boolean().default(false),
});

export const schemas = {
  upsert_profile_and_subjects: z.object({
    exam_date: isoDate.optional(),
    daily_target_minutes: z.number().int().min(60).max(960).optional(),
    daily_hard_cap_minutes: z.number().int().min(60).max(960).optional(),
    rest_days: z.array(z.object({
      weekday: z.number().int().min(1).max(7),
      period: z.enum(["all_day", "morning", "afternoon", "evening"]),
    })).optional(),
    timezone: z.string().min(1).optional(),
    subjects: z.array(z.object({
      id: uuid.optional(), name: z.string().trim().min(1), color: z.string().regex(/^#[0-9a-fA-F]{6}$/).optional(),
      target_ratio: z.number().int().min(0).max(100).optional(), daily_max_minutes: z.number().int().min(5).max(960).optional(),
      require_daily_streak: z.boolean().optional(), is_archived: z.boolean().optional(),
    })).optional(),
  }),
  get_exam_roadmap: z.object({}),
  upsert_exam_roadmap: z.object({
    reason: z.string().trim().min(1), dry_run: z.boolean().default(false),
    phases: z.array(z.object({
      id: uuid.optional(), name: z.string().trim().min(1), order_index: z.number().int().min(1).max(8),
      start_date: isoDate, end_date: isoDate, target_hours: z.number().int().min(0), core_goal: z.string().min(1),
      subject_milestones: z.array(milestone).default([]), remind_before_days: z.array(z.number().int().min(0).max(60)).optional(),
      status: z.enum(["upcoming", "active", "completed"]),
    })).min(1).max(8),
  }),
  patch_exam_phase: z.object({
    phase_id: uuid, expected_version: z.number().int().min(1), reason: z.string().trim().min(1),
    cascade_subsequent: z.boolean().default(false), dry_run: z.boolean().default(false),
    fields: z.object({
      start_date: isoDate.optional(), end_date: isoDate.optional(), target_hours: z.number().int().min(0).optional(),
      core_goal: z.string().min(1).optional(), subject_milestones: z.array(milestone).optional(),
      remind_before_days: z.array(z.number().int().min(0).max(60)).optional(), status: z.enum(["upcoming", "active", "completed"]).optional(),
    }).refine((value) => Object.keys(value).length > 0, "至少提供一个修改字段"),
  }),
  get_monthly_plan: z.object({
    start_date: isoDate, end_date: isoDate, detail_level: z.enum(["summary", "full"]), ...subjectFields,
  }).refine((value) => value.end_date >= value.start_date, { message: "结束日期不能早于开始日期" })
    .refine((value) => dateSpanDays(value.start_date, value.end_date) <= 35, { message: "单次读取最多 35 天" })
    .refine((value) => !(value.subject_id && value.subject_name), { message: "subject_id 与 subject_name 不能同时提供" }),
  upsert_monthly_blueprint: z.object({
    monthly_plan_id: uuid.optional(), phase_id: uuid.optional(), title: z.string().trim().min(1), start_date: isoDate, end_date: isoDate,
    reason: z.string().trim().min(1).default("AI 更新月度计划骨架"), dry_run: z.boolean().default(false),
    subject_hour_quotas: z.record(z.number().nonnegative()), weekly_milestones: z.array(z.unknown()).default([]), strategy_note: z.string().optional(),
  }).refine((value) => value.end_date >= value.start_date, { message: "结束日期不能早于开始日期" })
    .refine((value) => dateSpanDays(value.start_date, value.end_date) <= 35, { message: "月计划最多 35 天" }),
  batch_upsert_plan_range: z.object({
    monthly_plan_id: uuid.optional(), mode: z.enum(["replace_ai_only", "append"]), reason: z.string().trim().min(1), dry_run: z.boolean().default(false),
    days: z.array(z.object({ plan_date: isoDate, tasks: z.array(z.object({
      ...subjectFields,
      title: z.string().trim().min(1), activity_type: activityType, granularity: z.enum(["detailed", "outline"]).default("detailed"),
      planned_minutes: z.number().int().min(5).max(480), scheduled_start_time: scheduledTime,
      priority: z.number().int().min(1).max(3).default(2), tags: z.array(z.string()).default([]), ai_reason: z.string().optional(),
    })) })).max(10).min(1).superRefine((days, context) => {
      const dates = days.map((day) => day.plan_date);
      if (dates.some((date, index) => dates.indexOf(date) !== index)) {
        context.addIssue({ code: z.ZodIssueCode.custom, message: "每个日期只能出现一次", path: ["days"] });
      }
    }),
  }),
  patch_plan_tasks: z.object({
    reason: z.string().trim().min(1), dry_run: z.boolean().default(false),
    operations: z.array(z.discriminatedUnion("op", [
      z.object({ op: z.literal("add"), plan_date: isoDate, ...subjectFields, title: z.string().min(1), activity_type: activityType,
        planned_minutes: z.number().int().min(5).max(480), scheduled_start_time: scheduledTime, priority: z.number().int().min(1).max(3).default(2), tags: z.array(z.string()).default([]), ai_reason: z.string().optional() }),
      z.object({ op: z.literal("update"), task_id: uuid, expected_version: z.number().int().min(1), fields: z.object({ title: z.string().min(1).optional(), plan_date: isoDate.optional(), planned_minutes: z.number().int().min(5).max(480).optional(), scheduled_start_time: scheduledTime, activity_type: activityType.optional(), priority: z.number().int().min(1).max(3).optional(), tags: z.array(z.string()).optional(), ai_reason: z.string().optional() }).refine((fields) => Object.keys(fields).length > 0, "至少提供一个任务修改字段") }),
      z.object({ op: z.literal("delete"), task_id: uuid, expected_version: z.number().int().min(1) }),
      z.object({ op: z.literal("move"), task_id: uuid, expected_version: z.number().int().min(1), plan_date: isoDate, scheduled_start_time: scheduledTime }),
    ])).min(1).max(50).superRefine((operations, context) => {
      const taskIds = operations.filter((operation) => operation.op !== "add").map((operation) => operation.task_id);
      const duplicates = taskIds.filter((taskId, index) => taskIds.indexOf(taskId) !== index);
      if (duplicates.length > 0) context.addIssue({ code: z.ZodIssueCode.custom, message: "同一任务在单次修改中只能出现一次", path: ["operations"] });
    }),
  }),
  shift_plan_schedule: z.object({
    from_date: isoDate, to_date: isoDate, shift_days: z.number().int().min(1).max(14), ...subjectFields,
    preserve_rest_days: z.boolean().default(true), reason: z.string().trim().min(1), dry_run: z.boolean().default(false),
  }).refine((value) => value.to_date >= value.from_date, { message: "结束日期不能早于开始日期" })
    .refine((value) => !(value.subject_id && value.subject_name), { message: "subject_id 与 subject_name 不能同时提供" }),
  get_study_summary: z.object({
    days: z.number().int().min(1).max(30).default(7), subject_id: uuid.optional(), subject_name: z.string().trim().min(1).optional(),
  }).refine((value) => !(value.subject_id && value.subject_name), { message: "subject_id 与 subject_name 不能同时提供" }),
  log_study_session: z.object({
    session_id: uuid, ...subjectFields, activity_type: activityType, duration_minutes: z.number().int().min(5).max(480),
    focus_score: z.number().int().min(1).max(3).default(2), started_at: z.string().datetime().optional(), note: z.string().max(50).optional(),
  }),
};

function dateSpanDays(startDate: string, endDate: string): number {
  return Math.floor((Date.parse(`${endDate}T00:00:00Z`) - Date.parse(`${startDate}T00:00:00Z`)) / 86_400_000) + 1;
}
