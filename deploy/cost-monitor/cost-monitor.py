#!/usr/bin/env python3
"""Cost-monitor — sanity-net voor budget-overrun (KAN-41).

De realtime budget-check in de poller's /agent-run/complete (KAN-39) faalt
als de runner crasht vóór de POST. Deze CronJob scant elke 5 minuten
alle actieve story_runs in factory.story_runs en past dezelfde drempels
toe als de realtime-check:

  >=75%  → comment [COST-MONITOR] 75%
  >=90%  → comment [COST-MONITOR] 90%
  >=100% → comment + transitie AI Needs Info + phase=awaiting-po

Idempotent: bestaande [COST-MONITOR] N%-markers in een story blokkeren
re-posting van dezelfde drempel.

Run-mode: one-shot — de CronJob handelt de scheduling af, dit script
doet één scan en exit. Output op stdout zodat `oc logs job/...` werkt.
"""

import logging
import os
import sys
from typing import Optional

import psycopg
import requests

# ─── config ───────────────────────────────────────────────────────────────

FACTORY_DATABASE_URL = os.environ.get("FACTORY_DATABASE_URL", "")
JIRA_BASE_URL = os.environ.get("JIRA_BASE_URL", "").rstrip("/")
JIRA_EMAIL = os.environ.get("JIRA_EMAIL", "")
JIRA_API_KEY = os.environ.get("JIRA_API_KEY", "")
DEFAULT_TOKEN_BUDGET = int(os.environ.get("DEFAULT_TOKEN_BUDGET", "40000"))
# Statussen waarvoor de check zinvol is. Stories die al gepauzeerd zijn
# (AI Paused / AI Needs Info) skippen we — die wachten al op de PO.
ACTIVE_STATUSES = {"AI Queued", "AI IN PROGRESS", "AI Ready"}

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [cost-monitor] %(message)s",
    datefmt="%Y-%m-%dT%H:%M:%SZ",
)
log = logging.getLogger("cost-monitor")

# Marker-strings — identiek aan poller.py (KAN-39).
MARKER_75 = "[COST-MONITOR] 75%"
MARKER_90 = "[COST-MONITOR] 90%"
MARKER_100 = "[COST-MONITOR] 100%"

# Custom-field-display-namen identiek aan poller's AI_FIELD_NAMES.
AI_FIELD_NAMES = {
    "token_budget":  "AI Token Budget",
    "tokens_used":   "AI Tokens Used",
    "phase":         "AI Phase",
    "resume_phase":  "AI Resume Phase",
}

_field_id_cache: dict[str, Optional[str]] = {}

_jira_session = requests.Session()
if JIRA_EMAIL and JIRA_API_KEY:
    _jira_session.auth = (JIRA_EMAIL, JIRA_API_KEY)
_jira_session.headers.update({
    "Accept": "application/json",
    "Content-Type": "application/json",
})


# ─── JIRA helpers ─────────────────────────────────────────────────────────

def jira_get(path: str, params: Optional[dict] = None) -> Optional[dict]:
    if not JIRA_BASE_URL:
        return None
    try:
        r = _jira_session.get(f"{JIRA_BASE_URL}{path}", params=params, timeout=10)
    except requests.RequestException as e:
        log.warning("jira GET %s faalde: %s", path, e)
        return None
    if r.status_code != 200:
        log.warning("jira GET %s -> %s", path, r.status_code)
        return None
    return r.json()


def jira_post(path: str, body: dict) -> bool:
    if not JIRA_BASE_URL:
        return False
    try:
        r = _jira_session.post(f"{JIRA_BASE_URL}{path}", json=body, timeout=10)
    except requests.RequestException as e:
        log.warning("jira POST %s faalde: %s", path, e)
        return False
    if r.status_code not in (200, 201, 204):
        log.warning("jira POST %s -> %s %s", path, r.status_code, r.text[:200])
        return False
    return True


def jira_put(path: str, body: dict) -> bool:
    try:
        r = _jira_session.put(f"{JIRA_BASE_URL}{path}", json=body, timeout=10)
    except requests.RequestException as e:
        log.warning("jira PUT %s faalde: %s", path, e)
        return False
    if r.status_code not in (200, 201, 204):
        log.warning("jira PUT %s -> %s %s", path, r.status_code, r.text[:200])
        return False
    return True


def discover_field_ids() -> None:
    if _field_id_cache:
        return
    data = jira_get("/rest/api/3/field")
    if not data:
        return
    by_name = {f.get("name", ""): f.get("id", "") for f in data}
    for short, display in AI_FIELD_NAMES.items():
        _field_id_cache[short] = by_name.get(display)


def field_id(short: str) -> Optional[str]:
    discover_field_ids()
    return _field_id_cache.get(short)


def fields_param() -> str:
    discover_field_ids()
    out = "summary,status"
    for fid in _field_id_cache.values():
        if fid:
            out += f",{fid}"
    return out


def post_comment(issue_key: str, text: str) -> bool:
    body = {"body": {
        "type": "doc", "version": 1,
        "content": [{"type": "paragraph",
                     "content": [{"type": "text", "text": text}]}],
    }}
    return jira_post(f"/rest/api/3/issue/{issue_key}/comment", body)


def has_comment_containing(issue_key: str, needle: str) -> bool:
    data = jira_get(f"/rest/api/3/issue/{issue_key}/comment",
                    params={"maxResults": "100"})
    if not data:
        return False
    for c in data.get("comments", []):
        if needle in _adf_to_plain(c.get("body")):
            return True
    return False


