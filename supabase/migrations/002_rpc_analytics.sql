create or replace function public.rpc_assert_user(p_user_id uuid)
returns void
language plpgsql
stable
security definer
set search_path = pg_catalog, public, auth
as $$
declare
  v_role text;
begin
  v_role := coalesce(
    nullif(current_setting('request.jwt.claim.role', true), ''),
    nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'role',
    ''
  );
  if p_user_id is null then
    raise exception using errcode = '22023', message = 'ERR_INVALID_USER', detail = 'user_id is required';
  end if;
  if auth.uid() is distinct from p_user_id and v_role <> 'service_role' then
    raise exception using errcode = '42501', message = 'ERR_FORBIDDEN', detail = 'The caller cannot access this user';
  end if;
end;
$$;

create or replace function public.rpc_get_study_summary(
  p_user_id uuid,
  p_days integer default 7,
  p_subject_id uuid default null
)
returns jsonb
language plpgsql
stable
security definer
set search_path = pg_catalog, public, auth
as $$
declare
  v_tz text;
  v_today date;
  v_start date;
  v_result jsonb;
begin
  perform public.rpc_assert_user(p_user_id);
  if p_days is null or p_days < 1 or p_days > 30 then
    raise exception using errcode = '22023', message = 'days must be between 1 and 30';
  end if;

  select timezone into v_tz from public.user_profiles where id = p_user_id;
  v_tz := coalesce(v_tz, 'Asia/Shanghai');
  v_today := (now() at time zone v_tz)::date;
  v_start := v_today - (p_days - 1);

  with recent as (
    select ss.*, (ss.started_at at time zone v_tz)::date as local_date,
           extract(hour from ss.started_at at time zone v_tz)::integer as local_hour
    from public.study_sessions ss
    where ss.user_id = p_user_id
      and ss.started_at >= (v_start::timestamp at time zone v_tz)
      and ss.started_at < ((v_today + 1)::timestamp at time zone v_tz)
      and (p_subject_id is null or ss.subject_id = p_subject_id)
  ), subject_stats as (
    select s.id, s.name, s.color, s.target_ratio,
           coalesce(sum(r.duration_seconds), 0)::bigint as duration_seconds,
           count(r.id)::integer as session_count,
           count(distinct r.local_date)::integer as study_days
    from public.subjects s
    left join recent r on r.subject_id = s.id
    where s.user_id = p_user_id and s.is_archived = false
      and (p_subject_id is null or s.id = p_subject_id)
    group by s.id, s.name, s.color, s.target_ratio
  ), activity_stats as (
    select r.activity_type, coalesce(sum(r.duration_seconds), 0)::bigint as duration_seconds
    from recent r group by r.activity_type
  ), activity_stats_with_total as (
    select a.activity_type, a.duration_seconds, sum(a.duration_seconds) over () as total_seconds
    from activity_stats a
  ), hour_stats as (
    select local_hour, avg(focus_score)::numeric(4,2) as average_focus_score,
           count(*)::integer as session_count
    from recent group by local_hour
  ), overdue as (
    select dp.id, dp.plan_date, dp.subject_id, s.name as subject_name, dp.title,
           dp.planned_minutes, dp.status, dp.version
    from public.daily_plans dp
    join public.subjects s on s.id = dp.subject_id and s.user_id = dp.user_id
    where dp.user_id = p_user_id and dp.deleted_at is null
      and dp.status in ('todo', 'in_progress') and dp.plan_date < v_today
      and (p_subject_id is null or dp.subject_id = p_subject_id)
    order by dp.plan_date, dp.priority, dp.id
  ), phase_hours as (
    select ep.id, ep.name, coalesce(sum(ss.duration_seconds), 0)::bigint as duration_seconds
    from public.exam_phases ep
    left join public.study_sessions ss on ss.user_id = ep.user_id
      and (ss.started_at at time zone v_tz)::date between ep.start_date and ep.end_date
    where ep.user_id = p_user_id and ep.deleted_at is null
    group by ep.id, ep.name, ep.order_index
    order by ep.order_index
  ), streak_breaks as (
    select s.id as subject_id, s.name as subject_name
    from public.subjects s
    where s.user_id = p_user_id and s.is_archived = false and s.require_daily_streak
      and (p_subject_id is null or s.id = p_subject_id)
      and not exists (
        select 1 from public.study_sessions ss
        where ss.user_id = p_user_id and ss.subject_id = s.id
          and (ss.started_at at time zone v_tz)::date = v_today - 1
      )
  )
  select jsonb_build_object(
    'user_id', p_user_id,
    'timezone', v_tz,
    'period', jsonb_build_object('start_date', v_start, 'end_date', v_today, 'days', p_days),
    'subjects', coalesce((
      select jsonb_agg(jsonb_build_object(
        'subject_id', id, 'subject_name', name, 'color', color,
        'target_ratio', target_ratio,
        'duration_seconds', duration_seconds,
        'duration_minutes', round(duration_seconds / 60.0, 1),
        'session_count', session_count, 'study_days', study_days
      ) order by duration_seconds desc, name)
      from subject_stats
    ), '[]'::jsonb),
    'activity_types', coalesce((
      select jsonb_object_agg(activity_type, jsonb_build_object(
        'duration_seconds', duration_seconds,
        'ratio', case when total_seconds = 0 then 0
          else round(duration_seconds * 100.0 / total_seconds, 1) end
      )) from activity_stats_with_total
    ), '{}'::jsonb),
    'focus_by_hour', coalesce((
      select jsonb_agg(jsonb_build_object('hour', local_hour, 'average_focus_score', average_focus_score,
        'session_count', session_count) order by local_hour) from hour_stats
    ), '[]'::jsonb),
    'phase_hours', coalesce((
      select jsonb_agg(jsonb_build_object('phase_id', id, 'phase_name', name,
        'duration_seconds', duration_seconds, 'duration_hours', round(duration_seconds / 3600.0, 2)))
      from phase_hours
    ), '[]'::jsonb),
    'overdue', jsonb_build_object(
      'count', (select count(*) from overdue),
      'tasks', coalesce((select jsonb_agg(to_jsonb(overdue)) from overdue), '[]'::jsonb)
    ),
    'streak_breaks', coalesce((select jsonb_agg(jsonb_build_object('subject_id', subject_id,
      'subject_name', subject_name) order by subject_name) from streak_breaks), '[]'::jsonb)
  ) into v_result;

  return v_result;
end;
$$;

revoke all on function public.rpc_assert_user(uuid) from public;
revoke all on function public.rpc_get_study_summary(uuid, integer, uuid) from public;
grant execute on function public.rpc_assert_user(uuid) to authenticated, service_role;
grant execute on function public.rpc_get_study_summary(uuid, integer, uuid) to authenticated, service_role;
