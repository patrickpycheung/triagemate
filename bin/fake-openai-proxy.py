#!/usr/bin/env python3
"""Stub OpenAI-compatible server — a stand-in for the Copilot proxy (E2).

Why this exists
---------------
`bin/e2-proxy-spike.sh` validates a real Copilot->OpenAI proxy on the corp
laptop. This stub lets you exercise the spike (and the app's LLM wiring) on any
machine, with no Copilot seat and no network, so a broken *spike* is never
mistaken for a broken *proxy*.

Its most important trick is `--drop-tools`, which reproduces the highest-risk
real-world failure: a proxy that accepts the `tools` field and silently ignores
it, answering with prose where the ADK agent loop needs `tool_calls`. That
failure is invisible to a `/v1/models` check and degrades D1 to a single-shot
answer with no evidence trail.

Usage
-----
    # compliant proxy — returns tool_calls when tools are declared
    ./bin/fake-openai-proxy.py

    # broken proxy — accepts `tools`, ignores them, answers with prose
    ./bin/fake-openai-proxy.py --drop-tools

    # then, in another terminal:
    ./bin/e2-proxy-spike.sh http://localhost:4000/v1 claude-opus-4.6
    #   default    -> 4 passed, 0 failed   (exit 0)
    #   --drop-tools -> check 4 fails      (exit 1)

Every inbound request is logged to --log as JSON, so you can inspect exactly
what our app sends: auth header, model id, temperature, declared tool names,
message roles. That request shape IS the contract a real proxy must satisfy.

NOT FOR PRODUCTION. Binds to 127.0.0.1, no auth, canned responses.
"""
from __future__ import annotations

import argparse
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

DEFAULT_MODELS = ["claude-opus-4.6", "gpt-5.3-codex"]


class StubHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    # injected by build_server()
    models: list[str]
    drop_tools: bool
    log_path: str | None
    log: list[dict]

    # ---------- plumbing ----------

    def _send(self, obj: dict, code: int = 200) -> None:
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _record(self, entry: dict) -> None:
        self.log.append(entry)
        print(f">>> #{len(self.log)} {entry.get('method')} {entry.get('path')}",
              file=sys.stderr)
        print(json.dumps(entry, indent=2), file=sys.stderr)
        if self.log_path:
            with open(self.log_path, "w") as fh:
                json.dump(self.log, fh, indent=2)

    def log_message(self, *_args) -> None:  # silence default access log
        pass

    # ---------- endpoints ----------

    def do_GET(self) -> None:
        if self.path.rstrip("/").endswith("/models"):
            self._record({"method": "GET", "path": self.path})
            self._send({
                "object": "list",
                "data": [
                    {"id": m, "object": "model", "owned_by": "github-copilot"}
                    for m in self.models
                ],
            })
        else:
            self._send({"error": {"message": "not found"}}, 404)

    def do_POST(self) -> None:
        if not self.path.rstrip("/").endswith("/chat/completions"):
            self._send({"error": {"message": "not found"}}, 404)
            return

        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length).decode()
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            payload = {"_unparsed": raw}

        tools = payload.get("tools") or []
        self._record({
            "method": "POST",
            "path": self.path,
            "auth_header": self.headers.get("Authorization"),
            "content_type": self.headers.get("Content-Type"),
            "model": payload.get("model"),
            "temperature": payload.get("temperature"),
            "stream": payload.get("stream"),
            "n_messages": len(payload.get("messages", [])),
            "roles": [m.get("role") for m in payload.get("messages", [])],
            "tools_declared": [t.get("function", {}).get("name") for t in tools],
            "tool_choice": payload.get("tool_choice"),
            "tools_honoured": bool(tools) and not self.drop_tools,
        })

        model = payload.get("model") or (self.models[0] if self.models else "stub")

        # A compliant proxy passes `tools` through and can return tool_calls.
        # With --drop-tools we deliberately answer prose instead — the silent
        # failure mode this stub exists to reproduce.
        if tools and not self.drop_tools:
            fn = tools[0].get("function", {}).get("name", "unknown_tool")
            message = {
                "role": "assistant",
                "content": None,
                "tool_calls": [{
                    "id": "call_stub1",
                    "type": "function",
                    "function": {"name": fn,
                                 "arguments": json.dumps({"number": "INC0012345"})},
                }],
            }
            finish = "tool_calls"
        else:
            note = ("STUB-PROXY-OK: reached the OpenAI-compatible endpoint."
                    if not tools else
                    "STUB-PROXY (--drop-tools): tools were declared and IGNORED. "
                    "A real proxy behaving this way silently breaks the agent loop.")
            message = {"role": "assistant", "content": note}
            finish = "stop"

        self._send({
            "id": "chatcmpl-stub",
            "object": "chat.completion",
            "created": 0,
            "model": model,
            "choices": [{"index": 0, "message": message, "finish_reason": finish}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        })


def build_server(args: argparse.Namespace) -> HTTPServer:
    handler = type("BoundStubHandler", (StubHandler,), {
        "models": args.model,
        "drop_tools": args.drop_tools,
        "log_path": args.log,
        "log": [],
    })
    return HTTPServer((args.host, args.port), handler)


def main() -> int:
    p = argparse.ArgumentParser(
        description="Stub OpenAI-compatible server standing in for the Copilot proxy.",
        epilog="See bin/e2-proxy-spike.sh — this stub exercises it offline.",
    )
    p.add_argument("--port", type=int, default=4000, help="default: 4000")
    p.add_argument("--host", default="127.0.0.1", help="default: 127.0.0.1 (localhost only)")
    p.add_argument("--model", action="append", default=None,
                   help="model id to advertise; repeatable "
                        f"(default: {', '.join(DEFAULT_MODELS)})")
    p.add_argument("--drop-tools", action="store_true",
                   help="simulate a proxy that accepts `tools` but ignores them, "
                        "answering with prose instead of tool_calls")
    p.add_argument("--log", default=None, help="write request log here as JSON")
    args = p.parse_args()
    if args.model is None:
        args.model = list(DEFAULT_MODELS)

    server = build_server(args)
    mode = "DROPPING tools (simulating a broken proxy)" if args.drop_tools \
        else "honouring tools (compliant proxy)"
    print(f"stub proxy: http://{args.host}:{args.port}/v1 — {mode}", file=sys.stderr)
    print(f"models: {', '.join(args.model)}", file=sys.stderr)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
