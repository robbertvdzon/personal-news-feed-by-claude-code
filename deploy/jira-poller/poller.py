#!/usr/bin/env python3
"""
JIRA-poller voor de AI-driven dev pipeline (S-03 + S-04).

Loop:
  1. Pol JIRA elke POLL_INTERVAL seconden op issues in `JIRA_SOURCE_STATUS`
     (bv. "AI Ready") binnen `JIRA_PROJECT`.
  2. Voor elke gevonden issue:
       - Check concurrency cap (aantal lopende claude-runner Jobs).
       - Transition status van `AI Ready` → `AI IN PROGRESS` (atomair claim).
       - Bij success: maak een ConfigMap met de issue-beschrijving als
         `task.md` en spawn een claude-runner K8s Job.
  3. Jobs lopen autonoom verder (zie deploy/claude-runner/).

Environment:
  JIRA_BASE_URL         bv. https://vdzon.atlassian.net
  JIRA_EMAIL            bv. robbert@vdzon.com
  JIRA_API_KEY          API-token (uit ATLASSIAN_API_KEY secret-key)
  JIRA_PROJECT          bv. KAN
  JIRA_SOURCE_STATUS    bv. "AI Ready"
  JIRA_TARGET_STATUS    bv. "AI IN PROGRESS"
  POLL_INTERVAL_SEC     default 30
  MAX_CONCURRENT_JOBS   default 2
  CLAUDE_RUNNER_IMAGE   bv. ghcr.io/robbertvdzon/claude-runner:main
  RUNNER_NAMESPACE      bv. personal-news-feed
  REPO_URL              bv. https://github.com/.../personal-news-feed-by-claude-code.git
"""

import base64
import json
import logging
import os
import re
import subprocess
import threading
import sys
import time
from typing import Optional

import requests
import yaml
from flask import Flask, jsonify, request as flask_request

# psycopg v3 voor de factory-DB (Fase 1). Import is "soft" — als de
# package nog niet beschikbaar is (oude image) draait de poller alsnog,
# alleen de HTTP-endpoint geeft 503.
try:
    import psycopg
except ImportError:  # pragma: no cover
    psycopg = None  # type: ignore

# Module-level placeholder for `re.match` is used in config section below;
# the import must come first.

# ─── config ───────────────────────────────────────────────────────────────

JIRA_BASE_URL = os.environ["JIRA_BASE_URL"].rstrip("/")
JIRA_EMAIL = os.environ["JIRA_EMAIL"]
JIRA_API_KEY = os.environ["JIRA_API_KEY"]
JIRA_PROJECT = os.environ["JIRA_PROJECT"]
JIRA_SOURCE_STATUS = os.environ.get("JIRA_SOURCE_STATUS", "AI Ready")
JIRA_TARGET_STATUS = os.environ.get("JIRA_TARGET_STATUS", "AI IN PROGRESS")
JIRA_REVIEW_STATUS = os.environ.get("JIRA_REVIEW_STATUS", "AI IN REVIEW")
JIRA_DONE_STATUS = os.environ.get("JIRA_DONE_STATUS", "Klaar")
# Doel-status na re-implement. NL JIRA gebruikt 'Nog doen' i.p.v. 'To Do'.
JIRA_TODO_STATUS = os.environ.get("JIRA_TODO_STATUS", "Nog doen")
POLL_INTERVAL_SEC = int(os.environ.get("POLL_INTERVAL_SEC", "30"))
MAX_CONCURRENT_JOBS = int(os.environ.get("MAX_CONCURRENT_JOBS", "2"))
CLAUDE_RUNNER_IMAGE = os.environ.get(
    "CLAUDE_RUNNER_IMAGE", "ghcr.io/robbertvdzon/claude-runner:main"
)
# Tester-image — claude-runner + Playwright + Chromium voor screenshots
# (Fase 5). Alleen tester-Jobs gebruiken 'm; andere rollen blijven op
# claude-runner zodat ze geen ~300MB extra hoeven te pullen.
CLAUDE_TESTER_IMAGE = os.environ.get(
    "CLAUDE_TESTER_IMAGE", "ghcr.io/robbertvdzon/claude-tester:main"
)
RUNNER_NAMESPACE = os.environ.get("RUNNER_NAMESPACE", "personal-news-feed")
# DB voor de factory-observability (Fase 1). Bevat schema `factory` met
# story_runs / agent_runs / agent_events. Wordt door de HTTP-endpoint
# `/agent-run/complete` beschreven; lege string betekent niet-
# geconfigureerd en de endpoint geeft 503.
FACTORY_DATABASE_URL = os.environ.get("FACTORY_DATABASE_URL", "")

# Level-matrix (Fase 2). Path naar de ConfigMap-mount; resolver gebruikt
# 'm bij elke spawn opnieuw zodat tunen zonder image-rebuild werkt.
AGENT_LEVELS_PATH = os.environ.get(
    "AGENT_LEVELS_PATH", "/etc/factory/agent-levels.yaml"
)
# Default-level voor stories zonder expliciete `AI Level`-veld in JIRA.
# Sinds Fase 2 PR 2 staat dit op 0 (cheapest); PO bumpt 't naar
# 1-10 per story als die meer power verdient.
DEFAULT_AI_LEVEL = int(os.environ.get("DEFAULT_AI_LEVEL", "0"))
DEFAULT_TOKEN_BUDGET = int(os.environ.get("DEFAULT_TOKEN_BUDGET", "40000"))
# Format-string voor de live preview-URL per PR. {pr} wordt door de
# tester vervangen door het PR-nummer. Komt via env-var; default
# matched onze huidige Cloudflare-route.
PREVIEW_URL_FORMAT = os.environ.get(
    "PREVIEW_URL_FORMAT", "https://pnf-pr-{pr}.vdzonsoftware.nl"
)
REPO_URL = os.environ.get(
    "REPO_URL",
    "https://github.com/robbertvdzon/personal-news-feed-by-claude-code.git",
)
GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN", "")
# Owner + repo afleiden uit REPO_URL voor de PR-API.
_m = re.match(r"https?://github\.com/([^/]+)/([^/.]+)", REPO_URL)
GITHUB_OWNER = _m.group(1) if _m else ""
GITHUB_REPO = _m.group(2) if _m else ""

# OpenShift Console — hostname (zonder https://). Wordt gebruikt om
# klikbare links naar Job-logs te plaatsen in JIRA. Werkt alleen op het
# thuis-netwerk tenzij de console-route extern bereikbaar is.
OPENSHIFT_CONSOLE_HOST = os.environ.get("OPENSHIFT_CONSOLE_HOST", "")

# S-09: trigger-pattern in PR-comments. Een comment met deze substring
# (case-insensitive) op een open ai/-PR start een runner-iteratie. De
# poller markeert verwerkte triggers met een 'eyes'-reactie zodat dezelfde
# comment niet twee keer wordt opgepakt.
COMMENT_TRIGGER = os.environ.get("COMMENT_TRIGGER_PATTERN", "@claude").lower()

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [poller] %(message)s",
    datefmt="%Y-%m-%dT%H:%M:%SZ",
)
log = logging.getLogger("poller")

session = requests.Session()
session.auth = (JIRA_EMAIL, JIRA_API_KEY)
session.headers.update({"Accept": "application/json"})


# ─── helpers ──────────────────────────────────────────────────────────────


def jira(method: str, path: str, **kwargs) -> requests.Response:
    """Wrap requests met de JIRA base-URL + auth. Raise op 5xx."""
    url = f"{JIRA_BASE_URL}{path}"
    r = session.request(method, url, timeout=15, **kwargs)
    if r.status_code >= 500:
        r.raise_for_status()
    return r


def adf_to_markdown(node) -> str:
    """
    Vlak een Atlassian Document Format-tree af naar een markdown-achtige
    string. Niet 100% volledig, maar Claude kan er prima mee om.
    """
    if node is None:
        return ""
    if isinstance(node, str):
        return node
    if isinstance(node, list):
        return "".join(adf_to_markdown(c) for c in node)
    if not isinstance(node, dict):
        return ""

    t = node.get("type")
    content = node.get("content", [])

    if t == "text":
        txt = node.get("text", "")
        # Markeer fat/cursief eenvoudig
        for mark in node.get("marks", []):
            if mark.get("type") == "strong":
                txt = f"**{txt}**"
            elif mark.get("type") == "em":
                txt = f"*{txt}*"
            elif mark.get("type") == "code":
                txt = f"`{txt}`"
        return txt

    if t == "paragraph":
        return adf_to_markdown(content) + "\n\n"
    if t == "heading":
        level = node.get("attrs", {}).get("level", 1)
        return "#" * level + " " + adf_to_markdown(content) + "\n\n"
    if t in ("bulletList", "orderedList"):
        out = ""
        for i, item in enumerate(content, 1):
            bullet = "- " if t == "bulletList" else f"{i}. "
            out += bullet + adf_to_markdown(item).strip() + "\n"
        return out + "\n"
    if t == "listItem":
        return adf_to_markdown(content).strip()
    if t == "codeBlock":
        return "```\n" + adf_to_markdown(content) + "\n```\n\n"
    if t == "hardBreak":
        return "\n"

    # Default: render children
    return adf_to_markdown(content)


def get_transitions(issue_key: str) -> list[dict]:
    """Lijst beschikbare transitions voor een issue."""
    r = jira("GET", f"/rest/api/3/issue/{issue_key}/transitions")
    if r.status_code != 200:
        log.warning("transitions for %s -> %s", issue_key, r.status_code)
        return []
    return r.json().get("transitions", [])


def find_transition_id(transitions: list[dict], target_status: str) -> Optional[str]:
    """Zoek de transition-id die naar `target_status` leidt."""
    for tr in transitions:
        to = tr.get("to", {})
        if to.get("name") == target_status:
            return tr.get("id")
    return None


def transition_issue(issue_key: str, target_status: str) -> bool:
    """Verplaats issue naar target-status. Returns success."""
    transitions = get_transitions(issue_key)
    tr_id = find_transition_id(transitions, target_status)
    if not tr_id:
        log.error(
            "no transition from current to %r for %s. Beschikbaar: %s",
            target_status,
            issue_key,
            [t.get("to", {}).get("name") for t in transitions],
        )
        return False
    r = jira(
        "POST",
        f"/rest/api/3/issue/{issue_key}/transitions",
        json={"transition": {"id": tr_id}},
        headers={"Content-Type": "application/json"},
    )
    if r.status_code in (200, 204):
        return True
    log.error("transition %s failed: %s %s", issue_key, r.status_code, r.text[:200])
    return False


# ─── JIRA custom-field discovery (Fase 2 PR 2) ───────────────────────────
#
# JIRA custom fields zijn gekoppeld aan een veld-ID (`customfield_10042`)
# die per workspace verschilt. We zoeken bij eerste gebruik de IDs op
# basis van de display-naam — robuust tegen workspace-recreate én
# tegen typo's omdat we hier op naam matchen, niet op ID.

# Display-namen exact zoals ze in JIRA staan. Hoofdletter-gevoelig.
AI_FIELD_NAMES = {
    "level":         "AI Level",
    "token_budget":  "AI Token Budget",
    "tokens_used":   "AI Tokens Used",
    "phase":         "AI Phase",
    "resume_phase":  "AI Resume Phase",
}

