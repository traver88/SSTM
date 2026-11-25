#!/usr/bin/env python3
"""Create and remove a temporary Supabase user while testing MCP end to end."""

from __future__ import annotations

import json
import os
import secrets
import urllib.error
import urllib.request
import uuid


SUPABASE_URL = os.environ["SUPABASE_URL"].rstrip("/")
SUPABASE_SERVICE_ROLE_KEY = os.environ["SUPABASE_SERVICE_ROLE_KEY"]
MCP_URL = os.environ.get("MCP_URL", "http://127.0.0.1:8787/mcp")
ANON_KEY = os.environ["ANON_KEY"]


def request_json(url: str, *, method: str = "GET", headers: dict[str, str] | None = None, body: object | None = None):
    request_headers = {"Accept": "application/json", **(headers or {})}
    payload = None
    if body is not None:
        payload = json.dumps(body).encode()
        request_headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=payload, headers=request_headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            text = response.read().decode()
            return response.status, response.headers, json.loads(text) if text else None
    except urllib.error.HTTPError as error:
        error.read()
        raise RuntimeError(f"HTTP {error.code} for {method} {url}") from None


def assert_tool_success(response: dict) -> dict:
    if response.get("error"):
        raise RuntimeError("MCP returned a JSON-RPC error")
    result = response.get("result", {})
    if result.get("isError"):
        content = result.get("content", [])
        detail = json.loads(content[0]["text"]) if content else {}
        code = detail.get("error_code", "unknown")
        message = detail.get("message", "MCP tool returned an error")
        raise RuntimeError(f"MCP tool returned {code}: {message}")
    content = result.get("content", [])
    if not content:
        raise RuntimeError("MCP returned no tool content")
    data = json.loads(content[0]["text"])
    if not data.get("ok"):
        raise RuntimeError(f"MCP returned business error {data.get('error_code', 'unknown')}")
    return data.get("data", {})


def call_mcp(name: str, arguments: dict, *, headers: dict[str, str], session_id: str) -> dict:
    _, _, response = request_json(
        MCP_URL,
        method="POST",
        headers={**headers, "MCP-Session-Id": session_id, "MCP-Protocol-Version": "2025-03-26"},
        body={
            "jsonrpc": "2.0",
            "id": str(uuid.uuid4()),
            "method": "tools/call",
            "params": {"name": name, "arguments": arguments},
        },
    )
    return assert_tool_success(response)


def main() -> None:
    email = f"yantu-smoke-{uuid.uuid4().hex[:12]}@example.com"
    password = secrets.token_urlsafe(32)
    user_id: str | None = None
    api_key_id: str | None = None
    api_key: str | None = None
    mcp_session_id: str | None = None

    admin_headers = {
        "apikey": SUPABASE_SERVICE_ROLE_KEY,
        "Authorization": f"Bearer {SUPABASE_SERVICE_ROLE_KEY}",
    }
    user_headers: dict[str, str] = {}

    try:
        _, _, created_user = request_json(
            f"{SUPABASE_URL}/auth/v1/admin/users",
            method="POST",
            headers=admin_headers,
            body={"email": email, "password": password, "email_confirm": True},
        )
        user_id = created_user["id"]

        _, _, login = request_json(
            f"{SUPABASE_URL}/auth/v1/token?grant_type=password",
            method="POST",
            headers={"apikey": ANON_KEY},
            body={"email": email, "password": password},
        )
        access_token = login["access_token"]
        user_headers = {"apikey": ANON_KEY, "Authorization": f"Bearer {access_token}"}

        _, _, key_result = request_json(
            f"{SUPABASE_URL}/rest/v1/rpc/rpc_create_user_api_key",
            method="POST",
            headers=user_headers,
            body={},
        )
        api_key_id = key_result["id"]
        api_key = key_result["api_key"]
        mcp_headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
        }

        _, init_headers, initialized = request_json(
            MCP_URL,
            method="POST",
            headers={**mcp_headers, "Accept": "application/json, text/event-stream"},
            body={
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2025-03-26",
                    "capabilities": {},
                    "clientInfo": {"name": "yantu-deployment-smoke-test", "version": "1.0"},
                },
            },
        )
        mcp_session_id = init_headers.get("mcp-session-id")
        if not mcp_session_id:
            raise RuntimeError("MCP initialization did not return a session ID")
        if initialized.get("result", {}).get("protocolVersion") != "2025-03-26":
            raise RuntimeError("MCP negotiated an unexpected protocol version")

        call_mcp(
            "upsert_profile_and_subjects",
            {"exam_date": "2026-12-20", "subjects": [{"name": "数学一"}]},
            headers=mcp_headers,
            session_id=mcp_session_id,
        )
        road_map = call_mcp("get_exam_roadmap", {}, headers=mcp_headers, session_id=mcp_session_id)
        if road_map.get("profile") is None:
            raise RuntimeError("New test account did not return its profile")

        session_id = str(uuid.uuid4())
        study_args = {
            "session_id": session_id,
            "subject_name": "数学一",
            "activity_type": "output",
            "duration_minutes": 25,
            "focus_score": 2,
        }
        first_log = call_mcp("log_study_session", study_args, headers=mcp_headers, session_id=mcp_session_id)
        second_log = call_mcp("log_study_session", study_args, headers=mcp_headers, session_id=mcp_session_id)
        if first_log.get("duplicate") is not False or second_log.get("duplicate") is not True:
            raise RuntimeError("Study-session retry was not idempotent")

        print("Supabase Auth registration/login, MCP initialize, roadmap read, and idempotent study logging passed.")
    finally:
        if mcp_session_id and api_key:
            try:
                request_json(
                    MCP_URL,
                    method="DELETE",
                    headers={"Authorization": f"Bearer {api_key}", "MCP-Session-Id": mcp_session_id},
                )
            except Exception:
                pass
        if api_key_id and user_headers:
            try:
                request_json(
                    f"{SUPABASE_URL}/rest/v1/rpc/rpc_revoke_user_api_key",
                    method="POST",
                    headers=user_headers,
                    body={"p_key_id": api_key_id},
                )
            except Exception:
                print("Temporary smoke API Key cleanup needs server-side verification.")
        if user_id:
            try:
                request_json(
                    f"{SUPABASE_URL}/auth/v1/admin/users/{user_id}",
                    method="DELETE",
                    headers=admin_headers,
                )
            except Exception:
                print("Temporary smoke user cleanup needs server-side verification.")


if __name__ == "__main__":
    main()
