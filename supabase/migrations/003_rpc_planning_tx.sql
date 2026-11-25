create or replace function public.rpc_lock_user(p_user_id uuid)
returns void
language sql
volatile
security definer
set search_path = pg_catalog, public
as $$
  select pg_advisory_xact_lock(hashtextextended(p_user_id::text, 20261124));
$$;

create or replace function public.rpc_assert_task_slot(
  p_user_id uuid,
  p_plan_date date,
  p_start_time time,
  p_minutes integer,
  p_exclude_id uuid default null,
  p_ignore_replaceable_ai boolean default false
)
returns void
language plpgsql
volatile
security definer
set search_path = pg_catalog, public
as $$
declare
  v_rest jsonb;
  v_weekday integer := extract(isodow from p_plan_date)::integer;
  v_periods text[];
  v_is_all_day boolean;
  v_cap integer;
  v_task_count integer;
  v_used_minutes integer;
  v_period text;
  v_capacity_periods text[];
  v_check_date date;
  v_start_ts timestamp;
  v_end_ts timestamp;
  v_period_start time;
  v_period_end time;
begin
  select rest_days, daily_hard_cap_minutes
    into v_rest, v_cap from public.user_profiles where id = p_user_id;
  v_rest := coalesce(v_rest, '[]'::jsonb);
  v_periods := array(
    select distinct value->>'period'
    from jsonb_array_elements(v_rest) as rest_entry(value)
    where (rest_entry.value->>'weekday')::integer = v_weekday
  );
  v_is_all_day := 'all_day' = any(v_periods) or
    (select count(*) from unnest(v_periods) p where p in ('morning', 'afternoon', 'evening')) >= 2;
  v_capacity_periods := v_periods;
  if v_is_all_day then v_capacity_periods := array['all_day']; end if;

  if v_is_all_day then
    raise exception using errcode = 'P0001', message = 'ERR_REST_DAY_VIOLATION',
      detail = jsonb_build_object('plan_date', p_plan_date, 'rest_periods', v_periods)::text;
  end if;

  if p_start_time is not null then
    v_start_ts := p_plan_date + p_start_time;
    v_end_ts := v_start_ts + make_interval(mins => p_minutes);
    for v_check_date in
      select generate_series(p_plan_date, v_end_ts::date, interval '1 day')::date
    loop
      v_periods := array(
        select distinct value->>'period'
        from jsonb_array_elements(coalesce((select rest_days from public.user_profiles where id = p_user_id), '[]'::jsonb)) as rest_entry(value)
        where (rest_entry.value->>'weekday')::integer = extract(isodow from v_check_date)::integer
      );
      v_is_all_day := 'all_day' = any(v_periods) or
        (select count(*) from unnest(v_periods) p where p in ('morning', 'afternoon', 'evening')) >= 2;
      if v_is_all_day and v_start_ts < (v_check_date + 1)::timestamp and v_end_ts > v_check_date::timestamp then
        raise exception using errcode = 'P0001', message = 'ERR_REST_DAY_VIOLATION',
          detail = jsonb_build_object('plan_date', v_check_date, 'scheduled_start_time', p_start_time,
            'planned_minutes', p_minutes, 'rest_periods', v_periods)::text;
      end if;
      foreach v_period in array v_periods loop
        v_period_start := case v_period
          when 'morning' then time '06:00'
          when 'afternoon' then time '12:00'
          when 'evening' then time '18:00'
          else null end;
        v_period_end := case v_period
          when 'morning' then time '12:00'
          when 'afternoon' then time '18:00'
          when 'evening' then time '24:00'
          else null end;
        if v_period_start is not null
            and v_start_ts < v_check_date + v_period_end
            and v_end_ts > v_check_date + v_period_start then
          raise exception using errcode = 'P0001', message = 'ERR_REST_DAY_VIOLATION',
            detail = jsonb_build_object('plan_date', v_check_date, 'scheduled_start_time', p_start_time,
              'planned_minutes', p_minutes, 'rest_period', v_period)::text;
        end if;
      end loop;
    end loop;
  end if;

  if cardinality(v_capacity_periods) > 0 then
    v_cap := floor(coalesce(v_cap, 720) / 2.0)::integer;
  else
    v_cap := least(coalesce(v_cap, 720), 960);
  end if;

  select count(*)::integer, coalesce(sum(planned_minutes), 0)::integer
    into v_task_count, v_used_minutes
    from public.daily_plans
    where user_id = p_user_id and plan_date = p_plan_date and deleted_at is null
      and not (p_ignore_replaceable_ai and source = 'ai_agent' and not is_locked and status <> 'done')
      and id is distinct from p_exclude_id;

  if v_task_count + 1 > 20 then
    raise exception using errcode = 'P0001', message = 'ERR_DAILY_CAPACITY_EXCEEDED',
      detail = jsonb_build_object('plan_date', p_plan_date, 'task_count', v_task_count, 'task_limit', 20)::text;
  end if;
  if v_used_minutes + p_minutes > v_cap then
    raise exception using errcode = 'P0001', message = 'ERR_DAILY_CAPACITY_EXCEEDED',
      detail = jsonb_build_object('plan_date', p_plan_date, 'used_minutes', v_used_minutes,
        'requested_minutes', p_minutes, 'capacity_minutes', v_cap,
        'remaining_minutes', greatest(v_cap - v_used_minutes, 0))::text;
  end if;
end;
$$;

