#!/usr/bin/env python3
"""
Factory-report — POST'et een usage-record + event-stream naar de
jira-poller's /agent-run/complete-endpoint aan het einde van een
runner-Job.

Wordt aangeroepen door runner.sh nadat Claude succesvol klaar is
(of net daarna in trap on_exit, om óók faalpaden te rapporteren).

Strategie:
  1. Lees /tmp/claude.log.jsonl (de stream-json output).
  2. Pas regex-redactie toe op elke regel (vervangt API-keys, JWTs,
     DB-creds door <REDACTED>-placeholder).
  3. Parse events, vind het `result`-event voor token-counts.
  4. POST {story_key, role, job_name, model, effort, level, outcome,
           input_tokens, output_tokens, events} naar de poller.

Failures zijn niet-kritiek voor de runner zelf: bij netwerk- of DB-
problemen schrijft het script een waarschuwing en exit 0. De PR en
JIRA-comments van de runner zijn al gepost — de observability-laag
is best-effort.

Env-vars:
  STORY_ID              JIRA story-key (bv. KAN-42)
  JOB_NAME              K8s Job-naam (bv. claude-run-kan-42-20260514-103200)
  AGENT_ROLE            'refiner' | 'developer' | 'reviewer' | 'tester'
                        (default 'developer' tot we andere rollen hebben)
  CLAUDE_MODEL          model dat gebruikt is (bv. claude-sonnet-4-6)
  CLAUDE_EFFORT         'quick' | 'default' | 'deep'
  AI_LEVEL              integer 0-10 (snapshot)
  FACTORY_POLLER_URL    base-URL van de poller-service
                        (bv. http://jira-poller.pnf-software-factory.svc.cluster.local:8080)
  RUNNER_OUTCOME        optioneel — 'success' (default), 'failed', 'killed'
"""

import json
import os
import re
import sys
import urllib.error
import urllib.request

LOG_FILE = "/tmp/claude.log.jsonl"
TIMEOUT_SEC = 30

# Regex-patronen voor secret-redactie. Niet waterdicht — voldoende voor
# de bekende leak-vectors van onze stack.
REDACT_PATTERNS = [
    (re.compile(r"sk-ant-(api03|oat01)-[A-Za-z0-9_-]+"),  "<REDACTED-ANTHROPIC>"),
    (re.compile(r"ghp_[A-Za-z0-9]{36,}"),                  "<REDACTED-GITHUB>"),
    (re.compile(r"github_pat_[A-Za-z0-9_]+"),              "<REDACTED-GITHUB>"),
    (re.compile(r"eyJ[A-Za-z0-9_-]{16,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+"),
                                                            "<REDACTED-JWT>"),
    (re.compile(r"postgresql://([^:]*):([^@]*)@"),         r"postgresql://\1:<REDACTED>@"),
]


def redact(s: str) -> str:
    for pattern, repl in REDACT_PATTERNS:
        s = pattern.sub(repl, s)
    return s


def main() -> int:
    poller_url = os.environ.get("FACTORY_POLLER_URL", "").rstrip("/")
    if not poller_url:
        print("[factory-report] FACTORY_POLLER_URL niet gezet — skip rapportage.")
        return 0

    story_key = os.environ.get("STORY_ID", "")
    if not story_key:
        print("[factory-report] STORY_ID niet gezet — skip rapportage.", file=sys.stderr)
        return 0

    events: list[dict] = []
    # Velden uit het terminal `result`-event. Initialiseer op 0 zodat
    # afwezige velden gewoon als 0 doorkomen in de DB.
    total_input = 0
    total_output = 0
    cache_read = 0
    cache_creation = 0
    cost_usd = 0.0
    num_turns = 0
    duration_ms = 0
    # Samenvatting = de finale assistant-tekst (`result.result`).
    # Wordt door runner.sh ook gebruikt als JIRA-comment, hier voor
    # DB-opslag zodat dashboard 'm zonder JSONB-uitpluis kan tonen.
    summary_text = ""
    outcome = os.environ.get("RUNNER_OUTCOME", "success")

    try:
        with open(LOG_FILE, "r", encoding="utf-8") as f:
            for line in f:
                line = line.rstrip("\n")
                if not line:
                    continue
                redacted = redact(line)
                try:
                    payload = json.loads(redacted)
                except json.JSONDecodeError:
                    events.append({
                        "kind": "raw",
                        "payload": {"text": redacted[:1000]},
                    })
                    continue

                kind = payload.get("type", "unknown") if isinstance(payload, dict) else "unknown"
                events.append({"kind": kind, "payload": payload})

                # Token-counts + cost + meta uit het terminal `result`-event.
                if isinstance(payload, dict) and payload.get("type") == "result":
                    usage = payload.get("usage") or {}
                    total_input    = int(usage.get("input_tokens", 0) or 0)
                    total_output   = int(usage.get("output_tokens", 0) or 0)
                    cache_read     = int(usage.get("cache_read_input_tokens", 0) or 0)
                    cache_creation = int(usage.get("cache_creation_input_tokens", 0) or 0)
                    try:
                        cost_usd = float(payload.get("total_cost_usd", 0.0) or 0.0)
                    except (TypeError, ValueError):
                        cost_usd = 0.0
                    num_turns   = int(payload.get("num_turns", 0) or 0)
                    duration_ms = int(payload.get("duration_ms", 0) or 0)
                    summary_text = (payload.get("result") or "").strip()
                    subtype = payload.get("subtype")
                    if subtype and outcome == "success":
                        outcome = subtype  # bv. 'success', 'error_max_turns', ...
    except FileNotFoundError:
        print(f"[factory-report] {LOG_FILE} ontbreekt — empty events.")
        outcome = "no-log"
    except OSError as e:
        print(f"[factory-report] kan {LOG_FILE} niet lezen: {e}", file=sys.stderr)
        outcome = "log-read-error"

    body = {
        "story_key": story_key,
        "job_name": os.environ.get("JOB_NAME", ""),
        "role": os.environ.get("AGENT_ROLE", "developer"),
        "model": os.environ.get("CLAUDE_MODEL", ""),
        "effort": os.environ.get("CLAUDE_EFFORT", ""),
        "level": int(os.environ.get("AI_LEVEL", "0") or 0),
        "outcome": outcome,
        "input_tokens": total_input,
        "output_tokens": total_output,
        "cache_read_input_tokens": cache_read,
        "cache_creation_input_tokens": cache_creation,
        "cost_usd": cost_usd,
        "num_turns": num_turns,
        "duration_ms": duration_ms,
        "summary_text": redact(summary_text),
        "events": events,
    }

    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        f"{poller_url}/agent-run/complete",
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_SEC) as resp:
            print(f"[factory-report] OK ({resp.status}) — story={story_key} "
                  f"tokens={total_input}→{total_output} cache_r={cache_read} "
                  f"cache_c={cache_creation} cost=${cost_usd:.4f} "
                  f"turns={num_turns} events={len(events)}")
        return 0
    except urllib.error.HTTPError as e:
        print(f"[factory-report] HTTP {e.code}: {e.reason}", file=sys.stderr)
        try:
            print(f"[factory-report] body: {e.read().decode('utf-8', errors='replace')[:300]}",
                  file=sys.stderr)
        except Exception:
            pass
        return 0  # non-critical
    except (urllib.error.URLError, TimeoutError) as e:
        print(f"[factory-report] network error: {e}", file=sys.stderr)
        return 0  # non-critical


if __name__ == "__main__":
    sys.exit(main())
