import type { SupabaseClient } from "@supabase/supabase-js";

export class ToolError extends Error {
  constructor(
    readonly error_code: string,
    message: string,
    readonly hint_for_ai?: string,
    readonly conflict_items?: unknown,
  ) {
    super(message);
  }
}

export async function resolveSubjectId(
  supabase: SupabaseClient,
  userId: string,
  selector: { subject_id?: string; subject_name?: string },
): Promise<string> {
  if (selector.subject_id && selector.subject_name) {
    throw new ToolError("ERR_SUBJECT_AMBIGUOUS", "subject_id 与 subject_name 不能同时提供");
  }
  if (selector.subject_id) {
    const { data, error } = await supabase.from("subjects").select("id,name").eq("user_id", userId)
      .eq("id", selector.subject_id).eq("is_archived", false).maybeSingle();
    if (error) throw new ToolError("ERR_DATABASE", error.message);
    if (!data) throw new ToolError("ERR_SUBJECT_NOT_FOUND", "科目不存在或已归档", await subjectHint(supabase, userId));
    return data.id;
  }

  const normalizedName = selector.subject_name?.trim();
  if (!normalizedName) throw new ToolError("ERR_SUBJECT_NOT_FOUND", "必须提供有效的科目 ID 或名称", await subjectHint(supabase, userId));

  const { data, error } = await supabase.from("subjects").select("id,name")
    .eq("user_id", userId).eq("is_archived", false);
  if (error) throw new ToolError("ERR_DATABASE", error.message);
  const matchingSubjects = (data ?? []).filter((subject) => subject.name.trim().toLocaleLowerCase() === normalizedName.toLocaleLowerCase());
  if (matchingSubjects.length === 1) return matchingSubjects[0]!.id;
  if (matchingSubjects.length > 1) throw new ToolError("ERR_SUBJECT_AMBIGUOUS", `科目名称“${normalizedName}”匹配到多个科目`, await subjectHint(supabase, userId));
  throw new ToolError("ERR_SUBJECT_NOT_FOUND", `科目“${normalizedName}”不存在或已归档`, await subjectHint(supabase, userId));
}

async function subjectHint(supabase: SupabaseClient, userId: string): Promise<string> {
  const { data } = await supabase.from("subjects").select("id,name").eq("user_id", userId).eq("is_archived", false);
  const names = (data ?? []).map((subject) => `${subject.name} (${subject.id})`).join(", ") || "无已配置科目";
  return `当前可用科目：${names}。请修正名称或先调用 upsert_profile_and_subjects。`;
}

export function toToolError(error: unknown): ToolError {
  if (error instanceof ToolError) return error;
  const databaseError = error as { code?: string; details?: string; hint?: string };
  const message = error instanceof Error
    ? error.message
    : typeof (error as { message?: unknown })?.message === "string"
      ? (error as { message: string }).message
      : String(error);
  try {
    const parsed = JSON.parse(message) as { error_code?: string; message?: string; hint_for_ai?: string; conflict_items?: unknown };
    if (parsed.error_code) return new ToolError(parsed.error_code, parsed.message ?? message, parsed.hint_for_ai, parsed.conflict_items);
  } catch {
    // Supabase/Postgres usually returns a plain error message.
  }
  let details: unknown;
  if (databaseError.details) {
    try {
      details = JSON.parse(databaseError.details);
    } catch {
      details = databaseError.details;
    }
  }
  const businessCode = message.match(/\bERR_[A-Z0-9_]+\b/)?.[0];
  if (businessCode) {
    const detail = message.slice(message.indexOf(businessCode) + businessCode.length).replace(/^[:\s|]+/, "").trim();
    return new ToolError(businessCode, detail || message, databaseError.hint ?? errorHint(businessCode), details);
  }
  return new ToolError(databaseError.code ? `ERR_DB_${databaseError.code}` : "ERR_INTERNAL", message, undefined, details);
}

function errorHint(code: string): string | undefined {
  const hints: Record<string, string> = {
    ERR_SUBJECT_NOT_FOUND: "请重新读取有效科目列表，修正科目名称或先建档。",
    ERR_VERSION_CONFLICT: "请重新读取最新实体及 version，再重试本次改动。",
    ERR_TASK_LOCKED_OR_DONE: "请跳过锁定或已完成任务，必要时新增补充任务。",
    ERR_REST_DAY_VIOLATION: "请检查 rest_days 规则并调整日期或建议时段。",
    ERR_DAILY_CAPACITY_EXCEEDED: "请减少当日任务或拆分到其他可用日期。",
    ERR_REVERT_ORDER_CONFLICT: "请先确认是否连同较新的计划变更一并撤回。",
  };
  return hints[code];
}
