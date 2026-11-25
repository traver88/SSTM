-- Core schema for Kaoyan AI Planner.
-- Apply with Supabase CLI after linking a project.

create extension if not exists pgcrypto with schema extensions;

create or replace function public.set_updated_at()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

create table if not exists public.user_profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  exam_date date,
  daily_target_minutes integer not null default 480 check (daily_target_minutes between 60 and 960),
  daily_hard_cap_minutes integer not null default 720 check (daily_hard_cap_minutes between 60 and 960),
  rest_days jsonb not null default '[]'::jsonb check (jsonb_typeof(rest_days) = 'array'),
  timezone text not null default 'Asia/Shanghai',
  updated_at timestamptz not null default now()
);

create table if not exists public.subjects (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null check (length(trim(name)) between 1 and 80),
  color text not null default '#3B82F6' check (color ~ '^#[0-9A-Fa-f]{6}$'),
  target_ratio integer not null default 25 check (target_ratio between 0 and 100),
  daily_max_minutes integer not null default 210 check (daily_max_minutes between 1 and 960),
  require_daily_streak boolean not null default false,
  is_archived boolean not null default false,
  updated_at timestamptz not null default now()
);
create index if not exists subjects_user_archived_idx on public.subjects(user_id, is_archived);
create unique index if not exists subjects_user_name_active_uidx
  on public.subjects(user_id, lower(trim(name))) where is_archived = false;
create unique index if not exists subjects_user_id_uidx on public.subjects(user_id, id);

create table if not exists public.exam_phases (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null check (length(trim(name)) between 1 and 120),
  order_index smallint not null check (order_index between 1 and 8),
  start_date date not null,
  end_date date not null,
  target_hours integer not null default 150 check (target_hours >= 0),
  core_goal text not null default '',
  subject_milestones jsonb not null default '[]'::jsonb check (jsonb_typeof(subject_milestones) = 'array'),
  remind_before_days integer[] not null default array[7, 0],
  reminder_enabled boolean not null default true,
  status text not null default 'upcoming' check (status in ('upcoming', 'active', 'completed')),
  version integer not null default 1 check (version > 0),
  deleted_at timestamptz,
  updated_at timestamptz not null default now(),
  check (end_date >= start_date),
  check (array_position(remind_before_days, null) is null),
  unique (user_id, id)
);
create index if not exists exam_phases_user_order_live_idx on public.exam_phases(user_id, order_index) where deleted_at is null;
create index if not exists exam_phases_user_updated_idx on public.exam_phases(user_id, updated_at desc);
create unique index if not exists exam_phases_one_active_uidx on public.exam_phases(user_id)
  where status = 'active' and deleted_at is null;

create table if not exists public.monthly_plans (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  phase_id uuid references public.exam_phases(id) on delete set null,
  title text not null check (length(trim(title)) between 1 and 160),
  start_date date not null,
  end_date date not null,
  status text not null default 'active' check (status in ('active', 'archived')),
  subject_hour_quotas jsonb not null default '{}'::jsonb check (jsonb_typeof(subject_hour_quotas) = 'object'),
  weekly_milestones jsonb not null default '[]'::jsonb check (jsonb_typeof(weekly_milestones) = 'array'),
  strategy_note text,
  renew_remind_before_days integer not null default 3 check (renew_remind_before_days between 0 and 30),
  version integer not null default 1 check (version > 0),
  deleted_at timestamptz,
  updated_at timestamptz not null default now(),
  check (end_date >= start_date),
  unique (user_id, id),
  constraint monthly_plans_phase_owner_fk foreign key (user_id, phase_id)
    references public.exam_phases(user_id, id) on delete set null (phase_id)
);
create index if not exists monthly_plans_user_status_start_idx on public.monthly_plans(user_id, status, start_date desc);
create index if not exists monthly_plans_user_updated_idx on public.monthly_plans(user_id, updated_at desc);
create unique index if not exists monthly_plans_one_active_uidx on public.monthly_plans(user_id)
  where status = 'active' and deleted_at is null;

create table if not exists public.daily_plans (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  monthly_plan_id uuid references public.monthly_plans(id) on delete set null,
  plan_date date not null,
  subject_id uuid not null references public.subjects(id),
  title text not null check (length(trim(title)) between 1 and 240),
  activity_type text not null check (activity_type in ('input', 'output', 'review')),
  granularity text not null default 'detailed' check (granularity in ('detailed', 'outline')),
  planned_minutes integer not null check (planned_minutes between 5 and 480),
  scheduled_start_time time,
  priority smallint not null default 2 check (priority in (1, 2, 3)),
  status text not null default 'todo' check (status in ('todo', 'in_progress', 'done')),
  is_locked boolean not null default false,
  version integer not null default 1 check (version > 0),
  tags text[] not null default '{}',
  source text not null default 'manual' check (source in ('manual', 'ai_agent')),
  ai_reason text,
  deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, id),
  constraint daily_plans_subject_owner_fk foreign key (user_id, subject_id)
    references public.subjects(user_id, id),
  constraint daily_plans_month_owner_fk foreign key (user_id, monthly_plan_id)
    references public.monthly_plans(user_id, id) on delete set null (monthly_plan_id)
);
create index if not exists daily_plans_user_date_live_idx on public.daily_plans(user_id, plan_date) where deleted_at is null;
create index if not exists daily_plans_user_updated_idx on public.daily_plans(user_id, updated_at desc);

