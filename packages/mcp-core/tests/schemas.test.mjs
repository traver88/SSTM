import assert from "node:assert/strict";
import test from "node:test";
import { schemas } from "../dist/schemas.js";

test("registers all eleven planned MCP tools", () => {
  assert.equal(Object.keys(schemas).length, 11);
});

test("rejects invalid calendar dates and month ranges longer than 35 days", () => {
  const invalidDate = schemas.get_monthly_plan.safeParse({
    start_date: "2026-02-30", end_date: "2026-03-01", detail_level: "summary",
  });
  const longRange = schemas.get_monthly_plan.safeParse({
    start_date: "2026-01-01", end_date: "2026-02-05", detail_level: "full",
  });
  assert.equal(invalidDate.success, false);
  assert.equal(longRange.success, false);
});

test("requires a stable session ID for idempotent study logging", () => {
  const result = schemas.log_study_session.safeParse({
    subject_name: "数学一", activity_type: "output", duration_minutes: 45,
  });
  assert.equal(result.success, false);
});

test("limits a batch plan write to ten days", () => {
  const days = Array.from({ length: 11 }, (_, index) => ({
    plan_date: `2026-01-${String(index + 1).padStart(2, "0")}`,
    tasks: [],
  }));
  const result = schemas.batch_upsert_plan_range.safeParse({
    mode: "append", reason: "生成计划", days,
  });
  assert.equal(result.success, false);
});