_field_id_cache: dict[str, Optional[str]] = {}


def _discover_field_ids() -> None:
    """Vul _field_id_cache met de live custom-field-IDs uit JIRA.

    Idempotent. Roep aan vóór elke read/write — eerste keer doet de
    HTTP-call, daarna pakt 'ie uit cache. Bij JIRA-onbereikbaarheid
    blijven IDs leeg en doen reads/writes silently niets.
    """
    if _field_id_cache:
        return
    try:
        r = jira("GET", "/rest/api/3/field")
        if r.status_code != 200:
            log.warning("field-discovery JIRA -> %s", r.status_code)
            return
        by_name: dict[str, str] = {}
        for fld in r.json():
            name = fld.get("name", "")
            fid = fld.get("id", "")
            if name and fid:
                by_name[name] = fid
        for short, display in AI_FIELD_NAMES.items():
            _field_id_cache[short] = by_name.get(display)
            if not _field_id_cache[short]:
                log.warning("custom field %r niet gevonden in JIRA", display)
        log.info(
            "AI custom-field-IDs: %s",
            {k: v for k, v in _field_id_cache.items() if v},
        )
    except Exception as e:
        log.exception("field-discovery faalde: %s", e)


def _ai_field(short: str) -> Optional[str]:
    """Field-ID voor één van de AI_FIELD_NAMES, lazy discovery."""
    _discover_field_ids()
    return _field_id_cache.get(short)


def get_ai_fields(issue: dict) -> dict:
    """Lees AI-fields uit een issue-payload. Mist een veld → None / default."""
    fields = issue.get("fields") or {}

    def f(short: str):
        fid = _ai_field(short)
        return fields.get(fid) if fid else None

    level_raw = f("level")
    budget_raw = f("token_budget")
    used_raw = f("tokens_used")

    return {
        "level":         int(level_raw) if level_raw is not None else None,
        "token_budget":  int(budget_raw) if budget_raw is not None else None,
        "tokens_used":   int(used_raw) if used_raw is not None else None,
        "phase":         f("phase") or None,
        "resume_phase":  f("resume_phase") or None,
    }


def set_ai_fields(issue_key: str, updates: dict) -> bool:
    """Schrijf één of meer AI-fields naar een story. Keys uit AI_FIELD_NAMES.

    Lege updates of ontbrekende field-IDs → no-op (geen error).
    """
    _discover_field_ids()
    payload_fields: dict[str, object] = {}
    for short, value in updates.items():
        fid = _field_id_cache.get(short)
        if not fid:
            continue
        payload_fields[fid] = value
    if not payload_fields:
        return False
    r = jira(
        "PUT",
        f"/rest/api/3/issue/{issue_key}",
        json={"fields": payload_fields},
        headers={"Content-Type": "application/json"},
    )
    if r.status_code in (200, 204):
        return True
    log.warning("set_ai_fields(%s) -> %s %s", issue_key, r.status_code, r.text[:200])
    return False


def _ai_fields_param() -> str:
    """Standaard-fields + alle bekende AI-custom-field-IDs voor JIRA-zoek."""
    _discover_field_ids()
    fields_param = "summary,description,status"
    for fid in _field_id_cache.values():
        if fid:
            fields_param += f",{fid}"
    return fields_param


def fetch_ai_ready_issues() -> list[dict]:
    """Geef alle issues in source-status terug — key + summary + description
    + custom fields."""
    jql = f'project={JIRA_PROJECT} AND status="{JIRA_SOURCE_STATUS}" ORDER BY created ASC'
    r = jira(
        "GET",
        "/rest/api/3/search/jql",
        params={"jql": jql, "fields": _ai_fields_param()},
    )
    if r.status_code != 200:
        log.warning("search faalde: %s %s", r.status_code, r.text[:200])
        return []
    return r.json().get("issues", [])


def fetch_queued_with_phase(phase: str) -> list[dict]:
    """Stories in 'AI Queued' met `AI Phase = <phase>`. Voor de
    dispatcher die de volgende agent uitkiest na een afgeronde fase."""
    return _fetch_status_with_phase("AI Queued", phase)


def fetch_in_review_with_phase(phase: str) -> list[dict]:
    """Stories in 'AI IN REVIEW' met `AI Phase = <phase>`. Voor de
    Fase 4+ dispatcher die reviewer/tester op een gepubliceerde PR
    zet (status blijft AI IN REVIEW zolang de PR open is)."""
    return _fetch_status_with_phase(JIRA_REVIEW_STATUS, phase)


def _fetch_status_with_phase(status: str, phase: str) -> list[dict]:
    phase_field_id = _ai_field("phase")
    if not phase_field_id:
        return []
    # JIRA-JQL voor custom fields: cf[<id-zonder-prefix>] = "value"
    cf_num = phase_field_id.replace("customfield_", "")
    jql = (
        f'project={JIRA_PROJECT} AND status="{status}" '
        f'AND cf[{cf_num}] = "{phase}" ORDER BY updated ASC'
    )
    r = jira(
        "GET",
        "/rest/api/3/search/jql",
        params={"jql": jql, "fields": _ai_fields_param()},
    )
    if r.status_code != 200:
        log.warning("status-phase-search (%s/%s) faalde: %s %s",
                    status, phase, r.status_code, r.text[:200])
        return []
    return r.json().get("issues", [])


def fetch_jira_comments(issue_key: str, limit: int = 50) -> list[dict]:
    """Haal de comment-thread van een JIRA-issue op (chronologisch).

    Returnt een lijst dicts met keys 'author', 'created' (ISO), 'text'
    (gemark-down'de body). Bot-status-updates ('🤖 Claude …', '✅ Gemerged …')
    worden NIET gefilterd hier — caller bepaalt zelf wat relevant is.
    """
    r = jira(
        "GET",
        f"/rest/api/3/issue/{issue_key}/comment",
        params={"maxResults": str(limit), "orderBy": "created"},
    )
    if r.status_code != 200:
        log.warning("comment-fetch voor %s gaf HTTP %d", issue_key, r.status_code)
        return []
    out: list[dict] = []
    for c in r.json().get("comments", []):
        out.append({
            "id":      c.get("id") or "",
            "author":  (c.get("author") or {}).get("displayName") or "?",
            "created": c.get("created") or "",
            "text":    adf_to_markdown(c.get("body")).strip(),
        })
    return out


# Patronen waarop we comments overslaan in task.md. Dit zijn de
# automatische status-updates van poller/runner die geen inhoudelijke
# info dragen voor de agent.
_TASK_MD_COMMENT_SKIP = re.compile(
    r"^\s*(?:🤖|✅)\s",
)


def issue_to_task_md(issue: dict) -> str:
    """Bouw een task.md uit een JIRA-issue (incl. comment-thread).

    De thread wordt meegestuurd zodat refiner/developer eerdere
    [REFINER]/[DEVELOPER]-vragen + PO-antwoorden mee kan lezen — anders
    blijft een agent op elke spawn dezelfde vraag stellen.
    """
    key = issue["key"]
    summary = issue["fields"].get("summary", "(geen titel)")
    desc_node = issue["fields"].get("description")
    body = adf_to_markdown(desc_node).strip() if desc_node else "_(geen beschrijving)_"

    md = f"# {summary}\n\n_JIRA-issue: {key}_\n\n{body}\n"

    comments = fetch_jira_comments(key)
    # Skip pure status-emoji updates van de bot-flow.
    useful = [c for c in comments if c["text"] and not _TASK_MD_COMMENT_SKIP.match(c["text"])]
    if useful:
        md += "\n\n## JIRA-comments (chronologisch)\n\n"
        md += (
            "_De thread hieronder is leidend voor je interpretatie van de story. "
            "Eerdere vragen van agents (`[REFINER]`/`[DEVELOPER]`) en de "
            "antwoorden van de PO daarop zijn authoritative — gebruik ze in "
            "plaats van opnieuw dezelfde vraag te stellen._\n\n"
        )
        for c in useful:
            md += f"### {c['author']} — {c['created']}\n\n{c['text']}\n\n"
    return md


# ─── kubectl wrappers ─────────────────────────────────────────────────────


def kubectl(*args, input_data: Optional[str] = None, check: bool = True) -> subprocess.CompletedProcess:
    cmd = ["kubectl", *args]
    return subprocess.run(
        cmd, input=input_data, capture_output=True, text=True, check=check
    )


def count_active_runner_jobs() -> int:
    """Tel claude-runner Jobs die nog niet Complete/Failed zijn."""
    try:
        out = kubectl(
            "get", "jobs",
            "-n", RUNNER_NAMESPACE,
            "-l", "app=claude-runner",
            "-o", "json",
            check=False,
        )
        if out.returncode != 0:
            log.warning("kubectl get jobs faalde: %s", out.stderr[:200])
            return 0
        data = json.loads(out.stdout or "{}")
    except Exception as e:
        log.warning("count active jobs faalde: %s", e)
        return 0

    active = 0
    for j in data.get("items", []):
        conds = j.get("status", {}).get("conditions", [])
        done = any(
            c.get("type") in ("Complete", "Failed") and c.get("status") == "True"
            for c in conds
        )
        if not done:
            active += 1
    return active


def sanitize_id(issue_key: str) -> str:
    """K8s-name-safe versie van een JIRA-key (lowercase, kebab)."""
    s = issue_key.lower()
    s = re.sub(r"[^a-z0-9-]+", "-", s)
    s = re.sub(r"-+", "-", s).strip("-")
    return s[:30] or "job"


def resolve_model_effort(level: int, role: str) -> tuple[str, str]:
    """Lees agent-levels.yaml en geef (model, effort) voor (level, role).

    Bij ontbrekende file, ongeldige YAML, missende level of rol: geef
    een safe fallback (haiku quick = goedkoopste). Logged een warning
    zodat 't zichtbaar is.
    """
    try:
        with open(AGENT_LEVELS_PATH, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f) or {}
    except (OSError, yaml.YAMLError) as e:
        log.warning(
            "agent-levels.yaml onleesbaar (%s) — val terug op haiku/quick",
            e,
        )
        return ("claude-haiku-4-5", "quick")

    models = data.get("models") or {}
    levels = data.get("levels") or {}

    # YAML parsed int-keys; sommige tools maken er strings van.
    level_entry = levels.get(level) or levels.get(str(level))
    if not isinstance(level_entry, dict):
        log.warning("level %d onbekend in agent-levels.yaml — fallback", level)
        return ("claude-haiku-4-5", "quick")

    bucket = level_entry.get(role) or level_entry.get("developer")
    if not isinstance(bucket, str):
        log.warning("rol %r onbekend op level %d — fallback", role, level)
        return ("claude-haiku-4-5", "quick")

    spec = models.get(bucket) or {}
    model = spec.get("model") or "claude-haiku-4-5"
    effort = spec.get("effort") or "quick"
    return (model, effort)


