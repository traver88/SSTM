import type { SupabaseClient } from "@supabase/supabase-js";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { resolveSubjectId, toToolError } from "./subjectResolver.js";
import { schemas } from "./schemas.js";

type JsonObject = Record<string, unknown>;
type ToolInput = Record<string, any>;
type Handler = (input: ToolInput) => Promise<unknown>;

export function registerTools(server: McpServer, supabase: SupabaseClient, userId: string): void {
  const register = (name: keyof typeof schemas, description: string, handler: Handler) => {
    const schema = schemas[name] as z.ZodTypeAny;
    server.registerTool(name, { description, inputSchema: schema }, async (rawInput: ToolInput) => {
      try {
        const input = schema.parse(rawInput) as ToolInput;
        const value = await handler(input);
        return { content: [{ type: "text" as const, text: JSON.stringify({ ok: true, data: value }) }] };
      } catch (error) {
        if (error instanceof z.ZodError) {
          return {
            isError: true,
            content: [{ type: "text" as const, text: JSON.stringify({
              ok: false,
              error_code: "ERR_INVALID_INPUT",
              message: "输入参数未通过校验",
              hint_for_ai: error.issues.map((issue) => `${issue.path.join(".") || "input"}: ${issue.message}`).join("; "),
              conflict_items: error.issues.map((issue) => ({ path: issue.path, message: issue.message })),
            }) }],
          };
        }
        const parsed = toToolError(error);
        return {
          isError: true,
          content: [{ type: "text" as const, text: JSON.stringify({
            ok: false,
            error_code: parsed.error_code,
            message: parsed.message,
            ...(parsed.hint_for_ai ? { hint_for_ai: parsed.hint_for_ai } : {}),
            ...(parsed.conflict_items ? { conflict_items: parsed.conflict_items } : {}),
          }) }],
        };
      }
    });
  };

  register("upsert_profile_and_subjects", "Create or update the user's exam profile and subjects.", async (input) => {
    const subjects = input.subjects ?? [];
    const { data, error } = await supabase.rpc("rpc_upsert_profile_and_subjects_tx", {
      p_user_id: userId,
      p_profile: {
        exam_date: input.exam_date,
        daily_target_minutes: input.daily_target_minutes,
        daily_hard_cap_minutes: input.daily_hard_cap_minutes,
        rest_days: input.rest_days,
        timezone: input.timezone,
      },
      p_subjects: subjects,
    });
    if (error) throw error;
    return data;
  });

  register("get_exam_roadmap", "Read the exam date, subjects, rest-day rules, phases, and phase study hours.", async () => {
    const [{ data: profile, error: profileError }, { data: subjects, error: subjectError }, { data: phases, error: phaseError }] = await Promise.all([
      supabase.from("user_profiles").select("*").eq("id", userId).maybeSingle(),
      supabase.from("subjects").select("id,name,color,target_ratio,daily_max_minutes,is_archived").eq("user_id", userId).eq("is_archived", false).order("name"),
      supabase.from("exam_phases").select("*").eq("user_id", userId).is("deleted_at", null).order("order_index"),
    ]);
    if (profileError) throw profileError;
    if (subjectError) throw subjectError;
    if (phaseError) throw phaseError;

    const hoursByPhase = await getPhaseHours(supabase, userId, phases ?? []);
    return {
      profile,
      days_until_exam: profile?.exam_date ? dateDiffInDays(todayInTimeZone(profile.timezone ?? "Asia/Shanghai"), profile.exam_date) : null,
      subjects,
      phases: (phases ?? []).map((phase) => ({ ...phase, logged_hours: hoursByPhase[phase.id] ?? 0 })),
    };
  });

  register("upsert_exam_roadmap", "Create or replace the user's full exam phase roadmap.", async (input) => {
    const phases = await resolvePhaseMilestones(supabase, userId, input.phases);
    return rpc(supabase, "rpc_upsert_roadmap_tx", {
      p_user_id: userId, p_actor: "mcp_agent", p_reason: input.reason, p_phases_json: phases, p_dry_run: input.dry_run,
    });
  });

  register("patch_exam_phase", "Update one phase with optimistic version checking.", async (input) => {
    const fields = { ...input.fields };
    if (fields.subject_milestones) fields.subject_milestones = await resolveMilestones(supabase, userId, fields.subject_milestones);
    return rpc(supabase, "rpc_patch_exam_phase_tx", {
      p_user_id: userId, p_actor: "mcp_agent", p_phase_id: input.phase_id, p_expected_version: input.expected_version,
      p_reason: input.reason, p_cascade_subsequent: input.cascade_subsequent, p_fields: fields, p_dry_run: input.dry_run,
    });
  });

  register("get_monthly_plan", "Read the monthly blueprint and daily plan summaries or task details.", async (input) => {
    let subjectId: string | undefined;
    if (input.subject_id || input.subject_name) subjectId = await resolveSubjectId(supabase, userId, input);
    const [{ data: plans, error: planError }, { data: tasks, error: taskError }, { data: profile, error: profileError }] = await Promise.all([
      supabase.from("monthly_plans").select("*").eq("user_id", userId).eq("status", "active")
        .is("deleted_at", null).lte("start_date", input.end_date).gte("end_date", input.start_date).order("start_date", { ascending: false }),
      supabase.from("daily_plans").select("*").eq("user_id", userId).is("deleted_at", null)
        .gte("plan_date", input.start_date).lte("plan_date", input.end_date).order("plan_date").order("scheduled_start_time"),
      supabase.from("user_profiles").select("timezone").eq("id", userId).maybeSingle(),
    ]);
    if (planError) throw planError;
    if (taskError) throw taskError;
    if (profileError) throw profileError;
    const selectedTasks = subjectId ? (tasks ?? []).filter((task) => task.subject_id === subjectId) : (tasks ?? []);
    const today = todayInTimeZone(profile?.timezone ?? "Asia/Shanghai");
    const fullTasks = selectedTasks.map((task) => ({ ...task, is_overdue: ["todo", "in_progress"].includes(task.status) && task.plan_date < today }));
    if (input.detail_level === "full") return { monthly_plans: plans ?? [], tasks: fullTasks };

    const grouped = new Map<string, { task_count: number; planned_minutes: number; tasks_done: number }>();
    for (const task of fullTasks) {
      const day = grouped.get(task.plan_date) ?? { task_count: 0, planned_minutes: 0, tasks_done: 0 };
      day.task_count += 1;
      day.planned_minutes += task.planned_minutes;
      if (task.status === "done") day.tasks_done += 1;
      grouped.set(task.plan_date, day);
    }
    return { monthly_plans: plans ?? [], daily_summary: eachDate(input.start_date, input.end_date).map((plan_date) => ({
      plan_date,
      ...(grouped.get(plan_date) ?? { task_count: 0, planned_minutes: 0, tasks_done: 0 }),
    })) };
  });

  register("upsert_monthly_blueprint", "Create or update a 30-day monthly plan blueprint.", async (input) => {
    const quotas: Record<string, number> = {};
    for (const [key, hours] of Object.entries(input.subject_hour_quotas as Record<string, number>)) {
      const isUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(key);
      const subject_id = isUuid ? await resolveSubjectId(supabase, userId, { subject_id: key }) : await resolveSubjectId(supabase, userId, { subject_name: key });
      quotas[subject_id] = hours;
    }
    return rpc(supabase, "rpc_upsert_monthly_blueprint_tx", {
      p_user_id: userId, p_actor: "mcp_agent", p_monthly_plan_id: input.monthly_plan_id ?? null,
      p_phase_id: input.phase_id ?? null, p_blueprint: {
        title: input.title, start_date: input.start_date, end_date: input.end_date, subject_hour_quotas: quotas,
        weekly_milestones: input.weekly_milestones, strategy_note: input.strategy_note,
      },
      p_reason: input.reason, p_dry_run: input.dry_run,
    });
  });

  register("batch_upsert_plan_range", "Create or replace AI-owned plan tasks for up to ten days.", async (input) => {
    const days = await Promise.all(input.days.map(async (day: ToolInput) => ({
      ...day,
      tasks: await Promise.all(day.tasks.map(async (task: ToolInput) => ({
        ...task, subject_id: await resolveSubjectId(supabase, userId, task), source: "ai_agent",
      }))),
    })));
    return rpc(supabase, "rpc_batch_upsert_plan_range_tx", {
      p_user_id: userId, p_actor: "mcp_agent", p_monthly_plan_id: input.monthly_plan_id ?? null,
      p_mode: input.mode, p_reason: input.reason, p_days: days, p_dry_run: input.dry_run,
    });
  });

  register("patch_plan_tasks", "Add, update, move, or soft-delete tasks with version and scheduling guardrails.", async (input) => {
    const operations = await Promise.all(input.operations.map(async (operation: ToolInput) => {
      if (operation.op !== "add") return operation;
      return { ...operation, subject_id: await resolveSubjectId(supabase, userId, operation), source: "ai_agent" };
    }));
    return rpc(supabase, "rpc_apply_plan_patch_tx", {
      p_user_id: userId, p_actor: "mcp_agent", p_reason: input.reason, p_operations_json: operations, p_dry_run: input.dry_run,
    });
  });

  register("shift_plan_schedule", "Shift unfinished, unlocked tasks while preserving rest-day and capacity rules.", async (input) => {
    const subject_id = input.subject_id || input.subject_name ? await resolveSubjectId(supabase, userId, input) : null;
    return rpc(supabase, "rpc_shift_plan_schedule_tx", {
      p_user_id: userId, p_actor: "mcp_agent", p_from_date: input.from_date, p_to_date: input.to_date,
      p_shift_days: input.shift_days, p_subject_id: subject_id, p_preserve_rest_days: input.preserve_rest_days,
      p_reason: input.reason, p_dry_run: input.dry_run,
    });
  });

  register("get_study_summary", "Read recent study time, activity mix, focus patterns, and overdue tasks.", async (input) => {
    const subject_id = input.subject_id || input.subject_name ? await resolveSubjectId(supabase, userId, input) : null;
    return rpc(supabase, "rpc_get_study_summary", { p_user_id: userId, p_days: input.days, p_subject_id: subject_id });
  });

  register("log_study_session", "Idempotently record study completed outside the timer.", async (input) => {
    const subject_id = await resolveSubjectId(supabase, userId, input);
    return rpc(supabase, "rpc_log_study_session_tx", {
      p_user_id: userId, p_session_id: input.session_id, p_subject_id: subject_id,
      p_activity_type: input.activity_type, p_duration_seconds: input.duration_minutes * 60,
      p_focus_score: input.focus_score, p_started_at: input.started_at ?? null, p_note: input.note ?? null,
    });
  });
}