-- Monthly plans are included in revision scope so changing/archiving a blueprint can be undone.
create table if not exists public.plan_revisions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  scope text not null check (scope in ('daily_plans', 'exam_phases', 'monthly_plans')),
  monthly_plan_id uuid references public.monthly_plans(id) on delete set null,
  actor text not null check (actor in ('mcp_agent', 'user')),
  tool_name text not null,
  change_summary text not null,
  reason text not null,
  affected_date_range text not null default '',
  before_snapshot jsonb not null default '{}'::jsonb,
  created_ids uuid[] not null default '{}',
  after_diff jsonb not null default '[]'::jsonb,
  is_read boolean not null default false,
  is_reverted boolean not null default false,
  created_at timestamptz not null default now()
);
create index if not exists plan_revisions_user_scope_created_idx on public.plan_revisions(user_id, scope, created_at desc, id desc);
create index if not exists plan_revisions_unread_idx on public.plan_revisions(user_id, is_read) where is_read = false;

create table if not exists public.study_sessions (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  subject_id uuid not null references public.subjects(id),
  plan_id uuid references public.daily_plans(id) on delete set null,
  activity_type text not null check (activity_type in ('input', 'output', 'review')),
  started_at timestamptz not null,
  ended_at timestamptz not null,
  duration_seconds integer not null check (duration_seconds >= 300),
  focus_score smallint not null default 2 check (focus_score in (1, 2, 3)),
  note text check (note is null or char_length(note) <= 50),
  source text not null default 'timer' check (source in ('timer', 'manual', 'ai_agent')),
  updated_at timestamptz not null default now(),
  check (ended_at >= started_at),
  constraint study_sessions_subject_owner_fk foreign key (user_id, subject_id)
    references public.subjects(user_id, id),
  constraint study_sessions_plan_owner_fk foreign key (user_id, plan_id)
    references public.daily_plans(user_id, id) on delete set null (plan_id)
);
create index if not exists study_sessions_user_started_idx on public.study_sessions(user_id, started_at desc);
create index if not exists study_sessions_user_subject_started_idx on public.study_sessions(user_id, subject_id, started_at desc);

create table if not exists public.user_api_keys (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  key_prefix text not null,
  key_hash text not null unique,
  last_used_at timestamptz,
  revoked_at timestamptz,
  created_at timestamptz not null default now()
);
create index if not exists user_api_keys_user_active_idx on public.user_api_keys(user_id, created_at desc) where revoked_at is null;

do $$
declare
  table_name text;
begin
  foreach table_name in array array['user_profiles', 'subjects', 'exam_phases', 'monthly_plans', 'daily_plans', 'study_sessions']
  loop
    execute format('drop trigger if exists set_updated_at on public.%I', table_name);
    execute format('create trigger set_updated_at before update on public.%I for each row execute function public.set_updated_at()', table_name);
  end loop;
end;
$$;

alter table public.user_profiles enable row level security;
alter table public.subjects enable row level security;
alter table public.exam_phases enable row level security;
alter table public.monthly_plans enable row level security;
alter table public.daily_plans enable row level security;
alter table public.plan_revisions enable row level security;
alter table public.study_sessions enable row level security;
alter table public.user_api_keys enable row level security;

drop policy if exists user_profiles_select_self on public.user_profiles;
create policy user_profiles_select_self on public.user_profiles for select to authenticated using (id = (select auth.uid()));
drop policy if exists user_profiles_insert_self on public.user_profiles;
create policy user_profiles_insert_self on public.user_profiles for insert to authenticated with check (id = (select auth.uid()));
drop policy if exists user_profiles_update_self on public.user_profiles;
create policy user_profiles_update_self on public.user_profiles for update to authenticated using (id = (select auth.uid())) with check (id = (select auth.uid()));

do $$
declare
  table_name text;
begin
  foreach table_name in array array['subjects', 'exam_phases', 'monthly_plans', 'daily_plans', 'plan_revisions', 'study_sessions']
  loop
    execute format('drop policy if exists %I on public.%I', table_name || '_self_access', table_name);
    execute format(
      'create policy %I on public.%I for all to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()))',
      table_name || '_self_access', table_name
    );
  end loop;
end;
$$;

drop policy if exists user_api_keys_self_access on public.user_api_keys;
create policy user_api_keys_self_access on public.user_api_keys for all to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

grant select, insert, update on public.user_profiles to authenticated;
grant select, insert, update on public.subjects, public.exam_phases, public.monthly_plans,
  public.study_sessions to authenticated;
-- Mobile task inserts must use the guarded transaction RPC. Direct task updates are limited
-- to execution state and tombstones, while planned content remains under the planning RPC.
grant select on public.daily_plans to authenticated;
grant update (status, is_locked, version, deleted_at) on public.daily_plans to authenticated;
grant select on public.plan_revisions to authenticated;
grant update (is_read) on public.plan_revisions to authenticated;
-- Key hashes are server-only. Exposing this table directly to the mobile role is unnecessary.
revoke all on public.user_api_keys from anon, authenticated;

do $$
begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    if not exists (select 1 from pg_publication_tables where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'exam_phases') then
      alter publication supabase_realtime add table public.exam_phases;
    end if;
    if not exists (select 1 from pg_publication_tables where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'monthly_plans') then
      alter publication supabase_realtime add table public.monthly_plans;
    end if;
    if not exists (select 1 from pg_publication_tables where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'daily_plans') then
      alter publication supabase_realtime add table public.daily_plans;
    end if;
    if not exists (select 1 from pg_publication_tables where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'plan_revisions') then
      alter publication supabase_realtime add table public.plan_revisions;
    end if;
  end if;
end;
$$;