def spawn_runner_job(
    issue_key: str,
    task_md: str,
    pr_number: Optional[int] = None,
    trigger_comment_id: Optional[int] = None,
    role: str = "developer",
    ai_level: Optional[int] = None,
) -> Optional[str]:
    """Maak ConfigMap + Job voor één issue. Returns job-name of None bij failure.

    Comment-mode (S-09): als pr_number én trigger_comment_id meegegeven worden,
    krijgt het Job die env-vars zodat de runner na z'n push een 'rocket'-reactie
    plaatst op het trigger-comment (op faal: 'confused').

    `role` + `ai_level` bepalen via de level-matrix welk model+effort
    de runner gebruikt. Default-level = DEFAULT_AI_LEVEL (Fase 2 PR 1)."""
    short = sanitize_id(issue_key)
    stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
    job_name = f"claude-run-{short}-{stamp}"
    cm_name = f"{job_name}-task"

    if ai_level is None:
        ai_level = DEFAULT_AI_LEVEL
    model, effort = resolve_model_effort(ai_level, role)

    # ConfigMap met task.md
    task_b64 = base64.b64encode(task_md.encode("utf-8")).decode("ascii")
    cm = {
        "apiVersion": "v1",
        "kind": "ConfigMap",
        "metadata": {"name": cm_name, "namespace": RUNNER_NAMESPACE},
        "binaryData": {"task.md": task_b64},
    }

    is_comment_mode = pr_number is not None
    labels = {
        "app": "claude-runner",
        "story-id": short,
        "mode": "comment" if is_comment_mode else "story",
    }
    if is_comment_mode:
        labels["pr-num"] = str(pr_number)

    env = [
        {"name": "STORY_ID", "value": issue_key},
        # Job-naam meegeven zodat de runner z'n usage-record correct kan
        # rapporteren aan de factory-DB (HOSTNAME is pod-naam, niet job-naam).
        {"name": "JOB_NAME", "value": job_name},
        # URL waar de runner z'n usage-record POST't aan het einde.
        # In-cluster service-DNS.
        {"name": "FACTORY_POLLER_URL", "value": "http://jira-poller.pnf-software-factory.svc.cluster.local:8080"},
        # Rol + level + resolved (model, effort). Runner gebruikt deze
        # voor de claude-CLI-aanroep; ze worden ook door factory-report.py
        # in het usage-record meegenomen voor latere analyse.
        {"name": "AGENT_ROLE", "value": role},
        {"name": "AI_LEVEL", "value": str(ai_level)},
        {"name": "CLAUDE_MODEL", "value": model},
        {"name": "CLAUDE_EFFORT", "value": effort},
        # JIRA custom-field-IDs zodat de runner z'n eigen phase-update
        # kan doen aan het einde (zonder zelf field-discovery te doen).
        {"name": "JIRA_FIELD_AI_PHASE", "value": _ai_field("phase") or ""},
        {"name": "JIRA_FIELD_AI_RESUME_PHASE", "value": _ai_field("resume_phase") or ""},
        {"name": "REPO_URL", "value": REPO_URL},
        {"name": "BASE_BRANCH", "value": "main"},
        {"name": "BRANCH_PREFIX", "value": "ai/"},
        # Format-string die de tester gebruikt om de live preview-URL
        # voor 'n PR af te leiden. {pr} wordt vervangen door het PR-nummer.
        {"name": "PREVIEW_URL_FORMAT", "value": PREVIEW_URL_FORMAT},
        # JIRA-info zodat runner z'n eigen status-transition + comments kan
        # plaatsen (S-07). In comment-mode is de issue al in REVIEW, dus de
        # transition no-op't; comments zijn idempotent.
        {"name": "JIRA_BASE_URL", "value": JIRA_BASE_URL},
        {"name": "JIRA_EMAIL", "value": JIRA_EMAIL},
        {"name": "JIRA_REVIEW_STATUS", "value": JIRA_REVIEW_STATUS},
        # Runner-auth via Claude Code OAuth-token (1 jaar geldig, gegenereerd
        # met `claude setup-token` op de laptop). Voordeel: usage gaat tegen
        # het Max-abonnementsquotum i.p.v. per-token API-billing. Belangrijk:
        # ANTHROPIC_API_KEY NIET óók meegeven — die zou de OAuth-route
        # overschrijven en je krijgt stilletjes weer API-billing.
        # De backend zelf gebruikt nog wél PNF_ANTHROPIC_API_KEY voor de
        # RSS-samenvattingen; dat is bewust, want backend-traffic zou anders
        # het 5-uurs-quotum met de runner delen.
        {
            "name": "CLAUDE_CODE_OAUTH_TOKEN",
            "valueFrom": {
                "secretKeyRef": {
                    "name": "newsfeed-api-keys",
                    "key": "CLAUDE_CODE_OAUTH_TOKEN",
                }
            },
        },
        {
            "name": "GITHUB_TOKEN",
            "valueFrom": {
                "secretKeyRef": {
                    "name": "newsfeed-api-keys",
                    "key": "GITHUB_TOKEN",
                }
            },
        },
        {
            "name": "JIRA_API_KEY",
            "valueFrom": {
                "secretKeyRef": {
                    "name": "newsfeed-api-keys",
                    "key": "ATLASSIAN_API_KEY",
                }
            },
        },
    ]
    if is_comment_mode:
        env.append({"name": "PR_NUMBER", "value": str(pr_number)})
        env.append({"name": "TRIGGER_COMMENT_ID", "value": str(trigger_comment_id)})

    # KAN-44: tester krijgt PREVIEW_DB_URL voor psql-queries. Hetzelfde
    # secret als de prod-app — preview-namespaces gebruiken dezelfde
    # Neon-DB met schema-isolation. Andere rollen krijgen 'm niet (zou
    # de developer verleiden tot DB-mutaties, wat verboden is).
    if role == "tester":
        env.append({
            "name": "PREVIEW_DB_URL",
            "valueFrom": {
                "secretKeyRef": {
                    "name": "newsfeed-api-keys",
                    "key": "PNF_DATABASE_URL",
                }
            },
        })
        # PR_NUMBER ook in story-mode (niet alleen comment-mode), zodat
        # de tester `pnf-pr-$PR_NUMBER` kan construeren voor oc-queries.
        if not is_comment_mode:
            # Voor story-mode is er nog geen PR; runner.sh berekent 't
            # zelf uit branch via github API. Geef alleen door als we
            # 'm hebben.
            pass

    job = {
        "apiVersion": "batch/v1",
        "kind": "Job",
        "metadata": {
            "name": job_name,
            "namespace": RUNNER_NAMESPACE,
            "labels": labels,
        },
        "spec": {
            # 2u TTL: lang genoeg om logs van een recent gefaalde Job nog te
            # bekijken, kort genoeg om de namespace niet te laten dichtslibben.
            "ttlSecondsAfterFinished": 7200,
            "backoffLimit": 0,
            "template": {
                "metadata": {"labels": labels},
                "spec": {
                    "restartPolicy": "Never",
                    # Tester draait onder een eigen SA met cluster-wide
                    # read; andere rollen blijven op de default-SA van
                    # pnf-software-factory (geen kubectl nodig).
                    **({"serviceAccountName": "claude-tester"}
                       if role == "tester" else {}),
                    "containers": [
                        {
                            "name": "runner",
                            # Tester gebruikt 't bredere image
                            # (Playwright + Chromium); andere rollen
                            # blijven op het lichte claude-runner image.
                            "image": CLAUDE_TESTER_IMAGE if role == "tester" else CLAUDE_RUNNER_IMAGE,
                            "imagePullPolicy": "Always",
                            "env": env,
                            "volumeMounts": [
                                {"name": "task", "mountPath": "/task", "readOnly": True}
                            ],
                            "resources": {
                                "requests": {"cpu": "250m", "memory": "512Mi"},
                                "limits": {"cpu": "2000m", "memory": "2Gi"},
                            },
                        }
                    ],
                    "volumes": [{"name": "task", "configMap": {"name": cm_name}}],
                },
            },
        },
    }

    # Apply: ConfigMap eerst (Pod-mount vereist 'm), dan Job (geeft UID
    # terug), tenslotte ConfigMap patchen met ownerReference naar de Job
    # zodat garbage-collection 'm meeruimt als de Job verdwijnt (anders
    # blijven CM's voor altijd hangen in de namespace).
    cm_out = kubectl(
        "apply", "-f", "-", input_data=json.dumps(cm), check=False
    )
    if cm_out.returncode != 0:
        log.error("kubectl apply CM faalde: %s", cm_out.stderr[:200])
        return None

    job_out = kubectl(
        "apply", "-f", "-", "-o", "json",
        input_data=json.dumps(job), check=False,
    )
    if job_out.returncode != 0:
        log.error("kubectl apply Job faalde: %s", job_out.stderr[:200])
        return None
    try:
        job_uid = json.loads(job_out.stdout)["metadata"]["uid"]
    except (json.JSONDecodeError, KeyError, TypeError) as e:
        log.warning("kon Job-UID niet uitlezen voor ownerRef op CM %s: %s", cm_name, e)
        job_uid = None

    if job_uid:
        # blockOwnerDeletion bewust uitgelaten: dat zou 'update finalizers'
        # op jobs vereisen (privilege escalation-check). We hoeven de Job-
        # delete NIET te blokkeren — we willen juist dat de CM mee-GC't.
        patch = {
            "metadata": {
                "ownerReferences": [{
                    "apiVersion": "batch/v1",
                    "kind": "Job",
                    "name": job_name,
                    "uid": job_uid,
                }]
            }
        }
        p_out = kubectl(
            "patch", "configmap", cm_name,
            "-n", RUNNER_NAMESPACE,
            "--type=merge",
            "-p", json.dumps(patch),
            check=False,
        )
        if p_out.returncode != 0:
            log.warning("ownerRef-patch op CM %s faalde: %s", cm_name, p_out.stderr[:200])

    log.info(
        "spawned Job %s voor %s (role=%s level=%d model=%s effort=%s mode=%s)",
        job_name, issue_key, role, ai_level, model, effort,
        "comment" if is_comment_mode else "story",
    )

    # Phase op JIRA zetten zodat het dashboard direct ziet dat de
    # bijbehorende agent draait. Mapping rol → active-phase.
    phase_for_role = {
        "refiner": "refining",
        "developer": "developing",
        "reviewer": "reviewing",
        "tester": "testing",
    }
    new_phase = phase_for_role.get(role)
    if new_phase:
        try:
            set_ai_fields(issue_key, {"phase": new_phase})
        except Exception as e:
            log.warning("kon AI Phase niet zetten op %s: %s", issue_key, e)

    return job_name


def count_active_jobs_for_pr(pr_num: int) -> int:
    """Tel actieve runner-jobs gekoppeld aan een specifieke PR."""
    try:
        out = kubectl(
            "get", "jobs",
            "-n", RUNNER_NAMESPACE,
            "-l", f"app=claude-runner,pr-num={pr_num}",
            "-o", "json",
            check=False,
        )
        if out.returncode != 0:
            return 0
        data = json.loads(out.stdout or "{}")
    except Exception:
        return 0
    active = 0
    for j in data.get("items", []):
        conds = j.get("status", {}).get("conditions", [])
        done = any(
            c.get("type") in ("Complete", "Failed") and c.get("status") == "True"
            for c in conds
        )
        if not done:
            active += 1
    return active