def _adf_to_plain(node) -> str:
    if node is None:
        return ""
    if isinstance(node, list):
        return "".join(_adf_to_plain(c) for c in node)
    if isinstance(node, dict):
        if node.get("type") == "text":
            return node.get("text", "") or ""
        return _adf_to_plain(node.get("content"))
    return ""


def set_ai_fields(issue_key: str, updates: dict) -> bool:
    payload = {}
    for short, value in updates.items():
        fid = field_id(short)
        if fid:
            payload[fid] = value
    if not payload:
        return False
    return jira_put(f"/rest/api/3/issue/{issue_key}",
                   {"fields": payload})


def transition_to(issue_key: str, target_status: str) -> bool:
    data = jira_get(f"/rest/api/3/issue/{issue_key}/transitions")
    if not data:
        return False
    for t in data.get("transitions", []):
        if (t.get("to") or {}).get("name") == target_status:
            return jira_post(
                f"/rest/api/3/issue/{issue_key}/transitions",
                {"transition": {"id": t["id"]}},
            )
    log.warning("transitie naar %r niet beschikbaar voor %s",
                target_status, issue_key)
    return False


# ─── Core check ───────────────────────────────────────────────────────────

def check_one_story(story_key: str, tokens_used: int) -> None:
    """Pas de drempels toe op één story. Idempotent op marker-niveau."""
    issue = jira_get(f"/rest/api/3/issue/{story_key}",
                     params={"fields": fields_param()})
    if not issue:
        return
    fields = issue.get("fields") or {}
    status_name = ((fields.get("status") or {}).get("name") or "")
    # Skip stories die al gepauzeerd zijn / niet meer actief.
    if status_name not in ACTIVE_STATUSES:
        log.info("  %s: status=%r — skip", story_key, status_name)
        return

    budget_raw = fields.get(field_id("token_budget") or "")
    try:
        budget = int(budget_raw) if budget_raw is not None else None
    except (TypeError, ValueError):
        budget = None
    if budget is None or budget <= 0:
        budget = DEFAULT_TOKEN_BUDGET
    pct = (tokens_used * 100) // budget if budget > 0 else 0
    log.info("  %s: used=%d budget=%d pct=%d status=%s",
             story_key, tokens_used, budget, pct, status_name)

    # Tokens_used-field bijwerken zodat het dashboard 'm kan tonen.
    set_ai_fields(story_key, {"tokens_used": tokens_used})

    if pct >= 100:
        if has_comment_containing(story_key, MARKER_100):
            return
        # Welke phase loopt? De active runner kan refining/developing/
        # reviewing/testing zijn; we lezen 'm uit de AI Phase-field.
        phase_now = fields.get(field_id("phase") or "") or "developing"
        # Voor de awaiting-po-flow stop ik 't 'ing-form' (refining/...) in
        # resume_phase. Als phase_now al een -ed/-ok/-fail-vorm heeft,
        # fallback naar 'developing' want dan was de POST gemist tijdens
        # development.
        resume_phase = (phase_now if phase_now in
                       ("refining", "developing", "reviewing", "testing")
                       else "developing")
        updates = {"phase": "awaiting-po", "resume_phase": resume_phase}
        set_ai_fields(story_key, updates)
        transition_to(story_key, "AI Needs Info")
        post_comment(
            story_key,
            f"{MARKER_100} — budget bereikt "
            f"({tokens_used}/{budget} tokens) [via cost-monitor sanity-net]. "
            f"Story is gepauzeerd op phase={resume_phase}.\n\n"
            "Om door te gaan, plaats één van deze comments:\n"
            "  BUDGET=120000   (zet nieuw absoluut budget in tokens)\n"
            "  CONTINUE        (verhoog huidig budget met +50%)\n",
        )
        return

    if pct >= 90:
        if not has_comment_containing(story_key, MARKER_90):
            post_comment(
                story_key,
                f"{MARKER_90} — bijna op "
                f"({tokens_used}/{budget} tokens) [via cost-monitor]. "
                "Bij 100% wordt de story automatisch gepauzeerd.",
            )
        return

    if pct >= 75:
        if not has_comment_containing(story_key, MARKER_75):
            post_comment(
                story_key,
                f"{MARKER_75} van budget bereikt "
                f"({tokens_used}/{budget} tokens) [via cost-monitor].",
            )


def main() -> int:
    if not FACTORY_DATABASE_URL:
        log.error("FACTORY_DATABASE_URL ontbreekt — kan niet scannen")
        return 1
    if not (JIRA_BASE_URL and JIRA_EMAIL and JIRA_API_KEY):
        log.error("JIRA-credentials onvolledig — kan geen budget-check posten")
        return 1

    log.info("cost-monitor start — scan actieve story_runs")
    try:
        with psycopg.connect(FACTORY_DATABASE_URL) as conn, conn.cursor() as cur:
            cur.execute(
                """SELECT story_key,
                          COALESCE(total_input_tokens, 0)
                          + COALESCE(total_output_tokens, 0) AS tokens_used
                   FROM factory.story_runs
                   WHERE ended_at IS NULL
                   ORDER BY started_at DESC""",
            )
            rows = cur.fetchall()
    except Exception as e:
        log.exception("DB-query faalde: %s", e)
        return 1

    log.info("scan %d actieve story_run(s)", len(rows))
    for story_key, tokens_used in rows:
        try:
            check_one_story(story_key, int(tokens_used or 0))
        except Exception as e:
            log.exception("check %s faalde: %s", story_key, e)
    log.info("cost-monitor klaar")
    return 0


if __name__ == "__main__":
    sys.exit(main())