create or replace function public.rpc_mobile_add_plan_task_tx(
  p_user_id uuid,
  p_task jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_id uuid;
  v_plan_date date;
  v_subject_id uuid;
  v_minutes integer;
  v_start_time time;
  v_existing public.daily_plans%rowtype;
begin
  perform public.rpc_assert_user(p_user_id);
  perform public.rpc_lock_user(p_user_id);
  if jsonb_typeof(p_task) <> 'object' then
    raise exception using errcode = '22023', message = 'task must be an object';
  end if;
  v_id := nullif(p_task->>'id', '')::uuid;
  v_plan_date := nullif(p_task->>'plan_date', '')::date;
  v_subject_id := nullif(p_task->>'subject_id', '')::uuid;
  v_minutes := nullif(p_task->>'planned_minutes', '')::integer;
  v_start_time := nullif(p_task->>'scheduled_start_time', '')::time;
  if v_id is null or v_plan_date is null or v_subject_id is null or v_minutes is null then
    raise exception using errcode = '22023', message = 'task id, plan_date, subject_id, and planned_minutes are required';
  end if;
  select * into v_existing from public.daily_plans where id = v_id for update;
  if found then
    if v_existing.user_id = p_user_id and v_existing.deleted_at is null then
      return jsonb_build_object('task_id', v_id, 'duplicate', true);
    end if;
    raise exception using errcode = 'P0001', message = 'ERR_TASK_ID_CONFLICT', detail = v_id::text;
  end if;
  if not exists (select 1 from public.subjects where id = v_subject_id and user_id = p_user_id and is_archived = false) then
    raise exception using errcode = 'P0001', message = 'ERR_SUBJECT_NOT_FOUND', detail = v_subject_id::text;
  end if;
  if nullif(p_task->>'monthly_plan_id', '') is not null and not exists (
    select 1 from public.monthly_plans where id = (p_task->>'monthly_plan_id')::uuid
      and user_id = p_user_id and status = 'active' and deleted_at is null
      and v_plan_date between start_date and end_date
  ) then
    raise exception using errcode = 'P0001', message = 'ERR_MONTHLY_PLAN_NOT_FOUND';
  end if;
  if v_minutes < 5 or v_minutes > 480 then
    raise exception using errcode = '22023', message = 'planned_minutes must be between 5 and 480';
  end if;
  perform public.rpc_assert_task_slot(p_user_id, v_plan_date, v_start_time, v_minutes, null);
  insert into public.daily_plans(user_id, id, monthly_plan_id, plan_date, subject_id, title,
    activity_type, granularity, planned_minutes, scheduled_start_time, priority, tags, source, ai_reason)
  values (p_user_id, v_id, nullif(p_task->>'monthly_plan_id', '')::uuid, v_plan_date, v_subject_id,
    p_task->>'title', p_task->>'activity_type', coalesce(p_task->>'granularity', 'detailed'),
    v_minutes, v_start_time, coalesce(nullif(p_task->>'priority', '')::smallint, 2),
    coalesce(array(select jsonb_array_elements_text(p_task->'tags')), '{}'), 'manual', p_task->>'ai_reason');
  return jsonb_build_object('task_id', v_id, 'duplicate', false);
end;
$$;

create or replace function public.rpc_upsert_roadmap_tx(
  p_user_id uuid,
  p_actor text,
  p_reason text,
  p_phases_json jsonb,
  p_dry_run boolean default false
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_phase jsonb;
  v_phase_id uuid;
  v_existing public.exam_phases%rowtype;
  v_before jsonb := '[]'::jsonb;
  v_diff jsonb := '[]'::jsonb;
  v_created uuid[] := '{}';
  v_incoming_ids uuid[] := '{}';
  v_prev_end date;
  v_order integer := 0;
  v_active_count integer;
  v_start date;
  v_end date;
  v_prev_order integer := 0;
  v_new_version integer;
  v_milestone jsonb;
begin
  perform public.rpc_assert_user(p_user_id);
  perform public.rpc_lock_user(p_user_id);
  if jsonb_typeof(p_phases_json) <> 'array' or jsonb_array_length(p_phases_json) < 1
      or jsonb_array_length(p_phases_json) > 8 then
    raise exception using errcode = '22023', message = 'phases must contain between 1 and 8 items';
  end if;
  select count(*) into v_active_count from jsonb_array_elements(p_phases_json) as phase(value) where value->>'status' = 'active';
  if v_active_count > 1 then
    raise exception using errcode = '22023', message = 'at most one phase may be active';
  end if;

  for v_phase in select value from jsonb_array_elements(p_phases_json) order by (value->>'order_index')::integer
  loop
    v_order := (v_phase->>'order_index')::integer;
    v_start := (v_phase->>'start_date')::date;
    v_end := (v_phase->>'end_date')::date;
    if v_order < 1 or v_order > 8 or v_order <= v_prev_order or v_end < v_start then
      raise exception using errcode = '22023', message = 'invalid phase order or date range';
    end if;
    v_prev_order := v_order;
    if v_prev_end is not null and v_start <= v_prev_end then
      raise exception using errcode = '22023', message = 'phase date ranges overlap or are out of order';
    end if;
    for v_milestone in select value from jsonb_array_elements(coalesce(v_phase->'subject_milestones', '[]'::jsonb)) loop
      if nullif(v_milestone->>'subject_id', '') is not null and not exists (
        select 1 from public.subjects where id = (v_milestone->>'subject_id')::uuid
          and user_id = p_user_id and is_archived = false
      ) then
        raise exception using errcode = 'P0001', message = 'ERR_SUBJECT_NOT_FOUND', detail = v_milestone->>'subject_id';
      end if;
    end loop;
    v_prev_end := v_end;
    if v_phase ? 'id' and nullif(v_phase->>'id', '') is not null then
      v_phase_id := (v_phase->>'id')::uuid;
      if v_phase_id = any(v_incoming_ids) then
        raise exception using errcode = '22023', message = 'duplicate phase id';
      end if;
      v_incoming_ids := array_append(v_incoming_ids, v_phase_id);
    end if;
  end loop;

  select coalesce(jsonb_agg(jsonb_build_object('row', to_jsonb(ep),
      'after_version', ep.version + case when ep.status = 'active' then 2 else 1 end)
    order by ep.order_index), '[]'::jsonb)
    into v_before from public.exam_phases ep where ep.user_id = p_user_id and ep.deleted_at is null;

  if p_dry_run then
    return jsonb_build_object('dry_run', true, 'scope', 'exam_phases', 'before_count', jsonb_array_length(v_before),
      'requested_count', jsonb_array_length(p_phases_json), 'phases', p_phases_json);
  end if;

  -- Avoid a transient partial-index conflict when the active phase changes.
  update public.exam_phases set status = 'upcoming', version = version + 1
    where user_id = p_user_id and deleted_at is null and status = 'active';

  for v_phase in select value from jsonb_array_elements(p_phases_json) order by (value->>'order_index')::integer
  loop
    v_phase_id := nullif(v_phase->>'id', '')::uuid;
    if v_phase_id is null then
      insert into public.exam_phases(user_id, name, order_index, start_date, end_date, target_hours, core_goal,
        subject_milestones, remind_before_days, reminder_enabled, status)
      values (p_user_id, v_phase->>'name', (v_phase->>'order_index')::smallint,
        (v_phase->>'start_date')::date, (v_phase->>'end_date')::date,
        coalesce(nullif(v_phase->>'target_hours', '')::integer, 150), coalesce(v_phase->>'core_goal', ''),
        coalesce(v_phase->'subject_milestones', '[]'::jsonb),
        case when v_phase ? 'remind_before_days'
          then array(select jsonb_array_elements_text(v_phase->'remind_before_days')::integer) else array[7,0] end,
        coalesce(nullif(v_phase->>'reminder_enabled', '')::boolean, true),
        coalesce(v_phase->>'status', 'upcoming')) returning id into v_phase_id;
      v_created := array_append(v_created, v_phase_id);
      v_diff := v_diff || jsonb_build_array(jsonb_build_object('op', 'add', 'phase_id', v_phase_id, 'name', v_phase->>'name'));
    else
      select * into v_existing from public.exam_phases where id = v_phase_id and user_id = p_user_id and deleted_at is null for update;
      if not found then
        raise exception using errcode = 'P0001', message = 'ERR_PHASE_NOT_FOUND', detail = v_phase_id::text;
      end if;
      update public.exam_phases set
        name = v_phase->>'name', order_index = (v_phase->>'order_index')::smallint,
        start_date = (v_phase->>'start_date')::date, end_date = (v_phase->>'end_date')::date,
        target_hours = coalesce(nullif(v_phase->>'target_hours', '')::integer, 150),
        core_goal = coalesce(v_phase->>'core_goal', ''),
        subject_milestones = coalesce(v_phase->'subject_milestones', '[]'::jsonb),
        remind_before_days = case when v_phase ? 'remind_before_days'
          then array(select jsonb_array_elements_text(v_phase->'remind_before_days')::integer) else remind_before_days end,
        status = coalesce(v_phase->>'status', status), version = version + 1
      where id = v_phase_id and user_id = p_user_id returning version into v_new_version;
      v_diff := v_diff || jsonb_build_array(jsonb_build_object('op', 'update', 'phase_id', v_phase_id, 'name', v_phase->>'name'));
    end if;
  end loop;

  for v_existing in select * from public.exam_phases where user_id = p_user_id and deleted_at is null
  loop
    if not (v_existing.id = any(v_incoming_ids)) and not (v_existing.id = any(v_created)) then
      update public.exam_phases set deleted_at = now(), version = version + 1
        where id = v_existing.id and user_id = p_user_id;
      v_diff := v_diff || jsonb_build_array(jsonb_build_object('op', 'delete', 'phase_id', v_existing.id, 'name', v_existing.name));
    end if;
  end loop;

  insert into public.plan_revisions(user_id, scope, actor, tool_name, change_summary, reason,
    affected_date_range, before_snapshot, created_ids, after_diff)
  values (p_user_id, 'exam_phases', p_actor, 'upsert_exam_roadmap',
    format('更新 %s 个阶段', jsonb_array_length(p_phases_json)), p_reason,
    '', v_before, v_created, v_diff);

  return jsonb_build_object('dry_run', false, 'scope', 'exam_phases', 'created_ids', to_jsonb(v_created),
    'changes', v_diff);
end;
$$;

create or replace function public.rpc_patch_exam_phase_tx(
  p_user_id uuid,
  p_actor text,
  p_phase_id uuid,
  p_expected_version integer,
  p_reason text,
  p_cascade_subsequent boolean default false,
  p_fields jsonb default '{}'::jsonb,
  p_dry_run boolean default false
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_phase public.exam_phases%rowtype;
  v_delta integer := 0;
  v_before jsonb := '[]'::jsonb;
  v_diff jsonb := '[]'::jsonb;
  v_item record;
  v_milestone jsonb;
  v_new_start date;
  v_new_end date;
begin
  perform public.rpc_assert_user(p_user_id);
  perform public.rpc_lock_user(p_user_id);
  select * into v_phase from public.exam_phases where id = p_phase_id and user_id = p_user_id and deleted_at is null for update;
  if not found then raise exception using errcode = 'P0001', message = 'ERR_PHASE_NOT_FOUND'; end if;
  if v_phase.version <> p_expected_version then
    raise exception using errcode = 'P0001', message = 'ERR_VERSION_CONFLICT', detail = to_jsonb(v_phase)::text;
  end if;
  if p_fields ? 'end_date' then v_delta := (p_fields->>'end_date')::date - v_phase.end_date;
  elsif p_fields ? 'start_date' then v_delta := (p_fields->>'start_date')::date - v_phase.start_date;
  end if;
  if coalesce(p_fields->>'start_date', v_phase.start_date::text)::date > coalesce(p_fields->>'end_date', v_phase.end_date::text)::date then
    raise exception using errcode = '22023', message = 'phase end_date cannot precede start_date';
  end if;
  v_new_start := coalesce(nullif(p_fields->>'start_date', '')::date, v_phase.start_date);
  v_new_end := coalesce(nullif(p_fields->>'end_date', '')::date, v_phase.end_date);
  if exists (select 1 from public.exam_phases other where other.user_id = p_user_id
      and other.deleted_at is null and other.id <> p_phase_id
      and ((other.order_index < v_phase.order_index and other.end_date >= v_new_start)
        or (other.order_index > v_phase.order_index
          and not (p_cascade_subsequent and other.status = 'upcoming') and other.start_date <= v_new_end))) then
    raise exception using errcode = '22023', message = 'phase date ranges overlap';
  end if;
  if p_fields->>'status' = 'active' and exists (select 1 from public.exam_phases
      where user_id = p_user_id and deleted_at is null and status = 'active' and id <> p_phase_id) then
    raise exception using errcode = 'P0001', message = 'ERR_ACTIVE_PHASE_CONFLICT';
  end if;
  for v_milestone in select value from jsonb_array_elements(coalesce(p_fields->'subject_milestones', '[]'::jsonb)) loop
    if nullif(v_milestone->>'subject_id', '') is not null and not exists (
      select 1 from public.subjects where id = (v_milestone->>'subject_id')::uuid
        and user_id = p_user_id and is_archived = false
    ) then
      raise exception using errcode = 'P0001', message = 'ERR_SUBJECT_NOT_FOUND', detail = v_milestone->>'subject_id';
    end if;
  end loop;

  select jsonb_agg(jsonb_build_object('row', to_jsonb(ep), 'after_version', ep.version + 1))
    into v_before from public.exam_phases ep
    where ep.user_id = p_user_id and ep.deleted_at is null
      and (ep.id = p_phase_id or (p_cascade_subsequent and ep.order_index > v_phase.order_index and ep.status = 'upcoming'));
  v_diff := jsonb_build_array(jsonb_build_object('op', 'update', 'phase_id', p_phase_id,
    'fields', p_fields, 'cascade_days', case when p_cascade_subsequent then v_delta else 0 end));
  if p_dry_run then return jsonb_build_object('dry_run', true, 'scope', 'exam_phases', 'changes', v_diff); end if;

  update public.exam_phases set
    start_date = coalesce(nullif(p_fields->>'start_date', '')::date, start_date),
    end_date = coalesce(nullif(p_fields->>'end_date', '')::date, end_date),
    target_hours = coalesce(nullif(p_fields->>'target_hours', '')::integer, target_hours),
    core_goal = coalesce(p_fields->>'core_goal', core_goal),
    subject_milestones = coalesce(p_fields->'subject_milestones', subject_milestones),
    remind_before_days = case when p_fields ? 'remind_before_days'
      then array(select jsonb_array_elements_text(p_fields->'remind_before_days')::integer) else remind_before_days end,
    status = coalesce(p_fields->>'status', status), version = version + 1
  where id = p_phase_id and user_id = p_user_id;

  if p_cascade_subsequent and v_delta <> 0 then
    for v_item in select id, start_date, end_date from public.exam_phases
      where user_id = p_user_id and deleted_at is null and order_index > v_phase.order_index and status = 'upcoming'
      order by order_index for update
    loop
      update public.exam_phases set start_date = v_item.start_date + v_delta,
        end_date = v_item.end_date + v_delta, version = version + 1
        where id = v_item.id and user_id = p_user_id;
    end loop;
  end if;

  insert into public.plan_revisions(user_id, scope, actor, tool_name, change_summary, reason,
    before_snapshot, after_diff)
  values (p_user_id, 'exam_phases', p_actor, 'patch_exam_phase', '修改阶段及后续排期', p_reason,
    coalesce(v_before, '[]'::jsonb), v_diff);
  return jsonb_build_object('dry_run', false, 'changes', v_diff);
end;
$$;

create or replace function public.rpc_upsert_profile_and_subjects_tx(
  p_user_id uuid,
  p_profile jsonb,
  p_subjects jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_subject jsonb;
  v_subject_id uuid;
  v_name text;
begin
  perform public.rpc_assert_user(p_user_id);
  perform public.rpc_lock_user(p_user_id);
  if p_profile is null or jsonb_typeof(p_profile) <> 'object' then
    raise exception using errcode = '22023', message = 'p_profile must be an object';
  end if;
  if p_subjects is not null and jsonb_typeof(p_subjects) <> 'array' then
    raise exception using errcode = '22023', message = 'p_subjects must be an array';
  end if;
  if p_profile ? 'timezone' and not exists (select 1 from pg_timezone_names where name = p_profile->>'timezone') then
    raise exception using errcode = '22023', message = 'timezone must be a valid IANA time zone';
  end if;
  if p_profile ? 'rest_days' and jsonb_typeof(p_profile->'rest_days') <> 'array' then
    raise exception using errcode = '22023', message = 'rest_days must be an array';
  end if;
  if p_profile ? 'rest_days' and exists (
    select 1 from jsonb_array_elements(p_profile->'rest_days') as rest_entry(value)
    where coalesce(value->>'weekday', '') !~ '^[1-7]$'
      or coalesce(value->>'period', '') not in ('all_day', 'morning', 'afternoon', 'evening')
  ) then
    raise exception using errcode = '22023', message = 'rest_days contains an invalid weekday or period';
  end if;

  insert into public.user_profiles(id, exam_date, daily_target_minutes, daily_hard_cap_minutes, rest_days, timezone)
  values (
    p_user_id,
    nullif(p_profile->>'exam_date', '')::date,
    coalesce(nullif(p_profile->>'daily_target_minutes', '')::integer, 480),
    coalesce(nullif(p_profile->>'daily_hard_cap_minutes', '')::integer, 720),
    coalesce(p_profile->'rest_days', '[]'::jsonb),
    coalesce(nullif(p_profile->>'timezone', ''), 'Asia/Shanghai')
  )
  on conflict (id) do update set
    exam_date = coalesce(excluded.exam_date, public.user_profiles.exam_date),
    daily_target_minutes = case when p_profile ? 'daily_target_minutes' then excluded.daily_target_minutes else public.user_profiles.daily_target_minutes end,
    daily_hard_cap_minutes = case when p_profile ? 'daily_hard_cap_minutes' then excluded.daily_hard_cap_minutes else public.user_profiles.daily_hard_cap_minutes end,
    rest_days = case when p_profile ? 'rest_days' then excluded.rest_days else public.user_profiles.rest_days end,
    timezone = case when p_profile ? 'timezone' then excluded.timezone else public.user_profiles.timezone end;

  for v_subject in select value from jsonb_array_elements(coalesce(p_subjects, '[]'::jsonb))
  loop
    v_name := trim(v_subject->>'name');
    if v_name is null or v_name = '' then
      raise exception using errcode = '22023', message = 'subject name is required';
    end if;
    v_subject_id := nullif(v_subject->>'id', '')::uuid;
    if v_subject_id is not null then
      update public.subjects set
        name = v_name,
        color = coalesce(v_subject->>'color', color),
        target_ratio = coalesce(nullif(v_subject->>'target_ratio', '')::integer, target_ratio),
        daily_max_minutes = coalesce(nullif(v_subject->>'daily_max_minutes', '')::integer, daily_max_minutes),
        require_daily_streak = coalesce(nullif(v_subject->>'require_daily_streak', '')::boolean, require_daily_streak),
        is_archived = coalesce(nullif(v_subject->>'is_archived', '')::boolean, is_archived)
      where id = v_subject_id and user_id = p_user_id;
      if not found then
        raise exception using errcode = 'P0001', message = 'ERR_SUBJECT_NOT_FOUND', detail = v_subject_id::text;
      end if;
    else
      select id into v_subject_id from public.subjects
        where user_id = p_user_id and lower(trim(name)) = lower(v_name) and is_archived = false limit 1;
      if v_subject_id is null then
        insert into public.subjects(user_id, name, color, target_ratio, daily_max_minutes,
          require_daily_streak, is_archived)
        values (p_user_id, v_name,
          coalesce(v_subject->>'color', '#3B82F6'),
          coalesce(nullif(v_subject->>'target_ratio', '')::integer, 25),
          coalesce(nullif(v_subject->>'daily_max_minutes', '')::integer, 210),
          coalesce(nullif(v_subject->>'require_daily_streak', '')::boolean, false),
          coalesce(nullif(v_subject->>'is_archived', '')::boolean, false))
        returning id into v_subject_id;
      else
        update public.subjects set
          color = coalesce(v_subject->>'color', color),
          target_ratio = coalesce(nullif(v_subject->>'target_ratio', '')::integer, target_ratio),
          daily_max_minutes = coalesce(nullif(v_subject->>'daily_max_minutes', '')::integer, daily_max_minutes),
          require_daily_streak = coalesce(nullif(v_subject->>'require_daily_streak', '')::boolean, require_daily_streak)
        where id = v_subject_id and user_id = p_user_id;
      end if;
    end if;
  end loop;

  return jsonb_build_object(
    'profile', (select to_jsonb(p) from public.user_profiles p where p.id = p_user_id),
    'subjects', coalesce((select jsonb_agg(to_jsonb(s) order by s.name)
      from public.subjects s where s.user_id = p_user_id and s.is_archived = false), '[]'::jsonb)
  );
end;
$$;

create or replace function public.rpc_log_study_session_tx(
  p_user_id uuid,
  p_session_id uuid,
  p_subject_id uuid,
  p_activity_type text,
  p_duration_seconds integer,
  p_focus_score smallint default 2,
  p_started_at timestamptz default null,
  p_note text default null
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_session public.study_sessions%rowtype;
  v_started_at timestamptz := coalesce(p_started_at, now() - make_interval(secs => p_duration_seconds));
begin
  perform public.rpc_assert_user(p_user_id);
  if p_duration_seconds < 300 or p_duration_seconds > 28800 then
    raise exception using errcode = '22023', message = 'duration_seconds must be between 300 and 28800';
  end if;
  if p_activity_type not in ('input', 'output', 'review') then
    raise exception using errcode = '22023', message = 'invalid activity type';
  end if;
  if not exists (select 1 from public.subjects where id = p_subject_id and user_id = p_user_id and is_archived = false) then
    raise exception using errcode = 'P0001', message = 'ERR_SUBJECT_NOT_FOUND';
  end if;

  insert into public.study_sessions(id, user_id, subject_id, activity_type, started_at, ended_at,
    duration_seconds, focus_score, note, source)
  values (p_session_id, p_user_id, p_subject_id, p_activity_type, v_started_at,
    v_started_at + make_interval(secs => p_duration_seconds), p_duration_seconds,
    coalesce(p_focus_score, 2), p_note, 'ai_agent')
  on conflict (id) do nothing
  returning * into v_session;

  if v_session.id is null then
    select * into v_session from public.study_sessions where id = p_session_id and user_id = p_user_id;
    if v_session.id is null then
      raise exception using errcode = 'P0001', message = 'ERR_SESSION_ID_CONFLICT';
    end if;
    return jsonb_build_object('session', to_jsonb(v_session), 'duplicate', true);
  end if;
  return jsonb_build_object('session', to_jsonb(v_session), 'duplicate', false);
end;
$$;

create or replace function public.rpc_upsert_monthly_blueprint_tx(
  p_user_id uuid,
  p_actor text,
  p_monthly_plan_id uuid,
  p_phase_id uuid,
  p_blueprint jsonb,
  p_reason text default 'AI 更新月计划骨架',
  p_dry_run boolean default false
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_plan public.monthly_plans%rowtype;
  v_before jsonb := '[]'::jsonb;
  v_diff jsonb := '[]'::jsonb;
  v_created uuid[] := '{}';
  v_plan_id uuid := p_monthly_plan_id;
  v_plan_count integer;
  v_effective_phase_id uuid := p_phase_id;
  v_quota record;
begin
  perform public.rpc_assert_user(p_user_id);
  perform public.rpc_lock_user(p_user_id);
  if jsonb_typeof(p_blueprint) <> 'object' then
    raise exception using errcode = '22023', message = 'blueprint must be an object';
  end if;
  if (p_blueprint->>'start_date')::date > (p_blueprint->>'end_date')::date then
    raise exception using errcode = '22023', message = 'end_date cannot precede start_date';
  end if;
  if p_phase_id is not null and not exists (select 1 from public.exam_phases
    where id = p_phase_id and user_id = p_user_id and deleted_at is null) then
    raise exception using errcode = 'P0001', message = 'ERR_PHASE_NOT_FOUND';
  end if;
  if v_plan_id is not null and p_phase_id is null then
    select phase_id into v_effective_phase_id from public.monthly_plans
      where id = v_plan_id and user_id = p_user_id;
  end if;
  for v_quota in select key, value from jsonb_each(coalesce(p_blueprint->'subject_hour_quotas', '{}'::jsonb)) loop
    if v_quota.key !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      or not exists (select 1 from public.subjects where id = v_quota.key::uuid and user_id = p_user_id and not is_archived) then
      raise exception using errcode = 'P0001', message = 'ERR_SUBJECT_NOT_FOUND', detail = v_quota.key;
    end if;
  end loop;
  if v_plan_id is not null and not exists (select 1 from public.monthly_plans
    where id = v_plan_id and user_id = p_user_id and deleted_at is null) then
    raise exception using errcode = 'P0001', message = 'ERR_MONTHLY_PLAN_NOT_FOUND';
  end if;

  select count(*) into v_plan_count from public.monthly_plans
    where user_id = p_user_id and deleted_at is null and status = 'active';
  select coalesce(jsonb_agg(jsonb_build_object('row', to_jsonb(mp),
      'after_version', mp.version + case when mp.id = v_plan_id and mp.status = 'active' then 2 else 1 end)
      order by mp.start_date), '[]'::jsonb)
    into v_before from public.monthly_plans mp
    where mp.user_id = p_user_id and mp.deleted_at is null
      and (mp.status = 'active' or mp.id = v_plan_id);

  if p_dry_run then
    return jsonb_build_object('dry_run', true, 'scope', 'monthly_plans',
      'monthly_plan_id', v_plan_id, 'created', v_plan_id is null,
      'before', v_before, 'proposed', p_blueprint,
      'reason', coalesce(p_reason, 'AI 更新月计划骨架'));
  end if;

  -- Archive first to satisfy the one-active-plan invariant inside the transaction.
  update public.monthly_plans set status = 'archived', version = version + 1
    where user_id = p_user_id and deleted_at is null and status = 'active';

  if v_plan_id is null then
    insert into public.monthly_plans(user_id, phase_id, title, start_date, end_date, status,
      subject_hour_quotas, weekly_milestones, strategy_note, renew_remind_before_days)
    values (p_user_id, v_effective_phase_id, p_blueprint->>'title', (p_blueprint->>'start_date')::date,
      (p_blueprint->>'end_date')::date, 'active', coalesce(p_blueprint->'subject_hour_quotas', '{}'::jsonb),
      coalesce(p_blueprint->'weekly_milestones', '[]'::jsonb), p_blueprint->>'strategy_note',
      coalesce(nullif(p_blueprint->>'renew_remind_before_days', '')::integer, 3))
    returning id into v_plan_id;
    v_created := array_append(v_created, v_plan_id);
    v_diff := jsonb_build_array(jsonb_build_object('op', 'add', 'monthly_plan_id', v_plan_id,
      'title', p_blueprint->>'title'));
  else
    update public.monthly_plans set
      phase_id = v_effective_phase_id,
      title = p_blueprint->>'title',
      start_date = (p_blueprint->>'start_date')::date,
      end_date = (p_blueprint->>'end_date')::date,
      status = 'active',
      subject_hour_quotas = coalesce(p_blueprint->'subject_hour_quotas', '{}'::jsonb),
      weekly_milestones = coalesce(p_blueprint->'weekly_milestones', '[]'::jsonb),
      strategy_note = p_blueprint->>'strategy_note',
      renew_remind_before_days = coalesce(nullif(p_blueprint->>'renew_remind_before_days', '')::integer, renew_remind_before_days),
      version = version + 1
    where id = v_plan_id and user_id = p_user_id
    returning * into v_plan;
    v_diff := jsonb_build_array(jsonb_build_object('op', 'update', 'monthly_plan_id', v_plan_id,
      'title', p_blueprint->>'title'));
  end if;

  insert into public.plan_revisions(user_id, scope, monthly_plan_id, actor, tool_name, change_summary,
    reason, affected_date_range, before_snapshot, created_ids, after_diff)
  values (p_user_id, 'monthly_plans', v_plan_id, p_actor, 'upsert_monthly_blueprint',
    case when v_plan_count = 0 then '创建月计划' else '更新月计划' end,
    coalesce(p_reason, 'AI 更新月计划骨架'), format('%s..%s', p_blueprint->>'start_date', p_blueprint->>'end_date'),
    v_before, v_created, v_diff);
  return jsonb_build_object('monthly_plan_id', v_plan_id, 'created_ids', to_jsonb(v_created), 'changes', v_diff);
end;
$$;

create or replace function public.rpc_batch_upsert_plan_range_tx(
  p_user_id uuid,
  p_actor text,
  p_monthly_plan_id uuid,
  p_mode text,
  p_reason text,
  p_days jsonb,
  p_dry_run boolean default false
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_day jsonb;
  v_task jsonb;
  v_task_id uuid;
  v_month_id uuid := p_monthly_plan_id;
  v_before jsonb := '[]'::jsonb;
  v_diff jsonb := '[]'::jsonb;
  v_created uuid[] := '{}';
  v_min_date date;
  v_max_date date;
  v_minutes integer;
  v_count integer := 0;
  v_start_time time;
  v_day_task_count integer;
  v_day_used_minutes integer;
  v_day_capacity integer;
  v_rest_period_count integer;
begin
  perform public.rpc_assert_user(p_user_id);
  perform public.rpc_lock_user(p_user_id);
  if p_mode not in ('replace_ai_only', 'append') then raise exception using errcode = '22023', message = 'invalid mode'; end if;
  if jsonb_typeof(p_days) <> 'array' or jsonb_array_length(p_days) < 1 or jsonb_array_length(p_days) > 10 then
    raise exception using errcode = '22023', message = 'days must contain between 1 and 10 dates';
  end if;
  select min((value->>'plan_date')::date), max((value->>'plan_date')::date)
    into v_min_date, v_max_date from jsonb_array_elements(p_days);
  if v_month_id is null then
    select id into v_month_id from public.monthly_plans where user_id = p_user_id and status = 'active'
      and deleted_at is null order by start_date desc limit 1;
  end if;
  if v_month_id is not null and not exists (select 1 from public.monthly_plans
      where id = v_month_id and user_id = p_user_id and deleted_at is null) then
    raise exception using errcode = 'P0001', message = 'ERR_MONTHLY_PLAN_NOT_FOUND';
  end if;
  if v_month_id is not null and exists (select 1 from public.monthly_plans where id = v_month_id
      and (v_min_date < start_date or v_max_date > end_date)) then
    raise exception using errcode = 'P0001', message = 'ERR_DATE_OUTSIDE_MONTHLY_PLAN';
  end if;

  if p_mode = 'replace_ai_only' then
    select coalesce(jsonb_agg(jsonb_build_object('row', to_jsonb(dp), 'after_version', dp.version + 1)), '[]'::jsonb)
      into v_before from public.daily_plans dp
      where dp.user_id = p_user_id and dp.deleted_at is null and dp.source = 'ai_agent'
        and not dp.is_locked and dp.status <> 'done'
        and dp.plan_date between v_min_date and v_max_date
        and (v_month_id is null or dp.monthly_plan_id = v_month_id);
  end if;

  if p_dry_run then
    for v_day in select value from jsonb_array_elements(p_days) order by (value->>'plan_date')::date loop
      v_minutes := 0;
      select count(*)::integer, coalesce(sum(planned_minutes), 0)::integer
        into v_day_task_count, v_day_used_minutes
        from public.daily_plans dp
        where dp.user_id = p_user_id and dp.plan_date = (v_day->>'plan_date')::date and dp.deleted_at is null
          and not (p_mode = 'replace_ai_only' and dp.source = 'ai_agent' and not dp.is_locked and dp.status <> 'done');
      select coalesce(daily_hard_cap_minutes, 720) into v_day_capacity
        from public.user_profiles where id = p_user_id;
      select count(*)::integer into v_rest_period_count
        from jsonb_array_elements(coalesce((select rest_days from public.user_profiles where id = p_user_id), '[]'::jsonb)) as rest_entry(value)
        where (rest_entry.value->>'weekday')::integer = extract(isodow from (v_day->>'plan_date')::date)::integer;
      if v_rest_period_count > 0 then v_day_capacity := floor(coalesce(v_day_capacity, 720) / 2.0)::integer;
      else v_day_capacity := least(coalesce(v_day_capacity, 720), 960); end if;
      for v_task in select value from jsonb_array_elements(coalesce(v_day->'tasks', '[]'::jsonb)) loop
        v_minutes := v_minutes + (v_task->>'planned_minutes')::integer;
        if not exists (select 1 from public.subjects where id = (v_task->>'subject_id')::uuid
          and user_id = p_user_id and is_archived = false) then
          raise exception using errcode = 'P0001', message = 'ERR_SUBJECT_NOT_FOUND', detail = v_task->>'subject_id';
        end if;
        if (select count(*) from jsonb_array_elements(coalesce(v_day->'tasks', '[]'::jsonb))) > 20 then
          raise exception using errcode = 'P0001', message = 'ERR_DAILY_CAPACITY_EXCEEDED', detail = (v_day->>'plan_date');
        end if;
        perform public.rpc_assert_task_slot(p_user_id, (v_day->>'plan_date')::date,
          nullif(v_task->>'scheduled_start_time', '')::time, (v_task->>'planned_minutes')::integer, null,
          p_mode = 'replace_ai_only');
      end loop;
      if v_day_task_count + jsonb_array_length(coalesce(v_day->'tasks', '[]'::jsonb)) > 20
          or v_day_used_minutes + v_minutes > v_day_capacity then
        raise exception using errcode = 'P0001', message = 'ERR_DAILY_CAPACITY_EXCEEDED',
          detail = jsonb_build_object('plan_date', v_day->>'plan_date',
            'existing_task_count', v_day_task_count, 'new_task_count', jsonb_array_length(coalesce(v_day->'tasks', '[]'::jsonb)),
            'existing_minutes', v_day_used_minutes, 'new_minutes', v_minutes, 'capacity_minutes', v_day_capacity)::text;
      end if;
      v_diff := v_diff || jsonb_build_array(jsonb_build_object('op', 'plan_date', 'plan_date', v_day->>'plan_date',
        'task_count', jsonb_array_length(coalesce(v_day->'tasks', '[]'::jsonb)), 'planned_minutes', v_minutes));
    end loop;
    return jsonb_build_object('dry_run', true, 'mode', p_mode, 'affected_range', jsonb_build_array(v_min_date, v_max_date),
      'changes', v_diff, 'replace_count', jsonb_array_length(v_before));
  end if;

  if p_mode = 'replace_ai_only' then
    update public.daily_plans dp set deleted_at = now(), version = version + 1
      where dp.user_id = p_user_id and dp.deleted_at is null and dp.source = 'ai_agent'
        and not dp.is_locked and dp.status <> 'done'
        and dp.plan_date between v_min_date and v_max_date
        and (v_month_id is null or dp.monthly_plan_id = v_month_id);
    v_diff := v_diff || jsonb_build_array(jsonb_build_object('op', 'replace_ai_only', 'deleted_count', jsonb_array_length(v_before)));
  end if;

  for v_day in select value from jsonb_array_elements(p_days) order by (value->>'plan_date')::date
  loop
    for v_task in select value from jsonb_array_elements(coalesce(v_day->'tasks', '[]'::jsonb))
    loop
      v_task_id := gen_random_uuid();
      v_minutes := (v_task->>'planned_minutes')::integer;
      v_start_time := nullif(v_task->>'scheduled_start_time', '')::time;
      perform public.rpc_assert_task_slot(p_user_id, (v_day->>'plan_date')::date, v_start_time, v_minutes, null);
      if not exists (select 1 from public.subjects where id = (v_task->>'subject_id')::uuid
        and user_id = p_user_id and is_archived = false) then
        raise exception using errcode = 'P0001', message = 'ERR_SUBJECT_NOT_FOUND', detail = v_task->>'subject_id';
      end if;
      insert into public.daily_plans(user_id, monthly_plan_id, plan_date, subject_id, title, activity_type,
        granularity, planned_minutes, scheduled_start_time, priority, tags, source, ai_reason)
      values (p_user_id, v_month_id, (v_day->>'plan_date')::date, (v_task->>'subject_id')::uuid,
        v_task->>'title', v_task->>'activity_type', coalesce(v_task->>'granularity', 'detailed'),
        v_minutes, v_start_time, coalesce(nullif(v_task->>'priority', '')::smallint, 2),
        coalesce(array(select jsonb_array_elements_text(v_task->'tags')), '{}'), 'ai_agent', v_task->>'ai_reason')
      returning id into v_task_id;
      v_created := array_append(v_created, v_task_id);
      v_count := v_count + 1;
    end loop;
  end loop;

  v_diff := v_diff || jsonb_build_array(jsonb_build_object('op', 'add', 'created_count', v_count));
  insert into public.plan_revisions(user_id, scope, monthly_plan_id, actor, tool_name, change_summary,
    reason, affected_date_range, before_snapshot, created_ids, after_diff)
  values (p_user_id, 'daily_plans', v_month_id, p_actor, 'batch_upsert_plan_range',
    format('新增 %s 项任务', v_count), p_reason, format('%s..%s', v_min_date, v_max_date),
    v_before, v_created, v_diff);
  return jsonb_build_object('created_count', v_count, 'created_ids', to_jsonb(v_created), 'changes', v_diff);
end;
$$;

create or replace function public.rpc_apply_plan_patch_tx(
  p_user_id uuid,
  p_actor text,
  p_reason text,
  p_operations_json jsonb,
  p_dry_run boolean default false
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_operation jsonb;
  v_fields jsonb;
  v_task public.daily_plans%rowtype;
  v_task_id uuid;
  v_subject_id uuid;
  v_before jsonb := '[]'::jsonb;
  v_diff jsonb := '[]'::jsonb;
  v_created uuid[] := '{}';
  v_op text;
  v_new_date date;
  v_new_time time;
  v_new_minutes integer;
  v_new_title text;
  v_new_activity text;
  v_new_priority smallint;
  v_new_tags text[];
  v_new_reason text;
begin
  perform public.rpc_assert_user(p_user_id);
  perform public.rpc_lock_user(p_user_id);
  if jsonb_typeof(p_operations_json) <> 'array' or jsonb_array_length(p_operations_json) < 1
      or jsonb_array_length(p_operations_json) > 50 then
    raise exception using errcode = '22023', message = 'operations must contain between 1 and 50 items';
  end if;
  if exists (
    select task_id from (
      select value->>'task_id' as task_id
      from jsonb_array_elements(p_operations_json)
      where value->>'op' in ('update', 'delete', 'move')
    ) target_ids group by task_id having count(*) > 1
  ) then
    raise exception using errcode = '22023', message = 'a task may appear only once in a patch batch';
  end if;

  if p_dry_run then
    for v_operation in select value from jsonb_array_elements(p_operations_json) loop
      v_op := v_operation->>'op';
      if v_op in ('update', 'delete', 'move') then
        select * into v_task from public.daily_plans where id = (v_operation->>'task_id')::uuid
          and user_id = p_user_id and deleted_at is null;
        if not found then raise exception using errcode = 'P0001', message = 'ERR_TASK_NOT_FOUND'; end if;
        if v_task.version <> (v_operation->>'expected_version')::integer then
          raise exception using errcode = 'P0001', message = 'ERR_VERSION_CONFLICT', detail = to_jsonb(v_task)::text;
        end if;
        if v_task.is_locked or v_task.status = 'done' then
          raise exception using errcode = 'P0001', message = 'ERR_TASK_LOCKED_OR_DONE', detail = v_task.id::text;
        end if;
        if v_op in ('update', 'move') then
          v_fields := coalesce(v_operation->'fields', '{}'::jsonb);
          v_new_date := case when v_op = 'move' then (v_operation->>'plan_date')::date
            else coalesce(nullif(v_fields->>'plan_date', '')::date, v_task.plan_date) end;
          v_new_time := case
            when v_op = 'move' and v_operation ? 'scheduled_start_time' then nullif(v_operation->>'scheduled_start_time', '')::time
            when v_op = 'update' and v_fields ? 'scheduled_start_time' then nullif(v_fields->>'scheduled_start_time', '')::time
            else v_task.scheduled_start_time end;
          v_new_minutes := coalesce(nullif(v_fields->>'planned_minutes', '')::integer, v_task.planned_minutes);
          perform public.rpc_assert_task_slot(p_user_id, v_new_date, v_new_time, v_new_minutes, v_task.id);
        end if;
      elsif v_op = 'add' then
        if not exists (select 1 from public.subjects where id = (v_operation->>'subject_id')::uuid
          and user_id = p_user_id and is_archived = false) then
          raise exception using errcode = 'P0001', message = 'ERR_SUBJECT_NOT_FOUND', detail = v_operation->>'subject_id';
        end if;
        perform public.rpc_assert_task_slot(p_user_id, (v_operation->>'plan_date')::date,
          nullif(v_operation->>'scheduled_start_time', '')::time, (v_operation->>'planned_minutes')::integer, null);
      else
        raise exception using errcode = '22023', message = 'invalid task operation';
      end if;
      v_diff := v_diff || jsonb_build_array(jsonb_build_object('op', v_op,
        'task_id', v_operation->>'task_id', 'plan_date', v_operation->>'plan_date'));
    end loop;
    return jsonb_build_object('dry_run', true, 'changes', v_diff, 'operation_count', jsonb_array_length(p_operations_json));
  end if;

  for v_operation in select value from jsonb_array_elements(p_operations_json) loop
    v_op := v_operation->>'op';
    if v_op = 'add' then
      v_new_date := (v_operation->>'plan_date')::date;
      v_subject_id := (v_operation->>'subject_id')::uuid;
      v_new_minutes := (v_operation->>'planned_minutes')::integer;
      v_new_time := nullif(v_operation->>'scheduled_start_time', '')::time;
      if not exists (select 1 from public.subjects where id = v_subject_id and user_id = p_user_id and is_archived = false) then
        raise exception using errcode = 'P0001', message = 'ERR_SUBJECT_NOT_FOUND', detail = v_subject_id::text;
      end if;
      perform public.rpc_assert_task_slot(p_user_id, v_new_date, v_new_time, v_new_minutes, null);
      insert into public.daily_plans(user_id, monthly_plan_id, plan_date, subject_id, title, activity_type,
        planned_minutes, scheduled_start_time, priority, tags, source, ai_reason)
      values (p_user_id, (select id from public.monthly_plans where user_id = p_user_id and status = 'active'
        and deleted_at is null order by start_date desc limit 1), v_new_date, v_subject_id,
        v_operation->>'title', v_operation->>'activity_type', v_new_minutes, v_new_time,
        coalesce(nullif(v_operation->>'priority', '')::smallint, 2),
        coalesce(array(select jsonb_array_elements_text(v_operation->'tags')), '{}'),
        coalesce(v_operation->>'source', 'ai_agent'), v_operation->>'ai_reason') returning id into v_task_id;
      v_created := array_append(v_created, v_task_id);
      v_diff := v_diff || jsonb_build_array(jsonb_build_object('op', 'add', 'task_id', v_task_id,
        'plan_date', v_new_date, 'title', v_operation->>'title'));
      continue;
    end if;

    v_task_id := (v_operation->>'task_id')::uuid;
    select * into v_task from public.daily_plans where id = v_task_id and user_id = p_user_id
      and deleted_at is null for update;
    if not found then raise exception using errcode = 'P0001', message = 'ERR_TASK_NOT_FOUND', detail = v_task_id::text; end if;
    if v_task.version <> (v_operation->>'expected_version')::integer then
      raise exception using errcode = 'P0001', message = 'ERR_VERSION_CONFLICT', detail = to_jsonb(v_task)::text;
    end if;
    if v_task.is_locked or v_task.status = 'done' then
      raise exception using errcode = 'P0001', message = 'ERR_TASK_LOCKED_OR_DONE', detail = v_task_id::text;
    end if;
    v_before := v_before || jsonb_build_array(jsonb_build_object('row', to_jsonb(v_task), 'after_version', v_task.version + 1));

    if v_op = 'delete' then
      update public.daily_plans set deleted_at = now(), version = version + 1 where id = v_task_id and user_id = p_user_id;
      v_diff := v_diff || jsonb_build_array(jsonb_build_object('op', 'delete', 'task_id', v_task_id, 'title', v_task.title));
      continue;
    end if;

    v_fields := coalesce(v_operation->'fields', '{}'::jsonb);
    v_new_date := case when v_op = 'move' then (v_operation->>'plan_date')::date
      else coalesce(nullif(v_fields->>'plan_date', '')::date, v_task.plan_date) end;
    v_new_time := case
      when v_op = 'move' and v_operation ? 'scheduled_start_time' then nullif(v_operation->>'scheduled_start_time', '')::time
      when v_op = 'update' and v_fields ? 'scheduled_start_time' then nullif(v_fields->>'scheduled_start_time', '')::time
      else v_task.scheduled_start_time end;
    v_new_minutes := coalesce(nullif(v_fields->>'planned_minutes', '')::integer, v_task.planned_minutes);
    perform public.rpc_assert_task_slot(p_user_id, v_new_date, v_new_time, v_new_minutes, v_task_id);

    update public.daily_plans set
      plan_date = v_new_date,
      scheduled_start_time = v_new_time,
      title = coalesce(v_fields->>'title', title),
      planned_minutes = v_new_minutes,
      activity_type = coalesce(v_fields->>'activity_type', activity_type),
      priority = coalesce(nullif(v_fields->>'priority', '')::smallint, priority),
      tags = case when v_fields ? 'tags' then array(select jsonb_array_elements_text(v_fields->'tags')) else tags end,
      ai_reason = coalesce(v_fields->>'ai_reason', ai_reason),
      version = version + 1
    where id = v_task_id and user_id = p_user_id;
    v_diff := v_diff || jsonb_build_array(jsonb_build_object('op', v_op, 'task_id', v_task_id,
      'from_date', v_task.plan_date, 'to_date', v_new_date, 'title', coalesce(v_fields->>'title', v_task.title)));
  end loop;

  insert into public.plan_revisions(user_id, scope, actor, tool_name, change_summary, reason,
    affected_date_range, before_snapshot, created_ids, after_diff)
  values (p_user_id, 'daily_plans', p_actor, 'patch_plan_tasks',
    format('执行 %s 项任务修改', jsonb_array_length(p_operations_json)), p_reason,
    '', v_before, v_created, v_diff);
  return jsonb_build_object('created_ids', to_jsonb(v_created), 'changes', v_diff);
end;
$$;

create or replace function public.rpc_shift_plan_schedule_tx(
  p_user_id uuid,
  p_actor text,
  p_from_date date,
  p_to_date date,
  p_shift_days integer,
  p_subject_id uuid default null,
  p_preserve_rest_days boolean default true,
  p_reason text default '',
  p_dry_run boolean default false
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_task public.daily_plans%rowtype;
  v_target date;
  v_candidate date;
  v_start_time time;
  v_before jsonb := '[]'::jsonb;
  v_diff jsonb := '[]'::jsonb;
  v_new_date date;
  v_probe_ok boolean;
  v_month public.monthly_plans%rowtype;
  v_month_before jsonb;
begin
  perform public.rpc_assert_user(p_user_id);
  perform public.rpc_lock_user(p_user_id);
  if p_to_date < p_from_date or p_shift_days < 1 or p_shift_days > 14 then
    raise exception using errcode = '22023', message = 'invalid shift range or shift_days';
  end if;

  for v_task in select * from public.daily_plans dp
    where dp.user_id = p_user_id and dp.deleted_at is null
      and dp.plan_date between p_from_date and p_to_date
      and dp.status in ('todo', 'in_progress') and not dp.is_locked
      and (p_subject_id is null or dp.subject_id = p_subject_id)
    order by dp.plan_date, dp.scheduled_start_time nulls last, dp.priority, dp.id
    for update
  loop
    v_candidate := v_task.plan_date + p_shift_days;
    v_start_time := v_task.scheduled_start_time;
    v_probe_ok := false;
    while v_candidate <= v_task.plan_date + p_shift_days + 90 loop
      begin
        perform public.rpc_assert_task_slot(p_user_id, v_candidate, v_start_time, v_task.planned_minutes, v_task.id);
        v_probe_ok := true;
        exit;
      exception when sqlstate 'P0001' then
        if sqlerrm in ('ERR_REST_DAY_VIOLATION', 'ERR_DAILY_CAPACITY_EXCEEDED') and p_preserve_rest_days then
          v_probe_ok := false;
          v_candidate := v_candidate + 1;
        else
          raise;
        end if;
      end;
    end loop;
    if not v_probe_ok then
      raise exception using errcode = 'P0001', message = 'ERR_DAILY_CAPACITY_EXCEEDED',
        detail = jsonb_build_object('task_id', v_task.id, 'message', 'No available date found within 90 days')::text;
    end if;

    v_target := v_candidate;
    v_before := v_before || jsonb_build_array(jsonb_build_object('row', to_jsonb(v_task), 'after_version', v_task.version + 1));
    if not p_dry_run then
      update public.daily_plans set plan_date = v_target, version = version + 1
        where id = v_task.id and user_id = p_user_id;
    end if;
    v_diff := v_diff || jsonb_build_array(jsonb_build_object('op', 'move', 'task_id', v_task.id,
      'from_date', v_task.plan_date, 'to_date', v_target, 'title', v_task.title));

    if v_task.monthly_plan_id is not null then
      select * into v_month from public.monthly_plans where id = v_task.monthly_plan_id
        and user_id = p_user_id and deleted_at is null for update;
      if not p_dry_run and found and v_target > v_month.end_date then
        v_month_before := jsonb_build_object('row', to_jsonb(v_month), 'after_version', v_month.version + 1,
          'kind', 'monthly_plans');
        if exists (select 1 from jsonb_array_elements(v_before) as entry(value)
          where entry.value->>'kind' = 'monthly_plans' and entry.value->'row'->>'id' = v_month.id::text) then
          select coalesce(jsonb_agg(case when entry.value->>'kind' = 'monthly_plans'
              and entry.value->'row'->>'id' = v_month.id::text
            then jsonb_set(entry.value, '{after_version}', to_jsonb(v_month.version + 1), true)
            else entry.value end), '[]'::jsonb)
            into v_before from jsonb_array_elements(v_before) as entry(value);
        else
          v_before := v_before || jsonb_build_array(v_month_before);
        end if;
        update public.monthly_plans set end_date = v_target, version = version + 1
          where id = v_month.id and user_id = p_user_id;
      end if;
    end if;
  end loop;

  if p_dry_run then return jsonb_build_object('dry_run', true, 'changes', v_diff); end if;
  if jsonb_array_length(v_diff) = 0 then
    return jsonb_build_object('changed_count', 0, 'changes', '[]'::jsonb);
  end if;
  insert into public.plan_revisions(user_id, scope, actor, tool_name, change_summary, reason,
    affected_date_range, before_snapshot, after_diff)
  values (p_user_id, 'daily_plans', p_actor, 'shift_plan_schedule',
    format('顺延 %s 项任务', jsonb_array_length(v_diff)), p_reason,
    format('%s..%s', p_from_date, p_to_date), v_before, v_diff);
  return jsonb_build_object('changed_count', jsonb_array_length(v_diff), 'changes', v_diff);
end;
$$;

create or replace function public.rpc_revert_plan_revision_tx(
  p_user_id uuid,
  p_revision_id uuid,
  p_cascade_newer boolean default false
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_target public.plan_revisions%rowtype;
  v_revision public.plan_revisions%rowtype;
  v_wrapper jsonb;
  v_row jsonb;
  v_id uuid;
  v_current_version integer;
  v_current_status text;
  v_current_locked boolean;
  v_restored_count integer := 0;
  v_deleted_count integer := 0;
  v_skipped_done_count integer := 0;
  v_skipped_conflict_count integer := 0;
  v_reverted_ids uuid[] := '{}';
  v_newer jsonb;
  v_at_target boolean := false;
  v_reverted_daily_ids uuid[] := '{}';
  v_reverted_phase_ids uuid[] := '{}';
  v_reverted_monthly_ids uuid[] := '{}';
begin
  perform public.rpc_assert_user(p_user_id);
  perform public.rpc_lock_user(p_user_id);
  select * into v_target from public.plan_revisions
    where id = p_revision_id and user_id = p_user_id and not is_reverted for update;
  if not found then raise exception using errcode = 'P0001', message = 'ERR_REVISION_NOT_FOUND'; end if;

  select coalesce(jsonb_agg(jsonb_build_object('id', r.id, 'tool_name', r.tool_name,
    'change_summary', r.change_summary, 'created_at', r.created_at) order by r.created_at desc, r.id desc), '[]'::jsonb)
    into v_newer from public.plan_revisions r
    where r.user_id = p_user_id and r.scope = v_target.scope and not r.is_reverted
      and (r.created_at, r.id) > (v_target.created_at, v_target.id);
  if jsonb_array_length(v_newer) > 0 and not p_cascade_newer then
    raise exception using errcode = 'P0001', message = 'ERR_REVERT_ORDER_CONFLICT', detail = v_newer::text;
  end if;

  for v_revision in select * from public.plan_revisions r
    where r.user_id = p_user_id and r.scope = v_target.scope and not r.is_reverted
      and (r.created_at, r.id) >= (v_target.created_at, v_target.id)
    order by r.created_at desc, r.id desc for update
  loop
    if v_revision.scope = 'daily_plans' then
      foreach v_id in array v_revision.created_ids loop
        select version, status, is_locked into v_current_version, v_current_status, v_current_locked
          from public.daily_plans where id = v_id and user_id = p_user_id and deleted_at is null;
        if found then
          if (v_current_version = 1 or v_id = any(v_reverted_daily_ids))
              and v_current_status <> 'done' and not v_current_locked then
            update public.daily_plans set deleted_at = now(), version = version + 1
              where id = v_id and user_id = p_user_id and deleted_at is null;
            v_deleted_count := v_deleted_count + 1;
          elsif v_current_status = 'done' or v_current_locked then
            v_skipped_done_count := v_skipped_done_count + 1;
          else
            v_skipped_conflict_count := v_skipped_conflict_count + 1;
          end if;
        end if;
      end loop;
    elsif v_revision.scope = 'exam_phases' then
      foreach v_id in array v_revision.created_ids loop
        select version into v_current_version from public.exam_phases
          where id = v_id and user_id = p_user_id and deleted_at is null;
        if found then
          if v_current_version = 1 or v_id = any(v_reverted_phase_ids) then
            update public.exam_phases set deleted_at = now(), version = version + 1
              where id = v_id and user_id = p_user_id and deleted_at is null;
            v_deleted_count := v_deleted_count + 1;
          else
            v_skipped_conflict_count := v_skipped_conflict_count + 1;
          end if;
        end if;
      end loop;
    elsif v_revision.scope = 'monthly_plans' then
      foreach v_id in array v_revision.created_ids loop
        select version into v_current_version from public.monthly_plans
          where id = v_id and user_id = p_user_id and deleted_at is null;
        if found then
          if v_current_version = 1 or v_id = any(v_reverted_monthly_ids) then
            update public.monthly_plans set deleted_at = now(), status = 'archived', version = version + 1
              where id = v_id and user_id = p_user_id and deleted_at is null;
            v_deleted_count := v_deleted_count + 1;
          else
            v_skipped_conflict_count := v_skipped_conflict_count + 1;
          end if;
        end if;
      end loop;
    end if;

    for v_wrapper in select entry.value from jsonb_array_elements(coalesce(v_revision.before_snapshot, '[]'::jsonb)) as entry(value)
      order by case when (v_revision.scope = 'exam_phases'
          or v_revision.scope = 'monthly_plans'
          or (v_revision.scope = 'daily_plans' and entry.value->>'kind' = 'monthly_plans'))
          and entry.value->'row'->>'status' = 'active' then 1 else 0 end
    loop
      v_row := coalesce(v_wrapper->'row', v_wrapper);
      v_id := nullif(v_row->>'id', '')::uuid;
      if v_revision.scope = 'daily_plans' and coalesce(v_wrapper->>'kind', 'daily_plans') = 'daily_plans' then
        select version, status, is_locked into v_current_version, v_current_status, v_current_locked
          from public.daily_plans where id = v_id and user_id = p_user_id;
        if found then
          if v_current_version = (v_wrapper->>'after_version')::integer then
            if exists (select 1 from public.daily_plans where id = v_id and user_id = p_user_id
              and (status = 'done' or is_locked)) then
              v_skipped_done_count := v_skipped_done_count + 1;
            else
              update public.daily_plans set
                monthly_plan_id = nullif(v_row->>'monthly_plan_id', '')::uuid,
                plan_date = (v_row->>'plan_date')::date,
                subject_id = (v_row->>'subject_id')::uuid,
                title = v_row->>'title', activity_type = v_row->>'activity_type',
                granularity = v_row->>'granularity', planned_minutes = (v_row->>'planned_minutes')::integer,
                scheduled_start_time = nullif(v_row->>'scheduled_start_time', '')::time,
                priority = (v_row->>'priority')::smallint, status = v_row->>'status',
                is_locked = (v_row->>'is_locked')::boolean, tags = array(select jsonb_array_elements_text(v_row->'tags')),
                source = v_row->>'source', ai_reason = v_row->>'ai_reason',
                deleted_at = nullif(v_row->>'deleted_at', '')::timestamptz,
                version = version + 1
              where id = v_id and user_id = p_user_id;
              v_reverted_daily_ids := array_append(v_reverted_daily_ids, v_id);
              v_restored_count := v_restored_count + 1;
            end if;
          else
            v_skipped_conflict_count := v_skipped_conflict_count + 1;
          end if;
        end if;
      elsif v_revision.scope = 'exam_phases' then
        select version into v_current_version from public.exam_phases where id = v_id and user_id = p_user_id;
        if found then
          if v_current_version = (v_wrapper->>'after_version')::integer then
            if v_row->>'status' = 'active' and exists (select 1 from public.exam_phases
                where user_id = p_user_id and deleted_at is null and status = 'active' and id <> v_id) then
              v_skipped_conflict_count := v_skipped_conflict_count + 1;
            else
            update public.exam_phases set name = v_row->>'name', order_index = (v_row->>'order_index')::smallint,
              start_date = (v_row->>'start_date')::date, end_date = (v_row->>'end_date')::date,
              target_hours = (v_row->>'target_hours')::integer, core_goal = v_row->>'core_goal',
              subject_milestones = coalesce(v_row->'subject_milestones', '[]'::jsonb),
              remind_before_days = array(select jsonb_array_elements_text(v_row->'remind_before_days')::integer),
              reminder_enabled = (v_row->>'reminder_enabled')::boolean, status = v_row->>'status',
              deleted_at = nullif(v_row->>'deleted_at', '')::timestamptz, version = version + 1
            where id = v_id and user_id = p_user_id;
            v_reverted_phase_ids := array_append(v_reverted_phase_ids, v_id);
            v_restored_count := v_restored_count + 1;
            end if;
          else v_skipped_conflict_count := v_skipped_conflict_count + 1;
          end if;
        end if;
      elsif v_revision.scope = 'monthly_plans' or (v_revision.scope = 'daily_plans' and v_wrapper->>'kind' = 'monthly_plans') then
        select version into v_current_version from public.monthly_plans where id = v_id and user_id = p_user_id;
        if found then
          if v_current_version = (v_wrapper->>'after_version')::integer then
            if v_row->>'status' = 'active' and exists (select 1 from public.monthly_plans
                where user_id = p_user_id and deleted_at is null and status = 'active' and id <> v_id) then
              v_skipped_conflict_count := v_skipped_conflict_count + 1;
            else
            update public.monthly_plans set phase_id = nullif(v_row->>'phase_id', '')::uuid,
              title = v_row->>'title', start_date = (v_row->>'start_date')::date,
              end_date = (v_row->>'end_date')::date, status = v_row->>'status',
              subject_hour_quotas = coalesce(v_row->'subject_hour_quotas', '{}'::jsonb),
              weekly_milestones = coalesce(v_row->'weekly_milestones', '[]'::jsonb),
              strategy_note = v_row->>'strategy_note',
              renew_remind_before_days = (v_row->>'renew_remind_before_days')::integer,
              deleted_at = nullif(v_row->>'deleted_at', '')::timestamptz, version = version + 1
            where id = v_id and user_id = p_user_id;
            v_reverted_monthly_ids := array_append(v_reverted_monthly_ids, v_id);
            v_restored_count := v_restored_count + 1;
            end if;
          else v_skipped_conflict_count := v_skipped_conflict_count + 1;
          end if;
        end if;
      end if;
    end loop;

    update public.plan_revisions set is_reverted = true, is_read = true where id = v_revision.id and user_id = p_user_id;
    v_reverted_ids := array_append(v_reverted_ids, v_revision.id);
    if v_revision.id = p_revision_id then v_at_target := true; exit; end if;
  end loop;

  if not v_at_target then raise exception using errcode = 'P0001', message = 'ERR_REVERT_ORDER_CONFLICT'; end if;
  return jsonb_build_object('reverted_revision_ids', to_jsonb(v_reverted_ids),
    'restored_count', v_restored_count, 'deleted_count', v_deleted_count,
    'skipped_done_count', v_skipped_done_count, 'skipped_conflict_count', v_skipped_conflict_count);
end;
$$;

revoke all on function public.rpc_lock_user(uuid) from public;
revoke all on function public.rpc_assert_task_slot(uuid, date, time, integer, uuid, boolean) from public;
revoke all on function public.rpc_mobile_add_plan_task_tx(uuid, jsonb) from public;
revoke all on function public.rpc_upsert_profile_and_subjects_tx(uuid, jsonb, jsonb) from public;
revoke all on function public.rpc_log_study_session_tx(uuid, uuid, uuid, text, integer, smallint, timestamptz, text) from public;
revoke all on function public.rpc_upsert_roadmap_tx(uuid, text, text, jsonb, boolean) from public;
revoke all on function public.rpc_patch_exam_phase_tx(uuid, text, uuid, integer, text, boolean, jsonb, boolean) from public;
revoke all on function public.rpc_upsert_monthly_blueprint_tx(uuid, text, uuid, uuid, jsonb, text, boolean) from public;
revoke all on function public.rpc_batch_upsert_plan_range_tx(uuid, text, uuid, text, text, jsonb, boolean) from public;
revoke all on function public.rpc_apply_plan_patch_tx(uuid, text, text, jsonb, boolean) from public;
revoke all on function public.rpc_shift_plan_schedule_tx(uuid, text, date, date, integer, uuid, boolean, text, boolean) from public;
revoke all on function public.rpc_revert_plan_revision_tx(uuid, uuid, boolean) from public;

grant execute on function public.rpc_upsert_profile_and_subjects_tx(uuid, jsonb, jsonb) to authenticated, service_role;
grant execute on function public.rpc_mobile_add_plan_task_tx(uuid, jsonb) to authenticated, service_role;
grant execute on function public.rpc_log_study_session_tx(uuid, uuid, uuid, text, integer, smallint, timestamptz, text) to authenticated, service_role;
grant execute on function public.rpc_upsert_roadmap_tx(uuid, text, text, jsonb, boolean) to authenticated, service_role;
grant execute on function public.rpc_patch_exam_phase_tx(uuid, text, uuid, integer, text, boolean, jsonb, boolean) to authenticated, service_role;
grant execute on function public.rpc_upsert_monthly_blueprint_tx(uuid, text, uuid, uuid, jsonb, text, boolean) to authenticated, service_role;
grant execute on function public.rpc_batch_upsert_plan_range_tx(uuid, text, uuid, text, text, jsonb, boolean) to authenticated, service_role;
grant execute on function public.rpc_apply_plan_patch_tx(uuid, text, text, jsonb, boolean) to authenticated, service_role;
grant execute on function public.rpc_shift_plan_schedule_tx(uuid, text, date, date, integer, uuid, boolean, text, boolean) to authenticated, service_role;
grant execute on function public.rpc_revert_plan_revision_tx(uuid, uuid, boolean) to authenticated, service_role;