# ─── GitHub helpers (voor merge-check + S-09 comment-loop) ────────────────


def gh_request(
    method: str,
    path: str,
    json_body: Optional[dict] = None,
    params: Optional[dict] = None,
    timeout: int = 15,
) -> Optional[requests.Response]:
    """Wrap een GitHub REST API call met auth + base URL."""
    if not (GITHUB_TOKEN and GITHUB_OWNER and GITHUB_REPO):
        return None
    url = f"https://api.github.com{path}"
    headers = {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {GITHUB_TOKEN}",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    try:
        return requests.request(
            method, url, headers=headers, json=json_body, params=params, timeout=timeout
        )
    except requests.RequestException as e:
        log.warning("GH API %s %s faalde: %s", method, path, e)
        return None


def github_pr_for_branch(branch: str) -> Optional[dict]:
    """Vind een PR (open of closed) waarvan de head-branch overeenkomt."""
    r = gh_request(
        "GET",
        f"/repos/{GITHUB_OWNER}/{GITHUB_REPO}/pulls",
        params={"state": "all", "head": f"{GITHUB_OWNER}:{branch}", "per_page": "1"},
    )
    if not r or r.status_code != 200:
        if r is not None:
            log.warning("GH PR-lookup %s -> %s", branch, r.status_code)
        return None
    prs = r.json()
    return prs[0] if prs else None


def gh_list_open_prs() -> list[dict]:
    """Open PR's in de repo."""
    r = gh_request(
        "GET",
        f"/repos/{GITHUB_OWNER}/{GITHUB_REPO}/pulls",
        params={"state": "open", "per_page": "30"},
    )
    if not r or r.status_code != 200:
        return []
    return r.json()


def gh_list_issue_comments(issue_num: int) -> list[dict]:
    """Issue/PR-comments (niet review-comments). Ascending op created_at."""
    r = gh_request(
        "GET",
        f"/repos/{GITHUB_OWNER}/{GITHUB_REPO}/issues/{issue_num}/comments",
        params={"per_page": "100"},
    )
    if not r or r.status_code != 200:
        return []
    return r.json()


def gh_add_comment_reaction(comment_id: int, content: str) -> bool:
    """Plaats een reactie. content: +1, -1, laugh, confused, heart, hooray, rocket, eyes."""
    r = gh_request(
        "POST",
        f"/repos/{GITHUB_OWNER}/{GITHUB_REPO}/issues/comments/{comment_id}/reactions",
        json_body={"content": content},
    )
    return r is not None and r.status_code in (200, 201)


def comment_has_reaction(comment: dict, content: str) -> bool:
    """
    Check via de embedded `reactions`-rollup of een comment de gegeven
    reactie heeft. GitHub geeft per comment een telling per emoji terug,
    dus dit kost geen extra API-call.
    """
    return (comment.get("reactions") or {}).get(content, 0) > 0


# ─── JIRA-comment helpers ─────────────────────────────────────────────────


def adf_text(text: str) -> dict:
    """Plain-text ADF text-node."""
    return {"type": "text", "text": text}


def adf_link(text: str, href: str) -> dict:
    """Klikbare-link ADF text-node."""
    return {
        "type": "text",
        "text": text,
        "marks": [{"type": "link", "attrs": {"href": href}}],
    }


def adf_paragraph(*nodes) -> dict:
    """Paragraaf met de gegeven inline-nodes."""
    return {"type": "paragraph", "content": list(nodes)}


def jira_post_adf_comment(issue_key: str, paragraphs: list[dict]) -> bool:
    """Post een ADF-comment met een lijst paragrafen. Niet idempotent."""
    body = {"body": {"type": "doc", "version": 1, "content": paragraphs}}
    r = jira(
        "POST",
        f"/rest/api/3/issue/{issue_key}/comment",
        json=body,
        headers={"Content-Type": "application/json"},
    )
    return r.status_code in (200, 201)


def jira_post_comment(issue_key: str, text: str) -> bool:
    """Legacy helper voor eenvoudige plain-text comments."""
    return jira_post_adf_comment(issue_key, [adf_paragraph(adf_text(text))])


def jira_has_comment_containing(issue_key: str, needle: str) -> bool:
    """Check of een issue al een comment heeft met `needle` in de body."""
    r = jira(
        "GET",
        f"/rest/api/3/issue/{issue_key}/comment",
        params={"maxResults": "100"},
    )
    if r.status_code != 200:
        return False
    for c in r.json().get("comments", []):
        # ADF naar string flatten
        if needle in adf_to_markdown(c.get("body")):
            return True
    return False


# ─── PR-comment iteratieloop (S-09) ───────────────────────────────────────


def find_pending_comment_triggers() -> list[dict]:
    """
    Voor elke open ai/-PR: zoek nieuwe @claude-trigger-comments.

    Een trigger is een comment dat de trigger-substring bevat én nog GEEN
    'eyes'-reactie heeft. We bouwen een context-pakket op van alle comments
    sinds de laatste 'rocket'-gemarkeerde trigger (= laatste verwerkte
    iteratie) tot en met de nieuwe trigger; dat wordt Claude's task.
    """
    triggers: list[dict] = []
    for pr in gh_list_open_prs():
        pr_num = pr.get("number")
        branch = pr.get("head", {}).get("ref", "")
        # Alleen ai/-branches doen mee aan de comment-loop. Mens-branches
        # (feat/, fix/, …) blijven onaangeroerd.
        if not branch.startswith("ai/"):
            continue
        story_id = branch[len("ai/"):]
        # Story-id moet JIRA-format hebben (b.v. KAN-8) — anders kunnen we
        # downstream niet correct met JIRA praten en is iteratie zinloos.
        if not re.match(r"^[A-Z][A-Z0-9]+-[0-9]+$", story_id):
            continue

        comments = gh_list_issue_comments(pr_num)

        # Waterlijn voor context-bouw: index van het laatste comment met een
        # 'rocket'-reactie (= laatste iteratie die succesvol gepushed heeft).
        # Comments daarvoor zijn al verwerkt; context begint daarna.
        last_rocket_idx = -1
        for i, c in enumerate(comments):
            if comment_has_reaction(c, "rocket"):
                last_rocket_idx = i

        # Eerste bruikbare trigger zoeken na last_rocket.
        #
        # Drie soorten triggers onderweg:
        #   • alive  — heeft eyes maar nog geen rocket/confused → loopt nu;
        #              wacht met latere triggers tot deze klaar is.
        #   • dead   — heeft eyes + confused → gefaald; sla over.
        #   • fresh  — geen eyes → pak op (claim + spawn).
        trigger_idx = -1
        i = last_rocket_idx + 1
        while i < len(comments):
            c = comments[i]
            body = (c.get("body") or "").lower()
            if COMMENT_TRIGGER not in body:
                i += 1
                continue
            has_eyes = comment_has_reaction(c, "eyes")
            has_confused = comment_has_reaction(c, "confused")
            has_rocket = comment_has_reaction(c, "rocket")
            if has_eyes and not (has_confused or has_rocket):
                # Alive — blokkeer; wacht volgende poll.
                trigger_idx = -1
                break
            if has_eyes:
                # Dead (eyes+confused) of al-rocket'd-maar-nog-niet-watermark
                # → sla over en kijk verder.
                i += 1
                continue
            trigger_idx = i
            break

        if trigger_idx < 0:
            continue

        context_comments = comments[last_rocket_idx + 1 : trigger_idx + 1]
        triggers.append({
            "pr_number": pr_num,
            "pr_title": pr.get("title", ""),
            "pr_body": pr.get("body") or "",
            "branch": branch,
            "story_id": story_id,
            "trigger_comment_id": comments[trigger_idx]["id"],
            "context_comments": context_comments,
        })
    return triggers


def build_comment_task_md(trigger: dict) -> str:
    """Bouw een task.md voor de runner uit de feedback-comments op een PR."""
    out: list[str] = []
    out.append(
        f"# Feedback op PR #{trigger['pr_number']} — branch `{trigger['branch']}`\n\n"
    )
    out.append(
        "Je hebt eerder een implementatie gemaakt op deze branch. De "
        "reviewer heeft commentaar geplaatst op de PR; verwerk dat "
        "commentaar in nieuwe commits op dezelfde branch. Verander niets "
        "dat niet expliciet gevraagd wordt.\n\n"
    )

    if trigger.get("pr_body"):
        out.append("## Originele PR-beschrijving\n\n")
        out.append(trigger["pr_body"].strip() + "\n\n")

    out.append("## Commentaar van de reviewer (chronologisch)\n\n")
    for c in trigger["context_comments"]:
        author = c.get("user", {}).get("login", "unknown")
        ts = c.get("created_at", "")
        body = (c.get("body") or "").strip()
        out.append(f"### {author} — {ts}\n\n{body}\n\n")

    out.append(
        "De laatste comment hierboven is de trigger-comment (bevat "
        f"`{COMMENT_TRIGGER}`). Behandel alle bovenstaande comments als "
        "één geheel aan feedback. Commit elke logische verandering apart, "
        f"met commit-msg-format `{trigger['story_id']}: <korte beschrijving>`.\n"
    )
    return "".join(out)


def process_pr_comments() -> None:
    """Pak nieuwe @claude-comments op ai/-PR's op (S-09)."""
    if not (GITHUB_TOKEN and GITHUB_OWNER and GITHUB_REPO):
        return
    triggers = find_pending_comment_triggers()
    if not triggers:
        return
    log.info("found %d pending PR-comment trigger(s)", len(triggers))

    active = count_active_runner_jobs()
    for t in triggers:
        if active >= MAX_CONCURRENT_JOBS:
            log.info("capacity bereikt — comment-trigger(s) wachten op volgende poll")
            return
        # Geen twee runners tegelijk op dezelfde PR.
        if count_active_jobs_for_pr(t["pr_number"]) > 0:
            log.info(
                "PR #%d heeft al een actieve runner — skip deze trigger",
                t["pr_number"],
            )
            continue
        # Claim de trigger met een 'eyes'-reactie — dit zorgt ook voor
        # idempotentie als de volgende poll start vóór de Job klaar is.
        if not gh_add_comment_reaction(t["trigger_comment_id"], "eyes"):
            log.warning(
                "kon 'eyes'-reactie niet plaatsen op comment %d — skip",
                t["trigger_comment_id"],
            )
            continue
        log.info(
            "opgepakt: PR #%d branch=%s trigger=%d",
            t["pr_number"],
            t["branch"],
            t["trigger_comment_id"],
        )
        # JIRA: flip terug naar AI IN PROGRESS zodat de "AI bezig"-sectie
        # op het dashboard de iteratie laat zien. De runner's end-transition
        # zet 'm bij succes weer naar AI IN REVIEW; bij faal blijft 'ie op
        # AI IN PROGRESS staan (= zichtbaar als vastlopend werk).
        if not transition_issue(t["story_id"], JIRA_TARGET_STATUS):
            log.warning(
                "kon %s niet naar %s flippen — runner draait wel door",
                t["story_id"], JIRA_TARGET_STATUS,
            )
        task_md = build_comment_task_md(t)
        job_name = spawn_runner_job(
            issue_key=t["story_id"],
            task_md=task_md,
            pr_number=t["pr_number"],
            trigger_comment_id=t["trigger_comment_id"],
        )
        if not job_name:
            log.error("spawn faalde voor PR #%d", t["pr_number"])
            continue
        active += 1


# ─── Merge-check (AI IN REVIEW → Klaar) ───────────────────────────────────


# ─── @claude:command:* — handmatige acties via JIRA-comments ──────────
#
# PO kan vanuit een story-comment een actie triggeren door
# '@claude:command:<cmd>' (gevolgd door spatie of einde-tekst) te
# plaatsen. Vier commando's:
#   delete       : kill jobs, sluit PR + branch, delete pnf-pr-namespace,
#                  prepend '(CANCELLED) ' aan title, status → Gereed
#   merge        : merge PR squash, kill jobs, delete namespace
#                  (status volgt via bestaande merge-detect)
#   pause        : kill jobs, status → AI Paused
#   re-implement : kill jobs, sluit PR + branch, delete namespace,
#                  delete bot-comments, status → JIRA_TODO_STATUS
#                  (default 'Nog doen' voor NL workflows)
#
# Idempotency: na uitvoering appenden we '_[factory] commando uitgevoerd_'
# aan de comment-body. Comments met die marker worden geskipt.

CMD_PATTERN = re.compile(r"@claude:command:([\w-]+)(?:\s|$)")
CMD_DONE_MARKER = "_[factory] commando uitgevoerd_"
VALID_COMMANDS = {"delete", "merge", "pause", "re-implement"}

# Budget-triggers (KAN-40). Geplaatst door de PO in een JIRA-comment om
# een gepauzeerde-op-budget story te hervatten. Match aan begin van een
# regel zodat platte tekst zoals 'we hebben een nieuw BUDGET=5k afgesproken'
# niet per ongeluk triggert.
BUDGET_SET_PATTERN = re.compile(r"^\s*BUDGET\s*=\s*(\d+)\s*$", re.MULTILINE)
BUDGET_CONTINUE_PATTERN = re.compile(r"^\s*CONTINUE\s*$",
                                     re.MULTILINE | re.IGNORECASE)
BOT_COMMENT_PATTERNS = (
    re.compile(r"^\s*\[(REFINER|DEVELOPER|REVIEWER|TESTER)\]"),
    re.compile(r"^\s*🤖"),
    re.compile(r"^\s*✅"),
    re.compile(r"^\s*📸"),
)


def _is_bot_comment(text: str) -> bool:
    head = text.strip()
    return any(p.match(head) for p in BOT_COMMENT_PATTERNS)


def kill_runner_jobs_for_story(story_key: str) -> int:
    """Hard-kill alle nog-lopende runner-Jobs voor een story.
    Returns aantal verwijderde Jobs (best-effort)."""
    short = sanitize_id(story_key)
    log.info("[cmd] kill_jobs %s — label app=claude-runner,story-id=%s", story_key, short)
    out = kubectl(
        "delete", "jobs",
        "-n", RUNNER_NAMESPACE,
        "-l", f"app=claude-runner,story-id={short}",
        "--ignore-not-found=true",
        check=False,
    )
    killed = out.stdout.count("deleted") if out.stdout else 0
    log.info("[cmd] kill_jobs %s → %d jobs verwijderd; stdout=%r stderr=%r",
             story_key, killed, (out.stdout or "")[:200], (out.stderr or "")[:200])
    return killed


def find_preview_namespaces_for_story(story_key: str) -> list[str]:
    """Vind alle pnf-pr-*-namespaces die mogelijk bij deze story horen.

    Strategie: zoek alle PR's (open + closed) voor branch ai/<key> via
    de GH-API. Elke PR-nummer mapped naar pnf-pr-<num>. Returnt lijst
    namespaces (per geconstrueerde naam) — niet alle hoeven te bestaan.
    """
    branch = f"ai/{story_key}"
    r = gh_request(
        "GET",
        f"/repos/{GITHUB_OWNER}/{GITHUB_REPO}/pulls",
        params={"state": "all", "head": f"{GITHUB_OWNER}:{branch}", "per_page": "10"},
    )
    if not r or r.status_code != 200:
        return []
    prs = r.json()
    return [f"pnf-pr-{p['number']}" for p in prs if p.get("number")]


def close_pr_for_story(story_key: str) -> Optional[int]:
    """Sluit de PR voor ai/<key> + verwijder branch (best-effort).
    Returns PR-number of None (geen PR / al gesloten)."""
    branch = f"ai/{story_key}"
    pr = github_pr_for_branch(branch)
    if not pr:
        return None
    num = pr["number"]
    if pr.get("state") == "closed":
        return num
    r = gh_request(
        "PATCH",
        f"/repos/{GITHUB_OWNER}/{GITHUB_REPO}/pulls/{num}",
        json_body={"state": "closed"},
    )
    if r and r.status_code in (200, 201):
        # Branch ook verwijderen (best-effort).
        gh_request(
            "DELETE",
            f"/repos/{GITHUB_OWNER}/{GITHUB_REPO}/git/refs/heads/{branch}",
        )
    return num


def merge_pr_for_story(story_key: str) -> Optional[int]:
    """Squash-merge de PR voor ai/<key> + verwijder branch. Returns
    PR-number of None bij faal/no-PR."""
    branch = f"ai/{story_key}"
    pr = github_pr_for_branch(branch)
    if not pr:
        return None
    num = pr["number"]
    if pr.get("merged_at"):
        return num  # idempotent
    r = gh_request(
        "PUT",
        f"/repos/{GITHUB_OWNER}/{GITHUB_REPO}/pulls/{num}/merge",
        json_body={"merge_method": "squash"},
    )
    if not r or r.status_code not in (200,):
        log.warning("merge #%d faalde: %s", num, getattr(r, "status_code", "no-resp"))
        return None
    gh_request(
        "DELETE",
        f"/repos/{GITHUB_OWNER}/{GITHUB_REPO}/git/refs/heads/{branch}",
    )
    return num


def delete_preview_namespace(pr_number: int) -> bool:
    """oc delete namespace pnf-pr-N. Returnt True als de namespace
    bestond + delete-call geslaagd is."""
    ns = f"pnf-pr-{pr_number}"
    return _delete_namespace_by_name(ns)


def _delete_namespace_by_name(ns: str) -> bool:
    log.info("[cmd] delete_namespace %s", ns)
    # Check eerst of de namespace bestaat — anders is "--ignore-not-found"
    # stilletjes klaar zonder dat we weten of er iets is opgeruimd.
    check = kubectl("get", "namespace", ns, "--ignore-not-found=true",
                    "-o", "name", check=False)
    if not (check.stdout or "").strip():
        log.info("[cmd] delete_namespace %s — bestond niet (geen actie)", ns)
        return False
    out = kubectl("delete", "namespace", ns, "--wait=false", check=False)
    ok = out.returncode == 0
    log.info("[cmd] delete_namespace %s → rc=%d stdout=%r stderr=%r",
             ns, out.returncode, (out.stdout or "")[:200], (out.stderr or "")[:200])
    return ok


def prepend_cancelled_to_title(issue_key: str) -> None:
    """Voeg '(CANCELLED) '-prefix toe aan de issue-titel (idempotent)."""
    r = jira("GET", f"/rest/api/3/issue/{issue_key}",
             params={"fields": "summary"})
    if r.status_code != 200:
        return
    current = (r.json().get("fields") or {}).get("summary") or ""
    if current.startswith("(CANCELLED) "):
        return
    jira(
        "PUT",
        f"/rest/api/3/issue/{issue_key}",
        json={"fields": {"summary": "(CANCELLED) " + current}},
        headers={"Content-Type": "application/json"},
    )


def delete_bot_comments(issue_key: str) -> int:
    """Verwijder alle bot-comments (refiner/developer/reviewer/tester +
    🤖/✅/📸). Returns aantal verwijderd."""
    comments = fetch_jira_comments(issue_key, limit=100)
    deleted = 0
    for c in comments:
        if not _is_bot_comment(c.get("text", "")):
            continue
        cid = c.get("id")
        if not cid:
            continue
        r = jira("DELETE", f"/rest/api/3/issue/{issue_key}/comment/{cid}")
        if r.status_code in (200, 204):
            deleted += 1
    return deleted


def mark_command_handled(issue_key: str, comment_id: str, body: str) -> None:
    """Append marker aan comment-body via PUT zodat we niet 2× verwerken."""
    new_body = body.rstrip() + f"\n\n{CMD_DONE_MARKER}"
    r = jira(
        "PUT",
        f"/rest/api/3/issue/{issue_key}/comment/{comment_id}",
        json={"body": {
            "type": "doc",
            "version": 1,
            "content": [adf_paragraph(adf_text(new_body))],
        }},
        headers={"Content-Type": "application/json"},
    )
    if r.status_code not in (200, 201):
        log.warning("kon comment %s niet markeren: %s", comment_id, r.status_code)


def execute_story_command(issue_key: str, command: str) -> str:
    """Voer één commando uit. Returnt korte resultaat-string voor de log."""
    log.info("[cmd] %s: %s", issue_key, command)

    if command == "pause":
        killed = kill_runner_jobs_for_story(issue_key)
        ok = transition_issue(issue_key, "AI Paused")
        log.info("[cmd] pause %s: transition → %s", issue_key, ok)
        status_part = "status → AI Paused" if ok else (
            "status NIET veranderd — geen transitie naar 'AI Paused' beschikbaar"
        )
        return f"jobs={killed}, {status_part}"

    if command == "delete":
        killed = kill_runner_jobs_for_story(issue_key)
        pr_num = close_pr_for_story(issue_key)
        log.info("[cmd] delete %s: close_pr → %s", issue_key, pr_num)
        # Ruim alle namespaces voor deze story op (ook als pr_num None is).
        all_ns = find_preview_namespaces_for_story(issue_key)
        if pr_num and f"pnf-pr-{pr_num}" not in all_ns:
            all_ns.append(f"pnf-pr-{pr_num}")
        ns_deleted = sum(1 for ns in all_ns if _delete_namespace_by_name(ns))
        prepend_cancelled_to_title(issue_key)
        jira_post_comment(issue_key, "canceled by user")
        ok = transition_issue(issue_key, JIRA_DONE_STATUS)
        log.info("[cmd] delete %s: transition → %s", issue_key, ok)
        status_part = (
            f"status → {JIRA_DONE_STATUS}" if ok
            else f"status NIET veranderd — geen transitie naar '{JIRA_DONE_STATUS}'"
        )
        return (
            f"jobs={killed}, pr={pr_num or '-'}, "
            f"namespaces gewist={ns_deleted}/{len(all_ns)}, "
            f"title prefix, {status_part}"
        )

    if command == "merge":
        # Merge eerst (mocht 't falen, willen we niet alvast jobs killen +
        # namespace deleten en met half resultaat eindigen).
        pr_num = merge_pr_for_story(issue_key)
        log.info("[cmd] merge %s: merge_pr → %s", issue_key, pr_num)
        if pr_num is None:
            return "merge faalde / geen PR — geen verdere actie"
        killed = kill_runner_jobs_for_story(issue_key)
        all_ns = find_preview_namespaces_for_story(issue_key)
        if f"pnf-pr-{pr_num}" not in all_ns:
            all_ns.append(f"pnf-pr-{pr_num}")
        ns_deleted = sum(1 for ns in all_ns if _delete_namespace_by_name(ns))
        return (
            f"pr #{pr_num} merged squash, jobs={killed}, "
            f"namespaces gewist={ns_deleted}/{len(all_ns)} "
            "(status volgt via merge-detect)"
        )

    if command == "re-implement":
        log.info("[cmd] re-implement %s: start", issue_key)
        killed = kill_runner_jobs_for_story(issue_key)

        # Sluit ÉÉN PR (via huidige helper) — kan None geven als geen PR
        # of als hij niet meer matched.
        pr_num = close_pr_for_story(issue_key)
        log.info("[cmd] re-implement %s: close_pr → %s", issue_key, pr_num)

        # Zoek álle pnf-pr-*-namespaces die ooit bij deze story
        # hoorden (op basis van álle PR's voor ai/<key>) en delete ze.
        # Werkt ook als de PR al gesloten/gemerged is en close_pr_for_story
        # niets vond.
        all_ns = find_preview_namespaces_for_story(issue_key)
        if pr_num and f"pnf-pr-{pr_num}" not in all_ns:
            all_ns.append(f"pnf-pr-{pr_num}")
        ns_deleted = 0
        for ns in all_ns:
            if _delete_namespace_by_name(ns):
                ns_deleted += 1
        log.info("[cmd] re-implement %s: %d/%d namespaces verwijderd",
                 issue_key, ns_deleted, len(all_ns))

        deleted_comments = delete_bot_comments(issue_key)
        log.info("[cmd] re-implement %s: %d bot-comments verwijderd",
                 issue_key, deleted_comments)

        transition_ok = transition_issue(issue_key, JIRA_TODO_STATUS)
        log.info("[cmd] re-implement %s: transition to '%s' → %s",
                 issue_key, JIRA_TODO_STATUS, transition_ok)
        status_part = f"status → {JIRA_TODO_STATUS}" if transition_ok else (
            f"status NIET veranderd — geen transitie naar '{JIRA_TODO_STATUS}' "
            "beschikbaar vanuit huidige status (controleer JIRA-workflow)"
        )
        return (
            f"jobs={killed}, pr={pr_num or '-'}, "
            f"namespaces gewist={ns_deleted}/{len(all_ns)}, "
            f"bot-comments verwijderd={deleted_comments}, {status_part}"
        )

    return f"onbekend commando: {command}"


def execute_budget_command(issue_key: str, comment_text: str) -> Optional[str]:
    """Verwerk BUDGET=N / CONTINUE-commands uit een JIRA-comment. Returnt
    None als geen budget-pattern matcht, anders een korte resultaat-
    string voor de bevestigingscomment.

    Werkt alleen op stories met phase=awaiting-po — andere stories
    krijgen 'genegeerd: niet gepauzeerd op budget' terug zodat de PO
    ziet dat de comment is opgemerkt maar niet relevant is.
    """
    m_set = BUDGET_SET_PATTERN.search(comment_text)
    m_cont = BUDGET_CONTINUE_PATTERN.search(comment_text)
    if not m_set and not m_cont:
        return None

    # Eerst de huidige issue-state ophalen. We hebben phase + budget nodig.
    r = jira("GET", f"/rest/api/3/issue/{issue_key}",
             params={"fields": _ai_fields_param()})
    if r.status_code != 200:
        return f"FOUT: kon issue niet ophalen ({r.status_code})"
    issue = r.json()
    ai = get_ai_fields(issue)
    phase = (ai.get("phase") or "").strip()
    if phase != "awaiting-po":
        return f"genegeerd: phase={phase or '?'} (alleen awaiting-po)"

    current_budget = ai.get("token_budget")
    if current_budget is None or current_budget <= 0:
        current_budget = DEFAULT_TOKEN_BUDGET

    if m_set:
        new_budget = int(m_set.group(1))
        if new_budget <= 0:
            return f"FOUT: ongeldige BUDGET={m_set.group(1)}"
        action = f"BUDGET={new_budget}"
    else:
        # CONTINUE = +50%. Rond af op duizendtallen voor leesbaarheid.
        new_budget = int(current_budget * 1.5)
        action = f"CONTINUE (+50% → {new_budget})"

    set_ai_fields(issue_key, {"token_budget": new_budget})
    ok = transition_issue(issue_key, "AI Queued")
    if not ok:
        return (f"FOUT: budget gezet op {new_budget} maar transitie naar "
                "AI Queued faalde")
    return f"{action}, status → AI Queued (was {current_budget}, nu {new_budget})"


def process_story_commands() -> None:
    """Scan alle stories op @claude:command-comments en voer uit.

    Idempotent: comments met CMD_DONE_MARKER worden geskipt. Een
    bevestigings-comment '[factory] commando uitgevoerd: <result>' wordt
    naast de marker geplakt zodat de PO de bevestiging ziet.
    """
    # Welke statussen scannen we? Alles wat niet 'Gereed' is — een
    # gemergede story kan nog een laat commando hebben maar dat is
    # zeldzaam; voor v1 scannen we alleen actieve issues.
    statuses = ",".join(
        f'"{s}"'
        for s in (
            "AI Ready", "AI Queued", "AI IN PROGRESS",
            "AI IN REVIEW", "AI Needs Info", "AI Paused",
            JIRA_TODO_STATUS,
        )
    )
    jql = f"project={JIRA_PROJECT} AND status in ({statuses})"
    r = jira(
        "GET", "/rest/api/3/search/jql",
        params={"jql": jql, "fields": "summary", "maxResults": "100"},
    )
    if r.status_code != 200:
        return
    for issue in r.json().get("issues", []):
        key = issue["key"]
        try:
            comments = fetch_jira_comments(key, limit=100)
        except Exception as e:
            log.warning("comment-fetch %s faalde: %s", key, e)
            continue
        for c in comments:
            text = c.get("text", "")
            if CMD_DONE_MARKER in text:
                continue
            # 1) Bestaande @claude:command:*-pattern
            m = CMD_PATTERN.search(text)
            if m:
                cmd = m.group(1).lower()
                if cmd not in VALID_COMMANDS:
                    continue
                try:
                    result = execute_story_command(key, cmd)
                except Exception as e:
                    log.exception("commando %s op %s faalde: %s", cmd, key, e)
                    result = f"FOUT: {e}"
                mark_command_handled(key, c["id"], text)
                try:
                    jira_post_comment(
                        key, f"[factory] commando '{cmd}' uitgevoerd: {result}"
                    )
                except Exception:
                    pass
                continue
            # 2) Budget-triggers (KAN-40) — BUDGET=N / CONTINUE op een
            # gepauzeerde-op-budget story.
            try:
                budget_result = execute_budget_command(key, text)
            except Exception as e:
                log.exception("budget-cmd op %s faalde: %s", key, e)
                budget_result = f"FOUT: {e}"
            if budget_result is None:
                continue
            mark_command_handled(key, c["id"], text)
            try:
                jira_post_comment(
                    key, f"[factory] budget-commando uitgevoerd: {budget_result}"
                )
            except Exception:
                pass


def check_review_for_merges() -> None:
    """
    Loop over alle issues in `JIRA_REVIEW_STATUS`. Voor elk:
      - Zoek de bijbehorende PR (branch ai/<key>).
      - Als gemerged: transition naar `JIRA_DONE_STATUS` + comment.
    """
    if not GITHUB_TOKEN:
        return  # geen GH-toegang → kan merge niet detecteren

    jql = (
        f'project={JIRA_PROJECT} AND status="{JIRA_REVIEW_STATUS}" '
        f"ORDER BY updated DESC"
    )
    r = jira(
        "GET",
        "/rest/api/3/search/jql",
        params={"jql": jql, "fields": "summary,status"},
    )
    if r.status_code != 200:
        log.warning("review-search faalde: %s %s", r.status_code, r.text[:200])
        return

    for issue in r.json().get("issues", []):
        key = issue["key"]
        branch = f"ai/{key}"
        pr = github_pr_for_branch(branch)
        if not pr:
            continue
        if not pr.get("merged_at"):
            # PR bestaat, maar niet gemerged. Wacht.
            continue
        # Gemerged — markeer als Klaar (alleen als dat nog niet is gebeurd)
        marker = f"merge-marker-PR-{pr['number']}"
        if jira_has_comment_containing(key, marker):
            continue

        log.info("%s: PR #%d is gemerged → %s", key, pr["number"], JIRA_DONE_STATUS)
        if transition_issue(key, JIRA_DONE_STATUS):
            pr_url = pr.get("html_url", "")
            jira_post_adf_comment(
                key,
                [
                    adf_paragraph(
                        adf_text("✅ Gemerged via "),
                        adf_link(f"PR #{pr['number']}", pr_url),
                        adf_text(" naar main."),
                    ),
                    adf_paragraph(adf_text(f"[{marker}]")),
                ],
            )
        else:
            log.warning("transition naar %s faalde voor %s", JIRA_DONE_STATUS, key)


# ─── main loop ────────────────────────────────────────────────────────────


def process_one_pass() -> None:
    # Allereerst: handmatige @claude:command:*-acties uit JIRA-comments.
    # Doen we vóór alle andere stappen zodat een 'pause' of 'delete'
    # niet daarna nog een spawn triggert in dezelfde tick.
    try:
        process_story_commands()
    except Exception as e:
        log.exception("command-processor faalde: %s", e)

    # AI IN REVIEW → kijken of er gemergede PR's zijn (cheap call).
    try:
        check_review_for_merges()
    except Exception as e:
        log.exception("merge-check faalde: %s", e)

    # S-09: PR-comment iteratieloop — @claude-triggers oppakken.
    try:
        process_pr_comments()
    except Exception as e:
        log.exception("pr-comment loop faalde: %s", e)

    # Capacity-check is globaal (alle rollen samen tellen mee tegen
    # MAX_CONCURRENT_JOBS). Wordt later per-rol als de matrix groeit.
    active = count_active_runner_jobs()

    # ── Stap 1: AI Ready → refiner ───────────────────────────────────
    ready = fetch_ai_ready_issues()
    if ready:
        log.info(
            "  %d %r issue(s) klaar voor refiner; active=%d/%d",
            len(ready), JIRA_SOURCE_STATUS, active, MAX_CONCURRENT_JOBS,
        )
    for issue in ready:
        if active >= MAX_CONCURRENT_JOBS:
            log.info("  capacity bereikt — rest wacht op volgende poll")
            return
        if _claim_and_spawn(issue, role="refiner"):
            active += 1

    # ── Stap 2: AI Queued + phase=refined → developer ────────────────
    queued_for_dev = fetch_queued_with_phase("refined")
    if queued_for_dev:
        log.info(
            "  %d issue(s) klaar voor developer (phase=refined); active=%d/%d",
            len(queued_for_dev), active, MAX_CONCURRENT_JOBS,
        )
    for issue in queued_for_dev:
        if active >= MAX_CONCURRENT_JOBS:
            log.info("  capacity bereikt — rest wacht op volgende poll")
            return
        if _claim_and_spawn(issue, role="developer"):
            active += 1

    # ── Stap 2b: AI IN REVIEW + phase=developed → reviewer ──────────
    # Na een succesvolle developer-run staat de story op AI IN REVIEW
    # met phase=developed. Pak 'm direct op voor code-review. Status
    # blijft AI IN REVIEW (PR is open). Reviewer doet géén code-changes
    # — alleen JIRA-comment + phase=reviewed-ok of reviewed-changes.
    in_review_developed = fetch_in_review_with_phase("developed")
    if in_review_developed:
        log.info(
            "  %d issue(s) klaar voor reviewer (phase=developed); active=%d/%d",
            len(in_review_developed), active, MAX_CONCURRENT_JOBS,
        )
    for issue in in_review_developed:
        if active >= MAX_CONCURRENT_JOBS:
            log.info("  capacity bereikt — rest wacht op volgende poll")
            return
        if _claim_and_spawn(issue, role="reviewer", target_status=JIRA_REVIEW_STATUS):
            active += 1

    # ── Stap 2c: AI IN REVIEW + phase=reviewed-changes → developer ───
    # Reviewer-loopback. Developer leest de comment-thread (incl. de
    # [REVIEWER]-bevindingen) uit task.md, pakt de bestaande branch op
    # en pusht een nieuwe commit. PR-update is idempotent: gh pr view
    # vóór create. Na de developer-run staat 'ie weer op phase=developed
    # en pakt Stap 2b 'm op voor een tweede review-ronde.
    in_review_changes = fetch_in_review_with_phase("reviewed-changes")
    if in_review_changes:
        log.info(
            "  %d issue(s) reviewer-loopback (phase=reviewed-changes); active=%d/%d",
            len(in_review_changes), active, MAX_CONCURRENT_JOBS,
        )
    for issue in in_review_changes:
        if active >= MAX_CONCURRENT_JOBS:
            log.info("  capacity bereikt — rest wacht op volgende poll")
            return
        if _claim_and_spawn(issue, role="developer", target_status=JIRA_REVIEW_STATUS):
            active += 1

    # ── Stap 2d: AI IN REVIEW + phase=reviewed-ok → tester ──────────
    # Code-review is klaar; tijd voor de tester-agent (Fase 5 MVP).
    # Tester gebruikt curl + AI-judgment op de live preview-deploy.
    in_review_reviewed = fetch_in_review_with_phase("reviewed-ok")
    if in_review_reviewed:
        log.info(
            "  %d issue(s) klaar voor tester (phase=reviewed-ok); active=%d/%d",
            len(in_review_reviewed), active, MAX_CONCURRENT_JOBS,
        )
    for issue in in_review_reviewed:
        if active >= MAX_CONCURRENT_JOBS:
            log.info("  capacity bereikt — rest wacht op volgende poll")
            return
        if _claim_and_spawn(issue, role="tester", target_status=JIRA_REVIEW_STATUS):
            active += 1

    # ── Stap 2e: AI IN REVIEW + phase=tested-fail → developer ────────
    # Tester-loopback: developer ziet de [TESTER]-bevindingen in de
    # comment-thread en pakt 'm aan. Symmetrisch met Stap 2c.
    in_review_test_fail = fetch_in_review_with_phase("tested-fail")
    if in_review_test_fail:
        log.info(
            "  %d issue(s) tester-loopback (phase=tested-fail); active=%d/%d",
            len(in_review_test_fail), active, MAX_CONCURRENT_JOBS,
        )
    for issue in in_review_test_fail:
        if active >= MAX_CONCURRENT_JOBS:
            log.info("  capacity bereikt — rest wacht op volgende poll")
            return
        if _claim_and_spawn(issue, role="developer", target_status=JIRA_REVIEW_STATUS):
            active += 1

    # ── Stap 3: AI Queued + phase=awaiting-po → resume agent ─────────
    # PO heeft een vraag beantwoord en de story op AI Queued gezet. We
    # lezen `AI Resume Phase` (welke agent stond stil) en spawnen die
    # opnieuw. Op dit moment is alleen 'refining' een resumebare phase
    # — andere agents zetten geen awaiting-po (nog) — maar de mapping
    # is generiek zodat reviewer/tester later vanzelf werken.
    resume_map = {
        "refining":  "refiner",
        "developing": "developer",
        "reviewing": "reviewer",
        "testing":   "tester",
    }
    resumed = fetch_queued_with_phase("awaiting-po")
    if resumed:
        log.info(
            "  %d issue(s) hervatten na PO-antwoord; active=%d/%d",
            len(resumed), active, MAX_CONCURRENT_JOBS,
        )
    for issue in resumed:
        if active >= MAX_CONCURRENT_JOBS:
            log.info("  capacity bereikt — rest wacht op volgende poll")
            return
        ai = get_ai_fields(issue)
        resume_phase = (ai.get("resume_phase") or "").strip()
        role = resume_map.get(resume_phase)
        if not role:
            log.warning(
                "  %s: phase=awaiting-po maar resume_phase=%r onbekend — skip",
                issue["key"], resume_phase,
            )
            continue
        if _claim_and_spawn(issue, role=role):
            active += 1


def _claim_and_spawn(
    issue: dict, role: str, target_status: Optional[str] = None,
) -> bool:
    """Lees fields + schrijf defaults + transition → target_status +
    spawn runner-Job met de juiste rol. Returnt True bij succes.

    `target_status=None` (default) gebruikt JIRA_TARGET_STATUS (AI In
    Progress) als claim-state. Voor reviewer-spawns geef expliciet de
    huidige status mee zodat de transition een no-op wordt — een PR
    blijft op AI IN REVIEW staan zolang de reviewer/tester eraan werkt.
    """
    key = issue["key"]
    log.info("  oppakken: %s als %s", key, role)

    # Lees AI-fields; schrijf defaults terug zodat de PO ze in JIRA ziet.
    ai = get_ai_fields(issue)
    write_back: dict = {}
    if ai["level"] is None:
        ai["level"] = DEFAULT_AI_LEVEL
        write_back["level"] = DEFAULT_AI_LEVEL
    if ai["token_budget"] is None:
        ai["token_budget"] = DEFAULT_TOKEN_BUDGET
        write_back["token_budget"] = DEFAULT_TOKEN_BUDGET
    if ai["tokens_used"] is None:
        write_back["tokens_used"] = 0
    if write_back:
        set_ai_fields(key, write_back)
        log.info("  defaults geschreven voor %s: %s", key, write_back)

    level = max(0, min(10, ai["level"]))

    # Atomic claim: transition naar target_status. Default = AI In
    # Progress. Voor reviewer/tester wordt huidige status (AI IN REVIEW)
    # gepasseerd zodat geen transitie nodig is.
    effective_target = target_status or JIRA_TARGET_STATUS
    current_status = (issue.get("fields", {}).get("status") or {}).get("name") or ""
    if current_status != effective_target:
        if not transition_issue(key, effective_target):
            log.warning("  transition voor %s faalde — skip deze ronde", key)
            return False
    else:
        log.info("  %s al in %s — geen transitie nodig", key, effective_target)

    task_md = issue_to_task_md(issue)
    job_name = spawn_runner_job(key, task_md, role=role, ai_level=level)
    if not job_name:
        log.error(
            "  spawn faalde voor %s — issue staat nu in %s; manueel terugzetten",
            key, JIRA_TARGET_STATUS,
        )
        return False

    # JIRA-comment alleen bij developer-spawn (refiner is een quick check,
    # die maakt z'n eigen comment-output).
    if role == "developer":
        branch_url = (
            f"https://github.com/{GITHUB_OWNER}/{GITHUB_REPO}/tree/ai/{key}"
            if (GITHUB_OWNER and GITHUB_REPO) else ""
        )
        console_url = (
            f"https://{OPENSHIFT_CONSOLE_HOST}/k8s/ns/{RUNNER_NAMESPACE}/jobs/{job_name}/logs"
            if OPENSHIFT_CONSOLE_HOST else ""
        )
        paragraphs = [
            adf_paragraph(adf_text("🤖 Claude is begonnen aan de implementatie.")),
        ]
        if branch_url:
            paragraphs.append(adf_paragraph(
                adf_text("Branch (zichtbaar zodra Claude de eerste commit pusht): "),
                adf_link(f"ai/{key}", branch_url),
            ))
        paragraphs.append(adf_paragraph(adf_text(f"K8s Job: {job_name}")))
        if console_url:
            paragraphs.append(adf_paragraph(
                adf_text("Live logs (alleen op thuisnetwerk): "),
                adf_link("OpenShift Console", console_url),
            ))
        paragraphs.append(adf_paragraph(
            adf_text("Klaar over ~3-5 min — dan komt er een nieuwe comment met de PR-link."),
        ))
        try:
            jira_post_adf_comment(key, paragraphs)
        except Exception as e:
            log.warning("kon AI-IN-PROGRESS-comment niet plaatsen voor %s: %s", key, e)

    return True


# ─── HTTP-app voor agent-run-rapportage (Fase 1) ─────────────────────────
#
# De poller draait nu óók een kleine HTTP-server (Flask via gunicorn). Eén
# endpoint, `/agent-run/complete`, ontvangt aan het einde van elke
# runner-Job een usage-record + event-stream en schrijft 'm naar
# Postgres (schema `factory`).
#
# De JIRA-poll-loop blijft draaien op een achtergrondthread; gunicorn
# serveert HTTP in de main-thread. Met `--workers 1` (zie Dockerfile) is
# er gegarandeerd één poll-thread.

app = Flask(__name__)


@app.route("/healthz", methods=["GET"])
def healthz():
    return jsonify({"ok": True})


@app.route("/agent-run/complete", methods=["POST"])
def agent_run_complete():
    """Ontvangt usage-record + events van een voltooide runner-Job."""
    if not FACTORY_DATABASE_URL or psycopg is None:
        return jsonify({"error": "factory-DB niet geconfigureerd"}), 503

    data = flask_request.get_json(force=True, silent=True)
    if not data:
        return jsonify({"error": "missing or invalid JSON body"}), 400

    story_key = (data.get("story_key") or "").strip()
    if not story_key:
        return jsonify({"error": "missing story_key"}), 400

    role = data.get("role", "developer")
    job_name = data.get("job_name", "")
    input_tokens = int(data.get("input_tokens", 0) or 0)
    output_tokens = int(data.get("output_tokens", 0) or 0)
    cache_read = int(data.get("cache_read_input_tokens", 0) or 0)
    cache_creation = int(data.get("cache_creation_input_tokens", 0) or 0)
    try:
        cost_usd = float(data.get("cost_usd", 0.0) or 0.0)
    except (TypeError, ValueError):
        cost_usd = 0.0
    num_turns = int(data.get("num_turns", 0) or 0)
    duration_ms = int(data.get("duration_ms", 0) or 0)
    summary_text = data.get("summary_text") or None

    try:
        with psycopg.connect(FACTORY_DATABASE_URL) as conn, conn.cursor() as cur:
            # Vind of maak een lopende story_run voor deze story_key.
            cur.execute(
                """SELECT id FROM factory.story_runs
                   WHERE story_key = %s AND ended_at IS NULL
                   ORDER BY started_at DESC LIMIT 1""",
                (story_key,),
            )
            row = cur.fetchone()
            if row:
                story_run_id = row[0]
            else:
                cur.execute(
                    "INSERT INTO factory.story_runs (story_key) VALUES (%s) RETURNING id",
                    (story_key,),
                )
                story_run_id = cur.fetchone()[0]

            cur.execute(
                """INSERT INTO factory.agent_runs
                   (story_run_id, role, job_name, model, effort, level,
                    ended_at, outcome,
                    input_tokens, output_tokens,
                    cache_read_input_tokens, cache_creation_input_tokens,
                    cost_usd_est, num_turns, duration_ms, summary_text)
                   VALUES (%s, %s, %s, %s, %s, %s, now(), %s,
                           %s, %s, %s, %s, %s, %s, %s, %s)
                   RETURNING id""",
                (
                    story_run_id, role, job_name,
                    data.get("model") or None,
                    data.get("effort") or None,
                    int(data.get("level", 0) or 0),
                    data.get("outcome", ""),
                    input_tokens, output_tokens,
                    cache_read, cache_creation,
                    cost_usd, num_turns, duration_ms, summary_text,
                ),
            )
            agent_run_id = cur.fetchone()[0]

            # Bulk-insert events. Onbekende/missende kind/payload-velden
            # vallen netjes terug op default-waarden.
            events = data.get("events") or []
            event_rows = []
            for e in events:
                if not isinstance(e, dict):
                    continue
                event_rows.append((
                    agent_run_id,
                    e.get("kind", "unknown"),
                    json.dumps(e.get("payload", {})),
                ))
            if event_rows:
                cur.executemany(
                    """INSERT INTO factory.agent_events (agent_run_id, kind, payload)
                       VALUES (%s, %s, %s::jsonb)""",
                    event_rows,
                )

            # Story-totals bijwerken (cost-monitor leest deze sommen).
            cur.execute(
                """UPDATE factory.story_runs
                   SET total_input_tokens          = total_input_tokens          + %s,
                       total_output_tokens         = total_output_tokens         + %s,
                       total_cache_read_tokens     = total_cache_read_tokens     + %s,
                       total_cache_creation_tokens = total_cache_creation_tokens + %s,
                       total_cost_usd_est          = total_cost_usd_est          + %s
                   WHERE id = %s""",
                (input_tokens, output_tokens, cache_read, cache_creation,
                 cost_usd, story_run_id),
            )

            conn.commit()

        log.info(
            "recorded agent_run %d (story=%s role=%s tokens=%d→%d "
            "cache_r=%d cache_c=%d cost=$%.4f turns=%d events=%d)",
            agent_run_id, story_key, role,
            input_tokens, output_tokens,
            cache_read, cache_creation, cost_usd, num_turns,
            len(event_rows),
        )
        # Story 1 (KAN-39): realtime budget-check. Idempotent — bestaande
        # [COST-MONITOR]-markers voorkomen dubbele comments op dezelfde
        # drempel. Bij >=100% pauzeert de story (AI Needs Info + awaiting-po).
        try:
            check_budget_and_act(story_key, story_run_id, role)
        except Exception as bex:
            log.exception("budget-check faalde voor %s: %s", story_key, bex)
        return jsonify({
            "ok": True,
            "agent_run_id": agent_run_id,
            "story_run_id": story_run_id,
        })
    except Exception as e:
        log.exception("agent-run-complete schrijven faalde: %s", e)
        return jsonify({"error": str(e)}), 500


# ─── Budget-monitor (KAN-39) ──────────────────────────────────────────────
#
# Aan het einde van elke agent-run wordt /agent-run/complete aangeroepen.
# Daar checken we 't cumulatieve token-gebruik tegen 't AI Token Budget en
# zetten markers + pauze. Eigen pure-functie zodat de cost-monitor-CronJob
# (KAN-41) dezelfde logica kan hergebruiken.

# Marker-strings die in de JIRA-comments staan; dezelfde tekst dient als
# idempotency-check (jira_has_comment_containing).
_BUDGET_MARKER_75 = "[COST-MONITOR] 75%"
_BUDGET_MARKER_90 = "[COST-MONITOR] 90%"
_BUDGET_MARKER_100 = "[COST-MONITOR] 100%"

# role → resume_phase mapping voor de awaiting-po-flow. Komt overeen met
# de resume_map in process_one_pass (omgekeerd).
_ROLE_TO_RESUME_PHASE = {
    "refiner":   "refining",
    "developer": "developing",
    "reviewer":  "reviewing",
    "tester":    "testing",
}


def check_budget_and_act(story_key: str, story_run_id: int, role: str) -> None:
    """Vergelijk cumulatieve tokens met 't AI Token Budget en post markers
    + pauzeer indien nodig. Idempotent op marker-niveau.

    Cumulatief tokens = total_input_tokens + total_output_tokens van de
    huidige story_run (cache-tokens tellen niet mee — matched [[kan-42]]).
    """
    if not FACTORY_DATABASE_URL or psycopg is None:
        return
    with psycopg.connect(FACTORY_DATABASE_URL) as conn, conn.cursor() as cur:
        cur.execute(
            """SELECT total_input_tokens + total_output_tokens
               FROM factory.story_runs WHERE id = %s""",
            (story_run_id,),
        )
        row = cur.fetchone()
    tokens_used = int(row[0]) if row and row[0] is not None else 0

    # Budget + huidige phase uit JIRA halen.
    r = jira("GET", f"/rest/api/3/issue/{story_key}",
             params={"fields": _ai_fields_param()})
    if r.status_code != 200:
        log.warning("budget-check: issue lookup %s -> %s", story_key, r.status_code)
        return
    issue = r.json()
    ai = get_ai_fields(issue)
    budget = ai.get("token_budget")
    if budget is None or budget <= 0:
        budget = DEFAULT_TOKEN_BUDGET
    pct = (tokens_used * 100) // budget if budget > 0 else 0
    log.info("budget-check %s: used=%d budget=%d pct=%d (role=%s)",
             story_key, tokens_used, budget, pct, role)

    # Tokens-used-field bijwerken voor 't dashboard. Best effort.
    set_ai_fields(story_key, {"tokens_used": tokens_used})

    if pct >= 100:
        if jira_has_comment_containing(story_key, _BUDGET_MARKER_100):
            return  # al gepauzeerd op deze drempel — niks doen
        resume_phase = _ROLE_TO_RESUME_PHASE.get(role, ai.get("phase") or "")
        updates = {"phase": "awaiting-po"}
        if resume_phase:
            updates["resume_phase"] = resume_phase
        set_ai_fields(story_key, updates)
        transition_issue(story_key, "AI Needs Info")
        jira_post_comment(
            story_key,
            f"{_BUDGET_MARKER_100} — budget bereikt "
            f"({tokens_used}/{budget} tokens). Story is gepauzeerd op "
            f"phase={resume_phase or '?'}.\n\n"
            "Om door te gaan, plaats één van deze comments:\n"
            "  BUDGET=120000   (zet nieuw absoluut budget in tokens)\n"
            "  CONTINUE        (verhoog huidig budget met +50%)\n",
        )
        return

    if pct >= 90:
        if not jira_has_comment_containing(story_key, _BUDGET_MARKER_90):
            jira_post_comment(
                story_key,
                f"{_BUDGET_MARKER_90} — bijna op "
                f"({tokens_used}/{budget} tokens). Bij 100% wordt de "
                "story automatisch gepauzeerd.",
            )
        return

    if pct >= 75:
        if not jira_has_comment_containing(story_key, _BUDGET_MARKER_75):
            jira_post_comment(
                story_key,
                f"{_BUDGET_MARKER_75} van budget bereikt "
                f"({tokens_used}/{budget} tokens).",
            )


# ─── Background polling thread ───────────────────────────────────────────

_poll_thread_started = False
_poll_thread_lock = threading.Lock()


def _start_poll_thread() -> None:
    """Spawn de JIRA-poll-loop als daemon-thread (idempotent)."""
    global _poll_thread_started
    with _poll_thread_lock:
        if _poll_thread_started:
            return
        _poll_thread_started = True

    log.info(
        "start poll-loop — JIRA=%s project=%s source=%r target=%r interval=%ds max=%d",
        JIRA_BASE_URL, JIRA_PROJECT,
        JIRA_SOURCE_STATUS, JIRA_TARGET_STATUS,
        POLL_INTERVAL_SEC, MAX_CONCURRENT_JOBS,
    )

    # JIRA-auth sanity check — niet-blokkerend (poll-loop start sowieso).
    try:
        me = jira("GET", "/rest/api/3/myself")
        if me.status_code == 200:
            log.info("JIRA-auth OK als %s", me.json().get("emailAddress"))
        else:
            log.error("JIRA-auth faalt: %s %s", me.status_code, me.text[:200])
    except Exception as e:
        log.warning("JIRA-auth-check faalde: %s", e)

    def loop():
        while True:
            try:
                process_one_pass()
            except Exception as e:
                log.exception("pass faalde: %s", e)
            time.sleep(POLL_INTERVAL_SEC)

    t = threading.Thread(target=loop, daemon=True, name="poll-loop")
    t.start()


# ─── Factory-schema bootstrap ───────────────────────────────────────────
#
# In plaats van een aparte init-Job met ConfigMap + ArgoCD-hook draaien
# we de DDL bij elke poller-startup. Idempotent (`CREATE … IF NOT EXISTS`)
# en bliksemsnel. Geen sync-wave-ordering te bewaken.
#
# Faalt 't (DB onbereikbaar, geen rechten): poller start tóch, alleen de
# HTTP-endpoint geeft 503 totdat we 't fixen.

_FACTORY_SCHEMA_PATH = "/app/factory-schema.sql"


def init_schema() -> None:
    if not FACTORY_DATABASE_URL:
        log.info("FACTORY_DATABASE_URL leeg — sla schema-init over")
        return
    if psycopg is None:
        log.warning("psycopg niet geïnstalleerd — sla schema-init over")
        return
    if not os.path.exists(_FACTORY_SCHEMA_PATH):
        log.warning("schema-file %s niet gevonden — sla schema-init over",
                    _FACTORY_SCHEMA_PATH)
        return

    try:
        with open(_FACTORY_SCHEMA_PATH, "r", encoding="utf-8") as f:
            ddl = f.read()
        with psycopg.connect(FACTORY_DATABASE_URL) as conn, conn.cursor() as cur:
            cur.execute(ddl)
            conn.commit()
        log.info("factory-schema toegepast (%d bytes DDL)", len(ddl))
    except Exception as e:
        log.exception("factory-schema bootstrap faalde: %s", e)


# Onder gunicorn (productie): bootstrap + poll-loop bij module-load.
# Onder `python poller.py` (dev): idem, plus Flask via main() hieronder.
init_schema()
_start_poll_thread()


def main() -> int:
    """Dev-modus entrypoint. In productie draait gunicorn dit module.

    `_start_poll_thread()` is bij module-load al gedraaid; hier alleen
    nog de Flask dev-server starten.
    """
    app.run(host="0.0.0.0", port=8080)
    return 0


if __name__ == "__main__":
    sys.exit(main())
