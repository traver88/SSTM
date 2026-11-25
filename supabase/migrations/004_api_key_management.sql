-- Keep one active remote MCP key per user. Only a one-time key creation RPC
-- returns the raw token. The table stores the SHA-256 digest only.
with ranked_active_keys as (
  select id,
         row_number() over (partition by user_id order by created_at desc, id desc) as key_rank
  from public.user_api_keys
  where revoked_at is null
)
update public.user_api_keys as keys
set revoked_at = now()
from ranked_active_keys
where keys.id = ranked_active_keys.id and ranked_active_keys.key_rank > 1;

create unique index if not exists user_api_keys_one_active_per_user_uidx
  on public.user_api_keys(user_id) where revoked_at is null;

create or replace function public.rpc_create_user_api_key()
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public, auth, extensions
as $$
declare
  v_user_id uuid := auth.uid();
  v_raw_key text;
  v_key_hash text;
  v_key_prefix text;
  v_key_id uuid;
begin
  if v_user_id is null then
    raise exception using errcode = '42501', message = 'ERR_FORBIDDEN';
  end if;
  perform public.rpc_assert_user(v_user_id);
  perform public.rpc_lock_user(v_user_id);

  v_raw_key := 'ky_live_' || encode(extensions.gen_random_bytes(32), 'hex');
  v_key_hash := encode(extensions.digest(convert_to(v_raw_key, 'UTF8'), 'sha256'), 'hex');
  v_key_prefix := left(v_raw_key, 16);

  update public.user_api_keys
  set revoked_at = now()
  where user_id = v_user_id and revoked_at is null;

  insert into public.user_api_keys(user_id, key_prefix, key_hash)
  values (v_user_id, v_key_prefix, v_key_hash)
  returning id into v_key_id;

  return jsonb_build_object('id', v_key_id, 'api_key', v_raw_key, 'key_prefix', v_key_prefix);
end;
$$;

create or replace function public.rpc_revoke_user_api_key(p_key_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
  v_user_id uuid := auth.uid();
  v_revoked boolean := false;
begin
  if v_user_id is null then
    raise exception using errcode = '42501', message = 'ERR_FORBIDDEN';
  end if;
  perform public.rpc_assert_user(v_user_id);
  perform public.rpc_lock_user(v_user_id);

  update public.user_api_keys
  set revoked_at = coalesce(revoked_at, now())
  where id = p_key_id and user_id = v_user_id
  returning true into v_revoked;

  return jsonb_build_object('revoked', coalesce(v_revoked, false));
end;
$$;

revoke all on function public.rpc_create_user_api_key() from public, anon;
revoke all on function public.rpc_revoke_user_api_key(uuid) from public, anon;
grant execute on function public.rpc_create_user_api_key() to authenticated;
grant execute on function public.rpc_revoke_user_api_key(uuid) to authenticated;