async function rpc(supabase: SupabaseClient, functionName: string, params: JsonObject): Promise<unknown> {
  const { data, error } = await supabase.rpc(functionName, params);
  if (error) throw error;
  if (data && typeof data === "object" && "ok" in data && data.ok === false) {
    const result = data as { error_code?: string; message?: string; hint_for_ai?: string; conflict_items?: unknown };
    throw new Error(JSON.stringify(result));
  }
  return data;
}

async function resolveMilestones(supabase: SupabaseClient, userId: string, milestones: ToolInput[]): Promise<ToolInput[]> {
  return Promise.all(milestones.map(async (milestone) => {
    if (milestone.subject_id || milestone.subject_name) {
      const subject_id = await resolveSubjectId(supabase, userId, milestone);
      const { subject_name: _subjectName, ...rest } = milestone;
      return { ...rest, subject_id };
    }
    return milestone;
  }));
}

async function resolvePhaseMilestones(supabase: SupabaseClient, userId: string, phases: ToolInput[]): Promise<ToolInput[]> {
  return Promise.all(phases.map(async (phase) => ({
    ...phase,
    subject_milestones: await resolveMilestones(supabase, userId, phase.subject_milestones),
  })));
}

async function getPhaseHours(supabase: SupabaseClient, userId: string, phases: ToolInput[]): Promise<Record<string, number>> {
  if (phases.length === 0) return {};
  const phaseIds = phases.map((phase) => phase.id);
  const { data: plans, error: plansError } = await supabase.from("monthly_plans").select("id,phase_id")
    .eq("user_id", userId).in("phase_id", phaseIds);
  if (plansError) throw plansError;
  const monthlyPlanIds = (plans ?? []).map((plan) => plan.id);
  if (monthlyPlanIds.length === 0) return {};
  const { data: dailyPlans, error: dailyPlansError } = await supabase.from("daily_plans").select("id,monthly_plan_id")
    .eq("user_id", userId).is("deleted_at", null).in("monthly_plan_id", monthlyPlanIds);
  if (dailyPlansError) throw dailyPlansError;
  const monthlyPlanToPhase = new Map((plans ?? []).map((plan) => [plan.id, plan.phase_id]));
  const dailyPlanToPhase = new Map((dailyPlans ?? []).map((plan) => [plan.id, monthlyPlanToPhase.get(plan.monthly_plan_id)]));
  const dailyPlanIds = [...dailyPlanToPhase.keys()];
  if (dailyPlanIds.length === 0) return {};
  const { data: sessions, error: sessionsError } = await supabase.from("study_sessions").select("plan_id,duration_seconds")
    .eq("user_id", userId).in("plan_id", dailyPlanIds);
  if (sessionsError) throw sessionsError;
  const result: Record<string, number> = {};
  for (const session of sessions ?? []) {
    if (!session.plan_id) continue;
    const phaseId = dailyPlanToPhase.get(session.plan_id);
    if (phaseId) result[phaseId] = (result[phaseId] ?? 0) + session.duration_seconds / 3600;
  }
  return result;
}

function eachDate(startDate: string, endDate: string): string[] {
  const dates: string[] = [];
  for (let timestamp = Date.parse(`${startDate}T00:00:00Z`); timestamp <= Date.parse(`${endDate}T00:00:00Z`); timestamp += 86_400_000) {
    dates.push(new Date(timestamp).toISOString().slice(0, 10));
  }
  return dates;
}

function dateDiffInDays(from: string, to: string): number {
  const fromDate = new Date(`${from}T00:00:00Z`).getTime();
  const toDate = new Date(`${to}T00:00:00Z`).getTime();
  return Math.ceil((toDate - fromDate) / 86_400_000);
}

function todayInTimeZone(timeZone: string): string {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(new Date());
  const year = parts.find((part) => part.type === "year")?.value;
  const month = parts.find((part) => part.type === "month")?.value;
  const day = parts.find((part) => part.type === "day")?.value;
  return `${year}-${month}-${day}`;
}
