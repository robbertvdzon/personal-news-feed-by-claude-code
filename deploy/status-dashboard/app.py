#!/usr/bin/env python3
"""
Personal News Feed — status dashboard.

Eén HTML-pagina die in één blik laat zien:
  * Production: wat draait er op vdzonsoftware.nl (current SHA, build- en
    deploy-status, pod-health, laatste merges).
  * Open PR's: per PR de fasen validate → build backend → build frontend
    → ArgoCD sync → pods. Met klikbare links naar de PR, naar gefaalde
    GitHub Actions-runs en naar de live preview.

Data komt uit:
  * GitHub REST API   — PR's, workflow runs, jobs per run
  * Kubernetes API    — Application-objecten (argocd) + pods
                        (personal-news-feed + pnf-pr-*)

Het is bewust een server-side gerenderde HTML-pagina (geen SPA). Geen
JavaScript, een `<meta refresh>` doet de auto-refresh. Werkt zo prima op
elke browser én op je telefoon, ook bij krappe netwerken.
"""

import base64
import json
import logging
import os
import re
import subprocess
import time
from dataclasses import dataclass, field
from html import escape
from typing import Optional

import requests
from flask import Flask, Response, jsonify, redirect, request

# psycopg v3 voor de factory-DB (token/cost-lookups, timeline-page).
# Soft import zodat dashboard ook draait als pakket-build nog niet door is.
try:
    import psycopg
except ImportError:  # pragma: no cover
    psycopg = None  # type: ignore

# ─── config ───────────────────────────────────────────────────────────────

GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN", "")
GITHUB_OWNER = os.environ.get("GITHUB_OWNER", "robbertvdzon")
GITHUB_REPO = os.environ.get(
    "GITHUB_REPO", "personal-news-feed-by-claude-code"
)
ARGOCD_NS = os.environ.get("ARGOCD_NS", "argocd")
# PROD_NS = waar de applicatie zelf draait (frontend/backend/tunnel).
# FACTORY_NS = waar de claude-runner Jobs + poller + dashboard zelf draaien.
# Sinds Fase 0 zijn die expliciet gescheiden; voor 'app-pods' lezen we
# uit PROD_NS, voor 'runner-Jobs' uit FACTORY_NS.
PROD_NS = os.environ.get("PROD_NS", "personal-news-feed")
FACTORY_NS = os.environ.get("FACTORY_NS", "pnf-software-factory")
PREVIEW_NS_PREFIX = os.environ.get("PREVIEW_NS_PREFIX", "pnf-pr-")
APP_BASE_URL = os.environ.get("APP_BASE_URL", "https://vdzonsoftware.nl")
PREVIEW_URL_FORMAT = os.environ.get(
    "PREVIEW_URL_FORMAT", "https://pnf-pr-{pr}.vdzonsoftware.nl"
)
CACHE_TTL_SEC = int(os.environ.get("CACHE_TTL_SEC", "10"))
REFRESH_SEC = int(os.environ.get("REFRESH_SEC", "10"))
# Factory-DB voor token/cost-lookups en de timeline-page. Leeg = sectie
# wordt overgeslagen, dashboard blijft voor de rest werken.
FACTORY_DATABASE_URL = os.environ.get("FACTORY_DATABASE_URL", "")

# JIRA-config voor de "AI bezig"-sectie. Als JIRA_API_KEY leeg is wordt
# de sectie netjes overgeslagen — niets gebroken.
JIRA_BASE_URL = os.environ.get("JIRA_BASE_URL", "").rstrip("/")
JIRA_EMAIL = os.environ.get("JIRA_EMAIL", "")
JIRA_API_KEY = os.environ.get("JIRA_API_KEY", "")
JIRA_PROJECT = os.environ.get("JIRA_PROJECT", "KAN")
# Statussen die in de "AI bezig"-sectie verschijnen. Comma-separated.
JIRA_ACTIVE_STATUSES = [
    s.strip()
    for s in os.environ.get(
        "JIRA_ACTIVE_STATUSES",
        "AI Ready,AI Queued,AI IN PROGRESS,AI Needs Info,AI Paused",
    ).split(",")
    if s.strip()
]
# Statussen die op PR-kaartjes als badge verschijnen (typisch AI IN REVIEW).
# Worden gecombineerd met JIRA_ACTIVE_STATUSES in één zoekopdracht.
JIRA_PR_STATUSES = [
    s.strip()
    for s in os.environ.get("JIRA_PR_STATUSES", "AI IN REVIEW").split(",")
    if s.strip()
]

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [status] %(message)s",
    datefmt="%Y-%m-%dT%H:%M:%SZ",
)
log = logging.getLogger("status")


# ─── GitHub helpers ───────────────────────────────────────────────────────

_gh_session = requests.Session()
_gh_session.headers.update(
    {
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
)
if GITHUB_TOKEN:
    _gh_session.headers["Authorization"] = f"Bearer {GITHUB_TOKEN}"


def gh(path: str, params: Optional[dict] = None) -> Optional[dict]:
    """Wrap GitHub REST API met logging op fouten."""
    url = f"https://api.github.com{path}"
    try:
        r = _gh_session.get(url, params=params, timeout=10)
    except requests.RequestException as e:
        log.warning("GH %s faalde: %s", path, e)
        return None
    if r.status_code != 200:
        log.warning("GH %s -> %s %s", path, r.status_code, r.text[:120])
        return None
    return r.json()


def gh_prs_for_branch(branch: str) -> list[dict]:
    """Open + closed PR's voor een specifieke head-branch. Voor de
    detail-page van een story: typisch 1 PR per ai/-branch."""
    data = gh(
        f"/repos/{GITHUB_OWNER}/{GITHUB_REPO}/pulls",
        params={
            "state": "all",
            "head": f"{GITHUB_OWNER}:{branch}",
            "per_page": "10",
            "sort": "updated",
            "direction": "desc",
        },
    )
    return data or []


def gh_commits_for_branch(branch: str, limit: int = 30) -> list[dict]:
    """Commits op een branch DIE NIET OOK OP MAIN STAAN. Gebruikt
    GitHub's compare-API i.p.v. /commits, anders krijg je ook de
    main-historie mee. Nieuwste eerst."""
    data = gh(
        f"/repos/{GITHUB_OWNER}/{GITHUB_REPO}/compare/main...{branch}",
    )
    if not data:
        return []
    commits = data.get("commits", []) or []
    # GitHub compare-API geeft oudste eerst; wij willen nieuwste eerst.
    commits.reverse()
    return commits[:limit]


def gh_list_open_prs() -> list[dict]:
    data = gh(
        f"/repos/{GITHUB_OWNER}/{GITHUB_REPO}/pulls",
        params={"state": "open", "per_page": "30", "sort": "updated", "direction": "desc"},
    )
    return data or []


def gh_list_recent_closed_prs(limit: int = 5) -> list[dict]:
    data = gh(
        f"/repos/{GITHUB_OWNER}/{GITHUB_REPO}/pulls",
        params={
            "state": "closed",
            "per_page": str(limit),
            "sort": "updated",
            "direction": "desc",
        },
    )
    return [p for p in (data or []) if p.get("merged_at")][:limit]


def gh_latest_run_for_branch(workflow_file: str, branch: str) -> Optional[dict]:
    """Meest recente run van een workflow voor een specifieke branch."""
    data = gh(
        f"/repos/{GITHUB_OWNER}/{GITHUB_REPO}/actions/workflows/{workflow_file}/runs",
        params={"branch": branch, "per_page": "1"},
    )
    if not data:
        return None
    runs = data.get("workflow_runs") or []
    return runs[0] if runs else None


def gh_jobs_for_run(run_id: int) -> list[dict]:
    """Jobs binnen een workflow-run (voor backend/frontend split)."""
    data = gh(
        f"/repos/{GITHUB_OWNER}/{GITHUB_REPO}/actions/runs/{run_id}/jobs",
        params={"per_page": "20"},
    )
    if not data:
        return []
    return data.get("jobs") or []


def gh_latest_run_for_sha(workflow_file: str, sha: str) -> Optional[dict]:
    """Run van een workflow voor een specifieke commit-SHA op main."""
    data = gh(
        f"/repos/{GITHUB_OWNER}/{GITHUB_REPO}/actions/workflows/{workflow_file}/runs",
        params={"head_sha": sha, "per_page": "1"},
    )
    if not data:
        return None
    runs = data.get("workflow_runs") or []
    return runs[0] if runs else None


def gh_main_head_commit() -> Optional[dict]:
    data = gh(f"/repos/{GITHUB_OWNER}/{GITHUB_REPO}/commits/main")
    return data


def gh_commit(sha: str) -> Optional[dict]:
    """Een specifieke commit ophalen (voor committer.date per PR-head)."""
    if not sha:
        return None
    return gh(f"/repos/{GITHUB_OWNER}/{GITHUB_REPO}/commits/{sha}")


def gh_latest_release() -> Optional[dict]:
    """Meest recente non-draft, non-prerelease release. None als er nog
    geen release is."""
    data = gh(f"/repos/{GITHUB_OWNER}/{GITHUB_REPO}/releases/latest")
    return data


# ─── kubectl helpers ──────────────────────────────────────────────────────


def kubectl_json(*args) -> dict:
    """Voer kubectl uit met -o json en parse. Returns {} bij fout."""
    cmd = ["kubectl", *args, "-o", "json"]
    try:
        out = subprocess.run(
            cmd, capture_output=True, text=True, timeout=10, check=False
        )
    except subprocess.SubprocessError as e:
        log.warning("kubectl %s faalde: %s", " ".join(args), e)
        return {}
    if out.returncode != 0:
        log.warning("kubectl %s -> rc=%d %s", " ".join(args), out.returncode, out.stderr[:120])
        return {}
    try:
        return json.loads(out.stdout)
    except json.JSONDecodeError:
        return {}


def k8s_applications() -> list[dict]:
    """Alle ArgoCD Applications in de argocd-namespace."""
    data = kubectl_json("get", "applications.argoproj.io", "-n", ARGOCD_NS)
    return data.get("items", [])


def k8s_pods(namespace: str) -> list[dict]:
    data = kubectl_json("get", "pods", "-n", namespace)
    return data.get("items", [])


def k8s_pnf_namespaces() -> list[str]:
    """Lijst van alle pnf-pr-* namespaces."""
    data = kubectl_json("get", "namespaces")
    return [
        ns["metadata"]["name"]
        for ns in data.get("items", [])
        if ns.get("metadata", {}).get("name", "").startswith(PREVIEW_NS_PREFIX)
    ]


def k8s_jobs(namespace: str, label_selector: str = "") -> list[dict]:
    """Lijst van Jobs in een namespace, optioneel gefilterd op label."""
    args = ["get", "jobs", "-n", namespace]
    if label_selector:
        args.extend(["-l", label_selector])
    return kubectl_json(*args).get("items", [])


# ─── Kubernetes in-cluster API (voor pod-logs) ────────────────────────────

_K8S_API = "https://kubernetes.default.svc"
_K8S_TOKEN_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/token"
_K8S_CA_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"


def _k8s_get(path: str, params: Optional[dict] = None) -> Optional[requests.Response]:
    """HTTP GET naar de in-cluster K8s API via de pod's ServiceAccount-token."""
    try:
        with open(_K8S_TOKEN_PATH) as f:
            token = f.read().strip()
    except OSError:
        return None
    ca = _K8S_CA_PATH if os.path.exists(_K8S_CA_PATH) else False
    try:
        return requests.get(
            f"{_K8S_API}{path}",
            headers={"Authorization": f"Bearer {token}"},
            params=params,
            verify=ca,
            timeout=15,
        )
    except requests.RequestException as e:
        log.warning("k8s GET %s faalde: %s", path, e)
        return None


def k8s_pod_log(
    pod_name: str, namespace: str = "", tail_lines: int = 2000
) -> tuple[Optional[str], int]:
    """Haal pod-log op via K8s API.

    Returnt (log_text, http_status). http_status=0 betekent een setup-
    of netwerkprobleem (geen SA-token, DNS, time-out). log_text is None
    bij elke niet-200; alleen bij 200 staat de log erin.

    De caller kan op http_status branchen voor specifieke meldingen
    (b.v. 403 → "rbac mist pods/log", 404 → "pod weg").
    """
    # Default namespace voor pod-logs is FACTORY_NS — alle pods waarvan
    # we logs willen tonen (runner-Jobs) draaien daar.
    ns = namespace or FACTORY_NS
    r = _k8s_get(
        f"/api/v1/namespaces/{ns}/pods/{pod_name}/log",
        params={"tailLines": str(tail_lines)},
    )
    if r is None:
        return None, 0
    if r.status_code == 200:
        return r.text, 200
    log.warning("k8s pod log %s -> %s: %s", pod_name, r.status_code, r.text[:200])
    return None, r.status_code


# ─── Factory-DB helpers ──────────────────────────────────────────────────


def _factory_db_available() -> bool:
    return bool(FACTORY_DATABASE_URL) and psycopg is not None


def factory_totals_by_story(story_keys: list[str]) -> dict[str, dict]:
    """Geef per story_key de totalen van de meest recente story_run.

    Returnt {story_key: {input, output, cache_read, cache_creation, cost_usd,
    run_count}}. Run_count = aantal agent_runs voor de laatste story_run,
    handig om loop-detectie te doen (>10 = waarschijnlijk vast). Stories
    zonder rij in de DB komen niet voor in het resultaat.
    """
    if not _factory_db_available() or not story_keys:
        return {}
    try:
        with psycopg.connect(FACTORY_DATABASE_URL) as conn, conn.cursor() as cur:
            # DISTINCT ON pakt per story_key de meest recente story_run.
            # LATERAL join voor de agent_runs-count per dat story_run.
            cur.execute(
                """SELECT DISTINCT ON (sr.story_key)
                          sr.story_key,
                          sr.total_input_tokens, sr.total_output_tokens,
                          sr.total_cache_read_tokens, sr.total_cache_creation_tokens,
                          sr.total_cost_usd_est,
                          COALESCE(ar.cnt, 0)
                   FROM factory.story_runs sr
                   LEFT JOIN LATERAL (
                       SELECT COUNT(*)::int AS cnt
                       FROM factory.agent_runs
                       WHERE story_run_id = sr.id
                   ) ar ON true
                   WHERE sr.story_key = ANY(%s)
                   ORDER BY sr.story_key, sr.started_at DESC""",
                (list(story_keys),),
            )
            return {
                row[0]: {
                    "input": row[1] or 0,
                    "output": row[2] or 0,
                    "cache_read": row[3] or 0,
                    "cache_creation": row[4] or 0,
                    "cost_usd": float(row[5] or 0),
                    "run_count": row[6] or 0,
                }
                for row in cur.fetchall()
            }
    except Exception as e:
        log.warning("factory_totals_by_story faalde: %s", e)
        return {}


def factory_all_stories(limit: int = 200) -> list[dict]:
    """Lijst alle stories uit `factory.story_runs` (één rij per story_run,
    nieuwste eerst). Gebruikt door de /stories-overzichtstabel.

    Per rij geven we ook count(agent_runs) + som(duration_ms) terug
    zodat het dashboard direct duur + activiteit kan tonen zonder
    een second-roundtrip per story.
    """
    if not _factory_db_available():
        return []
    try:
        with psycopg.connect(FACTORY_DATABASE_URL) as conn, conn.cursor() as cur:
            cur.execute(
                """SELECT sr.id, sr.story_key, sr.started_at, sr.ended_at,
                          sr.final_status,
                          sr.total_input_tokens, sr.total_output_tokens,
                          sr.total_cache_read_tokens, sr.total_cache_creation_tokens,
                          sr.total_cost_usd_est,
                          COALESCE(ar.run_count, 0) AS run_count,
                          COALESCE(ar.duration_ms_sum, 0) AS duration_ms_sum
                   FROM factory.story_runs sr
                   LEFT JOIN LATERAL (
                       SELECT COUNT(*) AS run_count,
                              SUM(duration_ms) AS duration_ms_sum
                       FROM factory.agent_runs
                       WHERE story_run_id = sr.id
                   ) ar ON true
                   ORDER BY sr.started_at DESC
                   LIMIT %s""",
                (limit,),
            )
            rows = []
            for r in cur.fetchall():
                rows.append({
                    "id": r[0],
                    "story_key": r[1],
                    "started_at": r[2],
                    "ended_at": r[3],
                    "final_status": r[4] or "",
                    "input": r[5] or 0,
                    "output": r[6] or 0,
                    "cache_read": r[7] or 0,
                    "cache_creation": r[8] or 0,
                    "cost_usd": float(r[9] or 0),
                    "run_count": r[10] or 0,
                    "duration_ms_sum": r[11] or 0,
                })
            return rows
    except Exception as e:
        log.warning("factory_all_stories faalde: %s", e)
        return []


def factory_story_timeline(story_key: str) -> Optional[dict]:
    """Geef alle agent_runs voor de meest recente story_run van story_key.

    Returnt {story_run_id, started_at, ended_at, totals, runs: [...]}
    of None als de story niet in de DB voorkomt.
    """
    if not _factory_db_available():
        return None
    try:
        with psycopg.connect(FACTORY_DATABASE_URL) as conn, conn.cursor() as cur:
            cur.execute(
                """SELECT id, started_at, ended_at, final_status,
                          total_input_tokens, total_output_tokens,
                          total_cache_read_tokens, total_cache_creation_tokens,
                          total_cost_usd_est
                   FROM factory.story_runs
                   WHERE story_key = %s
                   ORDER BY started_at DESC LIMIT 1""",
                (story_key,),
            )
            sr_row = cur.fetchone()
            if not sr_row:
                return None
            story_run_id = sr_row[0]

            cur.execute(
                """SELECT id, role, job_name, model, effort, level,
                          started_at, ended_at, outcome,
                          input_tokens, output_tokens,
                          cache_read_input_tokens, cache_creation_input_tokens,
                          cost_usd_est, num_turns, duration_ms,
                          summary_text
                   FROM factory.agent_runs
                   WHERE story_run_id = %s
                   ORDER BY started_at ASC""",
                (story_run_id,),
            )
            runs = []
            for r in cur.fetchall():
                runs.append({
                    "id": r[0],
                    "role": r[1] or "",
                    "job_name": r[2] or "",
                    "model": r[3] or "",
                    "effort": r[4] or "",
                    "level": r[5],
                    "started_at": r[6],
                    "ended_at": r[7],
                    "outcome": r[8] or "",
                    "input": r[9] or 0,
                    "output": r[10] or 0,
                    "cache_read": r[11] or 0,
                    "cache_creation": r[12] or 0,
                    "cost_usd": float(r[13] or 0),
                    "num_turns": r[14] or 0,
                    "duration_ms": r[15] or 0,
                    "summary_text": r[16] or "",
                })

            return {
                "id": story_run_id,
                "story_key": story_key,
                "started_at": sr_row[1],
                "ended_at": sr_row[2],
                "final_status": sr_row[3],
                "totals": {
                    "input": sr_row[4] or 0,
                    "output": sr_row[5] or 0,
                    "cache_read": sr_row[6] or 0,
                    "cache_creation": sr_row[7] or 0,
                    "cost_usd": float(sr_row[8] or 0),
                },
                "runs": runs,
            }
    except Exception as e:
        log.warning("factory_story_timeline(%s) faalde: %s", story_key, e)
        return None


def factory_events_for_run(agent_run_id: int) -> list[dict]:
    """Geef alle agent_events voor een specifieke agent_run, ordered op ts."""
    if not _factory_db_available():
        return []
    try:
        with psycopg.connect(FACTORY_DATABASE_URL) as conn, conn.cursor() as cur:
            cur.execute(
                """SELECT kind, payload, ts
                   FROM factory.agent_events
                   WHERE agent_run_id = %s
                   ORDER BY id ASC""",
                (agent_run_id,),
            )
            return [
                {"kind": r[0], "payload": r[1], "ts": r[2]}
                for r in cur.fetchall()
            ]
    except Exception as e:
        log.warning("factory_events_for_run(%s) faalde: %s", agent_run_id, e)
        return []


def factory_lookup_agent_run_by_job(job_name: str) -> Optional[dict]:
    """Zoek een agent_run-row op basis van job_name. Voor de log-fallback
    in /runner/<job>/log als de pod al weg is."""
    if not _factory_db_available():
        return None
    try:
        with psycopg.connect(FACTORY_DATABASE_URL) as conn, conn.cursor() as cur:
            cur.execute(
                """SELECT ar.id, ar.role, ar.outcome, ar.started_at, ar.ended_at,
                          ar.input_tokens, ar.output_tokens, ar.cost_usd_est,
                          ar.num_turns, ar.duration_ms,
                          sr.story_key
                   FROM factory.agent_runs ar
                   JOIN factory.story_runs sr ON sr.id = ar.story_run_id
                   WHERE ar.job_name = %s
                   ORDER BY ar.started_at DESC LIMIT 1""",
                (job_name,),
            )
            row = cur.fetchone()
            if not row:
                return None
            return {
                "id": row[0],
                "role": row[1] or "",
                "outcome": row[2] or "",
                "started_at": row[3],
                "ended_at": row[4],
                "input": row[5] or 0,
                "output": row[6] or 0,
                "cost_usd": float(row[7] or 0),
                "num_turns": row[8] or 0,
                "duration_ms": row[9] or 0,
                "story_key": row[10] or "",
            }
    except Exception as e:
        log.warning("factory_lookup_agent_run_by_job(%s) faalde: %s", job_name, e)
        return None


# ─── JIRA helpers ─────────────────────────────────────────────────────────


_jira_session = requests.Session()
if JIRA_EMAIL and JIRA_API_KEY:
    _jira_session.auth = (JIRA_EMAIL, JIRA_API_KEY)
    _jira_session.headers.update({"Accept": "application/json"})


# Custom-field-discovery — net als de poller cachen we de field-IDs
# eenmalig zodat we de actuele AI Level + AI Phase per ticket kunnen
# tonen. Display-namen exact zoals ze in JIRA staan.
_AI_FIELDS = {
    "level": "AI Level",
    "phase": "AI Phase",
}
_ai_field_id_cache: dict[str, Optional[str]] = {}


def _discover_ai_field_ids() -> None:
    if _ai_field_id_cache or not (JIRA_BASE_URL and JIRA_EMAIL and JIRA_API_KEY):
        return
    try:
        r = _jira_session.get(f"{JIRA_BASE_URL}/rest/api/3/field", timeout=10)
        if r.status_code != 200:
            log.warning("dashboard field-discovery -> %s", r.status_code)
            return
        by_name = {fld.get("name", ""): fld.get("id", "") for fld in r.json()}
        for short, display in _AI_FIELDS.items():
            _ai_field_id_cache[short] = by_name.get(display)
    except Exception as e:
        log.warning("dashboard field-discovery faalde: %s", e)


def _ai_field_id(short: str) -> Optional[str]:
    _discover_ai_field_ids()
    return _ai_field_id_cache.get(short)


def _jira_last_status_change(issue: dict) -> str:
    """
    Geef de ISO-timestamp terug van de meest recente status-veld-wisseling
    van deze issue, gebaseerd op de changelog. Lege string als geen
    status-wisseling gevonden of changelog ontbreekt.

    De zoek-API levert histories meestal oudst-eerst, dus we scannen
    volledig en houden de laatste status-item bij.
    """
    histories = (issue.get("changelog") or {}).get("histories") or []
    latest = ""
    for h in histories:
        created = h.get("created", "")
        for item in h.get("items", []) or []:
            if item.get("field") == "status":
                if not latest or created > latest:
                    latest = created
                break
    return latest


def jira_search_tracked() -> list[dict]:
    """
    Issues in een van de tracked statussen (AI Ready + AI IN PROGRESS
    voor de "AI bezig"-sectie, plus AI IN REVIEW voor PR-badges). Eén
    call dekt beide gebruiken.
    """
    if not (JIRA_BASE_URL and JIRA_EMAIL and JIRA_API_KEY):
        return []
    tracked = JIRA_ACTIVE_STATUSES + JIRA_PR_STATUSES
    if not tracked:
        return []
    quoted = ",".join(f'"{s}"' for s in tracked)
    jql = (
        f"project={JIRA_PROJECT} AND status in ({quoted}) "
        f"ORDER BY updated DESC"
    )
    # AI custom-fields meevragen — de IDs ontdekken we lazy.
    _discover_ai_field_ids()
    field_list = "summary,status,updated"
    for fid in _ai_field_id_cache.values():
        if fid:
            field_list += f",{fid}"
    try:
        r = _jira_session.get(
            f"{JIRA_BASE_URL}/rest/api/3/search/jql",
            params={
                "jql": jql,
                "fields": field_list,
                # changelog ophalen zodat we de exacte laatste
                # status-field-wisseling kunnen vinden. Anders zou
                # b.v. AI IN REVIEW → AI IN PROGRESS (zelfde categorie)
                # géén timestamp-update geven via
                # statuscategorychangedate.
                "expand": "changelog",
                "maxResults": "50",
            },
            timeout=10,
        )
    except requests.RequestException as e:
        log.warning("JIRA search faalde: %s", e)
        return []
    if r.status_code != 200:
        log.warning("JIRA search -> %s %s", r.status_code, r.text[:120])
        return []
    return r.json().get("issues", [])


# ─── data-modellen ────────────────────────────────────────────────────────


@dataclass
class Phase:
    label: str
    status: str  # 'pass', 'running', 'fail', 'pending', 'unknown'
    detail: str = ""
    link: str = ""
    since: str = ""  # 'Xm geleden' suffix, b.v. wanneer de run klaar was


@dataclass
class PRCard:
    number: int
    title: str
    html_url: str
    branch: str
    head_sha: str
    author: str
    updated_age: str
    phases: list[Phase] = field(default_factory=list)
    preview_url: str = ""
    # Status-badges (afgeleid uit fases + JIRA):
    pr_state: str = ""          # 'ready' / 'building' / 'failed' / 'pending'
    pr_state_label: str = ""    # mensleesbare label
    jira_status: str = ""       # 'AI IN REVIEW' etc., leeg als geen JIRA-issue
    jira_status_age: str = ""   # 'sinds Xm' (vanuit changelog)
    last_commit_age: str = ""   # 'Xm geleden' op de HEAD-commit
    runner_state: str = "none"  # 'none', 'running', 'finished', 'failed'
    runner_text: str = "—"      # display-tekst, bv. "🟢 running (12s)"
    runner_job_name: str = ""   # job-naam voor de log-link
    # Factory-DB totalen (Fase 1 PR 2). 0 = geen rij in DB of niet
    # geconfigureerd.
    tokens_input: int = 0
    tokens_output: int = 0
    tokens_cache_read: int = 0
    cost_usd: float = 0.0
    # AI custom-fields (Fase 2 PR 3). -1 = veld niet ingevuld.
    ai_level: int = -1
    ai_phase: str = ""


@dataclass
class MainCard:
    sha: str
    sha_short: str
    message: str
    age: str
    phases: list[Phase] = field(default_factory=list)
    recent_merges: list[dict] = field(default_factory=list)
    apk_url: str = ""           # directe download-URL van de APK-asset
    apk_filename: str = ""
    apk_age: str = ""           # leeftijd van de release


@dataclass
class ClosedCard:
    number: int
    title: str
    html_url: str
    merged_age: str
    branch: str = ""
    merged_at: str = ""           # ISO-timestamp
    head_sha: str = ""
    story_key: str = ""           # leeg als branch geen ai/<KEY> patroon volgt
    # Factory-DB totalen (alleen ingevuld als story_key matcht):
    tokens_input: int = 0
    tokens_output: int = 0
    tokens_cache_read: int = 0
    cost_usd: float = 0.0
    run_count: int = 0


@dataclass
class JIRACard:
    key: str
    title: str
    status: str           # 'AI Ready' / 'AI IN PROGRESS' / etc.
    jira_url: str
    age: str
    # Job-info (alleen bij IN PROGRESS): "Running 3m", "Pending", "Failed", ""
    job_state: str = ""
    job_status: str = ""  # 'running' / 'pass' / 'fail' / 'pending'
    job_name: str = ""
    # Factory-DB totalen (Fase 1 PR 2)
    tokens_input: int = 0
    tokens_output: int = 0
    tokens_cache_read: int = 0
    cost_usd: float = 0.0
    # AI custom-fields (Fase 2 PR 3)
    ai_level: int = -1
    ai_phase: str = ""
    # Hoeveel agent-runs heeft deze story al gehad? Hoog aantal = mogelijke
    # loop (dev↔reviewer pingpong of tester die maar niet OK krijgt).
    run_count: int = 0


# ─── helpers voor fase-status afleiden ────────────────────────────────────


def _run_to_status(run: Optional[dict]) -> tuple[str, str, str, str]:
    """Map workflow-run naar (status, detail, link, since)."""
    if not run:
        return ("pending", "—", "", "")
    state = run.get("status")  # queued/in_progress/completed
    conc = run.get("conclusion")  # success/failure/cancelled/...
    link = run.get("html_url", "")
    # Timestamp: updated_at als 'm klaar is, anders run_started_at.
    if state == "completed":
        since = _ago(run.get("updated_at", ""))
    else:
        since = _ago(run.get("run_started_at") or run.get("created_at", ""))
    if state != "completed":
        return ("running", state or "running", link, since)
    if conc == "success":
        return ("pass", "", link, since)
    return ("fail", conc or "failed", link, since)


def _job_to_status(job: Optional[dict]) -> tuple[str, str, str, str]:
    if not job:
        return ("pending", "—", "", "")
    state = job.get("status")
    conc = job.get("conclusion")
    link = job.get("html_url", "")
    if state == "completed":
        since = _ago(job.get("completed_at", "") or job.get("started_at", ""))
    else:
        since = _ago(job.get("started_at", ""))
    if state != "completed":
        return ("running", state or "running", link, since)
    if conc == "success":
        return ("pass", "", link, since)
    return ("fail", conc or "failed", link, since)


def _find_job(jobs: list[dict], name_substr: str) -> Optional[dict]:
    """Eerste job die name_substr in z'n naam heeft."""
    for j in jobs:
        if name_substr in (j.get("name") or ""):
            return j
    return None


def _find_app_by_ns(apps: list[dict], namespace: str) -> Optional[dict]:
    """
    Zoek de ArgoCD Application die naar `namespace` deploy't. De
    ApplicationSet genereert namen als `preview-{number}-{branch_slug}`
    (geen voorspelbaar patroon), maar de destination.namespace is
    deterministisch — die gebruiken we als sleutel.
    """
    for app in apps:
        dest = (app.get("spec") or {}).get("destination") or {}
        if dest.get("namespace") == namespace:
            return app
    return None


_IMAGE_SHA_RE = re.compile(r":sha-([0-9a-f]{7,40})$")


def _pod_image_sha7(pod: dict, part: str) -> str:
    """Extract de 7-char SHA uit het image-tag van de container die bij
    `part` (b.v. backend/frontend) hoort. Lege string als geen match."""
    containers = (pod.get("spec") or {}).get("containers") or []
    for c in containers:
        # Container-name matcht meestal het deel-name; anders pak we 'm
        # gewoon als 't de eerste is.
        name = c.get("name", "")
        if part in name or len(containers) == 1:
            img = c.get("image", "") or ""
            m = _IMAGE_SHA_RE.search(img)
            if m:
                return m.group(1)[:7]
            return ""
    return ""


def _app_phase(app: Optional[dict]) -> Phase:
    """ArgoCD Application status → Phase."""
    if not app:
        return Phase(label="argocd sync", status="pending", detail="—")
    app_status = app.get("status") or {}
    status = (app_status.get("sync") or {}).get("status")
    health = (app_status.get("health") or {}).get("status")
    # Beste timestamp: operationState.finishedAt (laatste sync klaar);
    # fallback naar reconciledAt.
    op_state = app_status.get("operationState") or {}
    since = _ago(
        op_state.get("finishedAt")
        or app_status.get("reconciledAt")
        or ""
    )
    if status == "Synced" and health == "Healthy":
        return Phase(label="argocd sync", status="pass", detail="Synced/Healthy", since=since)
    if status == "Synced" and health == "Progressing":
        return Phase(label="argocd sync", status="running", detail="Synced/Progressing", since=since)
    if status == "OutOfSync":
        return Phase(label="argocd sync", status="running", detail="OutOfSync", since=since)
    return Phase(
        label="argocd sync",
        status="running" if status else "pending",
        detail=f"{status or '—'}/{health or '—'}",
        since=since,
    )


def _derive_pr_state(phases: list[Phase]) -> tuple[str, str]:
    """Aggregeer fase-statussen tot één PR-state-badge (en bijbehorend label)."""
    if any(p.status == "fail" for p in phases):
        return ("failed", "Build failed")
    if any(p.status == "running" for p in phases):
        return ("building", "Building / deploying")
    if all(p.status == "pass" for p in phases):
        return ("ready", "Ready to merge")
    return ("pending", "Pending")


def _pods_phase(pods: list[dict], part: str, expected_sha7: str = "") -> Phase:
    """
    Eén pod-fase per app-onderdeel (backend/frontend).

    Als `expected_sha7` gezet is, wordt de pod's container-image-tag
    (`sha-<7chars>`) vergeleken. Mismatch → fase is `running` met de
    detail "Running (oude image sha-...)" — pod draait nog, maar niet
    de versie waar de PR/main op zit.
    """
    matches = [
        p for p in pods
        if (p.get("metadata", {}).get("labels", {}).get("app") == part)
        or (p.get("metadata", {}).get("labels", {}).get("app.kubernetes.io/name") == part)
        or (part in (p.get("metadata", {}).get("name") or ""))
    ]
    if not matches:
        return Phase(label=f"{part} pod", status="pending", detail="—")
    # Pak de jongste met fase Running als referentie, anders de eerste.
    ready_running = [
        p for p in matches
        if p.get("status", {}).get("phase") == "Running"
        and all(c.get("ready") for c in p.get("status", {}).get("containerStatuses", []) or [])
    ]
    if ready_running:
        p = ready_running[0]
        pod_sha = _pod_image_sha7(p, part)
        restarts = sum(
            c.get("restartCount", 0)
            for c in p.get("status", {}).get("containerStatuses", []) or []
        )
        # Pod-leeftijd: startTime (= moment dat de kubelet 'm scheduled).
        since = _ago(p.get("status", {}).get("startTime", ""))
        # Mismatch met verwachte SHA = oude image, wacht op redeploy.
        if expected_sha7 and pod_sha and pod_sha != expected_sha7:
            return Phase(
                label=f"{part} pod",
                status="running",
                detail=f"Running (oude image sha-{pod_sha})",
                since=since,
            )
        detail = "Running"
        if pod_sha:
            detail += f" (sha-{pod_sha})"
        if restarts:
            detail += f" · restarts: {restarts}"
        return Phase(label=f"{part} pod", status="pass", detail=detail, since=since)
    p = matches[0]
    phase = p.get("status", {}).get("phase", "Unknown")
    reason = ""
    for c in p.get("status", {}).get("containerStatuses", []) or []:
        wait = c.get("state", {}).get("waiting") or {}
        if wait.get("reason"):
            reason = wait["reason"]
            break
    return Phase(
        label=f"{part} pod",
        status="running" if phase in ("Pending", "ContainerCreating") else "fail",
        detail=f"{phase}" + (f" — {reason}" if reason else ""),
        since=_ago(p.get("status", {}).get("startTime", "")),
    )


# ─── leeftijd / formatting ────────────────────────────────────────────────


def _ago(iso_ts: str) -> str:
    """ISO-timestamp → 'Xm geleden' / 'Xu geleden' / 'Xd geleden'."""
    if not iso_ts:
        return ""
    from datetime import datetime, timezone
    try:
        ts = datetime.fromisoformat(iso_ts.replace("Z", "+00:00"))
    except ValueError:
        return iso_ts
    delta = datetime.now(timezone.utc) - ts
    sec = int(delta.total_seconds())
    if sec < 60:
        return f"{sec}s"
    if sec < 3600:
        return f"{sec // 60}m"
    if sec < 86400:
        return f"{sec // 3600}u"
    return f"{sec // 86400}d"


def _duration_seconds(start_iso: str, end_iso: str = "") -> int:
    """Seconden van start_iso tot end_iso (of nu als end_iso leeg is)."""
    if not start_iso:
        return 0
    from datetime import datetime, timezone
    try:
        start = datetime.fromisoformat(start_iso.replace("Z", "+00:00"))
        end = (
            datetime.fromisoformat(end_iso.replace("Z", "+00:00"))
            if end_iso else datetime.now(timezone.utc)
        )
        return max(0, int((end - start).total_seconds()))
    except ValueError:
        return 0


def _fmt_seconds(sec: int) -> str:
    """Seconden formatteren als Xs / XmYs / XuYm."""
    if sec < 60:
        return f"{sec}s"
    if sec < 3600:
        return f"{sec // 60}m{sec % 60:02d}s"
    return f"{sec // 3600}u{(sec % 3600) // 60:02d}m"


# ─── claude-runner job helpers ────────────────────────────────────────────

_AI_BRANCH_RE = re.compile(r"^ai/(.+)$")
_JIRA_BRANCH_RE = re.compile(r"^ai/([A-Z]+-\d+)$")
_JOB_NAME_RE = re.compile(r"^claude-run-[a-z0-9-]+$")


def _sanitize_story_id(story_id: str) -> str:
    """K8s-safe story-id (zelfde logica als poller.py sanitize_id)."""
    s = story_id.lower()
    s = re.sub(r"[^a-z0-9-]+", "-", s)
    s = re.sub(r"-+", "-", s).strip("-")
    return s[:30] or "job"


def _match_runner_job(jobs: list[dict], pr_num: int, branch: str) -> Optional[dict]:
    """Meest recente claude-runner Job voor deze PR (story- of comment-mode)."""
    m = _AI_BRANCH_RE.match(branch)
    story_id_label = _sanitize_story_id(m.group(1)) if m else None
    candidates = []
    for job in jobs:
        labels = job.get("metadata", {}).get("labels", {}) or {}
        mode = labels.get("mode", "")
        if mode == "story" and story_id_label:
            if labels.get("story-id") == story_id_label:
                candidates.append(job)
        elif mode == "comment":
            if str(labels.get("pr-num", "")) == str(pr_num):
                candidates.append(job)
    if not candidates:
        return None
    candidates.sort(
        key=lambda j: j.get("metadata", {}).get("creationTimestamp", ""),
        reverse=True,
    )
    return candidates[0]


def _runner_info_from_job(job: dict) -> tuple[str, str, str]:
    """Geeft (state, display_text, job_name) voor een claude-runner Job.

    state: 'running' | 'finished' | 'failed'
    """
    job_name = job.get("metadata", {}).get("name", "")
    conds = job.get("status", {}).get("conditions", []) or []
    complete = any(c.get("type") == "Complete" and c.get("status") == "True" for c in conds)
    failed = any(c.get("type") == "Failed" and c.get("status") == "True" for c in conds)
    start_ts = job.get("status", {}).get("startTime", "")
    completion_ts = job.get("status", {}).get("completionTime", "")
    if complete:
        dur = _fmt_seconds(_duration_seconds(start_ts, completion_ts))
        age = _ago(completion_ts) if completion_ts else "?"
        return ("finished", f"✅ finished ({dur}, {age} geleden)", job_name)
    if failed:
        age = _ago(completion_ts or start_ts)
        return ("failed", f"❌ failed ({age} geleden)", job_name)
    dur_sec = _duration_seconds(start_ts)
    return ("running", f"🟢 running ({dur_sec}s)", job_name)


# ─── state-builder ────────────────────────────────────────────────────────


_cache: dict = {"ts": 0.0, "data": None}


def build_state() -> dict:
    """Eén pass over GH + cluster om alle kaartjes te bouwen. Gecached."""
    if _cache["data"] and (time.time() - _cache["ts"]) < CACHE_TTL_SEC:
        return _cache["data"]

    apps = k8s_applications()
    apps_by_name = {a.get("metadata", {}).get("name"): a for a in apps}
    pnf_namespaces = k8s_pnf_namespaces()
    pods_by_ns = {ns: k8s_pods(ns) for ns in [PROD_NS] + pnf_namespaces}
    runner_jobs = k8s_jobs(FACTORY_NS, label_selector="app=claude-runner")

    # Production
    main_commit = gh_main_head_commit() or {}
    main_sha = main_commit.get("sha", "")
    # APK uit de meest recente Release halen.
    apk_url = ""
    apk_filename = ""
    apk_age = ""
    rel = gh_latest_release()
    if rel:
        for asset in rel.get("assets") or []:
            if asset.get("name", "").endswith(".apk"):
                apk_url = asset.get("browser_download_url", "")
                apk_filename = asset.get("name", "")
                apk_age = _ago(asset.get("updated_at", "") or rel.get("published_at", ""))
                break
    main_card = MainCard(
        sha=main_sha,
        sha_short=main_sha[:7] if main_sha else "?",
        message=(main_commit.get("commit", {}).get("message", "").splitlines() or ["—"])[0],
        age=_ago(main_commit.get("commit", {}).get("committer", {}).get("date", "")),
        apk_url=apk_url,
        apk_filename=apk_filename,
        apk_age=apk_age,
        recent_merges=[
            {
                "number": pr["number"],
                "title": pr["title"],
                "html_url": pr["html_url"],
                "age": _ago(pr.get("merged_at", "")),
            }
            for pr in gh_list_recent_closed_prs(5)
        ],
    )

    # Build-images runs voor main
    build_run = gh_latest_run_for_sha("build-images.yml", main_sha) if main_sha else None
    build_jobs = gh_jobs_for_run(build_run["id"]) if build_run else []
    be_job = _find_job(build_jobs, "build-backend")
    fe_job = _find_job(build_jobs, "build-frontend")
    bump_job = _find_job(build_jobs, "bump-manifests")
    s, d, l, since = _job_to_status(be_job)
    main_card.phases.append(Phase("build backend", s, d, l, since))
    s, d, l, since = _job_to_status(fe_job)
    main_card.phases.append(Phase("build frontend", s, d, l, since))
    if bump_job is not None:
        s, d, l, since = _job_to_status(bump_job)
        main_card.phases.append(Phase("bump manifests", s, d, l, since))
    main_card.phases.append(_app_phase(apps_by_name.get(PROD_NS)))
    main_sha7 = main_sha[:7] if main_sha else ""
    main_card.phases.append(
        _pods_phase(pods_by_ns.get(PROD_NS, []), "backend", expected_sha7=main_sha7)
    )
    main_card.phases.append(
        _pods_phase(pods_by_ns.get(PROD_NS, []), "frontend", expected_sha7=main_sha7)
    )

    # PR-cards
    pr_cards: list[PRCard] = []
    for pr in gh_list_open_prs():
        branch = pr.get("head", {}).get("ref", "")
        head_sha = pr.get("head", {}).get("sha", "")
        pr_num = pr["number"]
        card = PRCard(
            number=pr_num,
            title=pr.get("title", ""),
            html_url=pr.get("html_url", ""),
            branch=branch,
            head_sha=head_sha[:7] if head_sha else "?",
            author=pr.get("user", {}).get("login", ""),
            updated_age=_ago(pr.get("updated_at", "")),
            preview_url=PREVIEW_URL_FORMAT.format(pr=pr_num),
        )

        # Fasen per HEAD-SHA fetchen: zo reflecteert elke vinkje de
        # status van DEZE commit. Geen run voor deze SHA = pending (—).
        # Nieuwe commit op de branch reset dus automatisch alle vinkjes
        # tot z'n eigen run start.
        head_sha7 = head_sha[:7] if head_sha else ""

        # validate-pr
        val_run = gh_latest_run_for_sha("validate-pr.yml", head_sha) if head_sha else None
        s, d, l, since = _run_to_status(val_run)
        card.phases.append(Phase("validate-pr", s, d, l, since))

        # build-images jobs (backend + frontend)
        b_run = gh_latest_run_for_sha("build-images.yml", head_sha) if head_sha else None
        b_jobs = gh_jobs_for_run(b_run["id"]) if b_run else []
        s, d, l, since = _job_to_status(_find_job(b_jobs, "build-backend"))
        card.phases.append(Phase("build backend", s, d, l, since))
        s, d, l, since = _job_to_status(_find_job(b_jobs, "build-frontend"))
        card.phases.append(Phase("build frontend", s, d, l, since))

        # ArgoCD-Application zoeken op destination.namespace (de
        # gegenereerde naam bevat een branch_slug en is niet
        # voorspelbaar; de namespace daarentegen is altijd pnf-pr-<N>).
        preview_ns = f"{PREVIEW_NS_PREFIX}{pr_num}"
        card.phases.append(_app_phase(_find_app_by_ns(apps, preview_ns)))

        # Pods in pnf-pr-<N>-namespace; expected_sha7 zorgt dat we de
        # "draait al op nieuwe SHA?"-check kunnen doen.
        preview_pods = pods_by_ns.get(preview_ns, [])
        card.phases.append(_pods_phase(preview_pods, "backend", expected_sha7=head_sha7))
        card.phases.append(_pods_phase(preview_pods, "frontend", expected_sha7=head_sha7))

        # HEAD-commit timestamp ophalen (apart call per PR, gecached).
        commit_data = gh_commit(head_sha)
        if commit_data:
            card.last_commit_age = _ago(
                ((commit_data.get("commit") or {}).get("committer") or {}).get("date", "")
            )

        # Claude runner: meest recente matching Job koppelen.
        runner_job = _match_runner_job(runner_jobs, pr_num, branch)
        if runner_job:
            rstate, rtext, rjob = _runner_info_from_job(runner_job)
            card.runner_state = rstate
            card.runner_text = rtext
            card.runner_job_name = rjob

        pr_cards.append(card)

    # JIRA: één call die alle tracked statussen ophaalt.
    all_tracked = jira_search_tracked()
    issues_by_key = {i.get("key", ""): i for i in all_tracked}

    # Verrijk PR-kaartjes met PR-state-badge + JIRA-status per key.
    for card in pr_cards:
        st, label = _derive_pr_state(card.phases)
        card.pr_state = st
        card.pr_state_label = label
        m = _JIRA_BRANCH_RE.match(card.branch)
        if m:
            issue = issues_by_key.get(m.group(1))
            if issue:
                card.jira_status = (
                    ((issue.get("fields") or {}).get("status") or {}).get("name", "")
                )
                card.jira_status_age = _ago(_jira_last_status_change(issue))
                # AI custom-fields
                fields = issue.get("fields", {}) or {}
                lvl_id = _ai_field_id("level")
                phase_id = _ai_field_id("phase")
                if lvl_id and fields.get(lvl_id) is not None:
                    try:
                        card.ai_level = int(fields[lvl_id])
                    except (TypeError, ValueError):
                        pass
                if phase_id:
                    card.ai_phase = fields.get(phase_id) or ""

    # AI bezig: filter op de active subset.
    jira_cards: list[JIRACard] = []
    active_jobs = k8s_jobs(FACTORY_NS, label_selector="app=claude-runner")
    # Tracked = active + PR-statussen (AI IN REVIEW). Reden: een story die
    # in AI IN REVIEW staat zonder open PR (PR al gemerged, JIRA-transitie
    # naar Done nog niet gebeurd) viel anders uit de stories-tab.
    tracked_for_cards = JIRA_ACTIVE_STATUSES + JIRA_PR_STATUSES
    for issue in all_tracked:
        key = issue.get("key", "")
        fields = issue.get("fields", {}) or {}
        status_name = (fields.get("status") or {}).get("name", "")
        if status_name not in tracked_for_cards:
            continue
        # Accurate "sinds wanneer in deze status": uit de changelog
        # halen. Fallback naar fields.updated (= laatste activiteit op
        # issue, ook door comments — niet ideaal maar beter dan niks).
        status_change = (
            _jira_last_status_change(issue) or fields.get("updated", "")
        )
        card = JIRACard(
            key=key,
            title=fields.get("summary", ""),
            status=status_name,
            jira_url=f"{JIRA_BASE_URL}/browse/{key}" if JIRA_BASE_URL else "",
            age=_ago(status_change),
        )
        # AI custom-fields
        lvl_id = _ai_field_id("level")
        phase_id = _ai_field_id("phase")
        if lvl_id and fields.get(lvl_id) is not None:
            try:
                card.ai_level = int(fields[lvl_id])
            except (TypeError, ValueError):
                pass
        if phase_id:
            card.ai_phase = fields.get(phase_id) or ""
        # Job-lookup: label story-id is de lowercase-kebab key (kan-12).
        story_label = re.sub(r"[^a-z0-9-]+", "-", key.lower()).strip("-")[:30]
        matching = [
            j for j in active_jobs
            if j.get("metadata", {}).get("labels", {}).get("story-id") == story_label
        ]
        # Laatste (jongste) Job pakken op creationTimestamp.
        matching.sort(
            key=lambda j: j.get("metadata", {}).get("creationTimestamp", ""),
            reverse=True,
        )
        if matching:
            j = matching[0]
            card.job_name = j.get("metadata", {}).get("name", "")
            conds = j.get("status", {}).get("conditions", []) or []
            complete = any(
                c.get("type") == "Complete" and c.get("status") == "True" for c in conds
            )
            failed = any(
                c.get("type") == "Failed" and c.get("status") == "True" for c in conds
            )
            active = int(j.get("status", {}).get("active", 0) or 0)
            start_ts = j.get("status", {}).get("startTime", "")
            job_age = _ago(start_ts) if start_ts else "—"
            if complete:
                card.job_status = "pass"
                card.job_state = f"Completed ({job_age} geleden)"
            elif failed:
                card.job_status = "fail"
                card.job_state = f"Failed ({job_age} geleden)"
            elif active > 0:
                card.job_status = "running"
                card.job_state = f"Running ({job_age})"
            else:
                card.job_status = "pending"
                card.job_state = f"Pending"
        elif status_name == "AI IN PROGRESS":
            # IN PROGRESS zonder Job — race-window of pod-cleanup; toon kort.
            card.job_status = "pending"
            card.job_state = "starting…"
        jira_cards.append(card)

    # Factory-DB totalen verrijken — één bulk-query voor alle relevante
    # story-keys. Stories zonder DB-row krijgen 0/0/$0.
    factory_keys: set[str] = set()
    for c in pr_cards:
        m = _JIRA_BRANCH_RE.match(c.branch)
        if m:
            factory_keys.add(m.group(1))
    for c in jira_cards:
        if c.key:
            factory_keys.add(c.key)
    totals = factory_totals_by_story(sorted(factory_keys))
    for c in pr_cards:
        m = _JIRA_BRANCH_RE.match(c.branch)
        if m and m.group(1) in totals:
            t = totals[m.group(1)]
            c.tokens_input = t["input"]
            c.tokens_output = t["output"]
            c.tokens_cache_read = t["cache_read"]
            c.cost_usd = t["cost_usd"]
    for c in jira_cards:
        if c.key in totals:
            t = totals[c.key]
            c.tokens_input = t["input"]
            c.tokens_output = t["output"]
            c.tokens_cache_read = t["cache_read"]
            c.cost_usd = t["cost_usd"]
            c.run_count = t.get("run_count", 0)

    # Sorteer: IN PROGRESS bovenaan, daarna AI Ready, beide op leeftijd.
    status_rank = {s: i for i, s in enumerate(JIRA_ACTIVE_STATUSES[::-1])}
    jira_cards.sort(key=lambda c: (-status_rank.get(c.status, -1), c.age))

    # Recent gemerged (laatste 24u)
    closed_cards: list[ClosedCard] = []
    for pr in gh_list_recent_closed_prs(10):
        branch = (pr.get("head") or {}).get("ref", "")
        m = _JIRA_BRANCH_RE.match(branch)
        story_key = m.group(1) if m else ""
        closed_cards.append(
            ClosedCard(
                number=pr["number"],
                title=pr.get("title", ""),
                html_url=pr.get("html_url", ""),
                merged_age=_ago(pr.get("merged_at", "")),
                branch=branch,
                merged_at=pr.get("merged_at", "") or "",
                head_sha=(pr.get("head") or {}).get("sha", ""),
                story_key=story_key,
            )
        )
    # Factory-DB totalen ophalen voor closed-PR story-keys + verrijken.
    closed_keys = sorted({c.story_key for c in closed_cards if c.story_key})
    if closed_keys:
        closed_totals = factory_totals_by_story(closed_keys)
        for c in closed_cards:
            if c.story_key in closed_totals:
                t = closed_totals[c.story_key]
                c.tokens_input = t["input"]
                c.tokens_output = t["output"]
                c.tokens_cache_read = t["cache_read"]
                c.cost_usd = t["cost_usd"]
                c.run_count = t.get("run_count", 0)

    state = {
        "main": main_card,
        "ai_active": jira_cards,
        "open_prs": pr_cards,
        "closed_prs": closed_cards[:10],
        "fetched_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    _cache["data"] = state
    _cache["ts"] = time.time()
    return state


# ─── HTML-render ──────────────────────────────────────────────────────────


STATUS_ICONS = {
    "pass": "✅",
    "running": "⏳",
    "fail": "❌",
    "pending": "—",
    "unknown": "?",
}


CSS = """
* { box-sizing: border-box; }
body {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  margin: 0; padding: 12px;
  background: #0f1419; color: #e4e6eb;
  max-width: 900px; margin-inline: auto;
  font-size: 14px; line-height: 1.45;
}
h1 { font-size: 18px; margin: 0 0 4px 0; }
.sub { color: #8b96a8; font-size: 12px; margin-bottom: 16px; }
.card {
  background: #1a2029; border: 1px solid #2c3340;
  border-radius: 10px; padding: 14px; margin-bottom: 12px;
}
.card.prod { border-left: 4px solid #4ade80; }
.card.pr   { border-left: 4px solid #fbbf24; }
.card.jira { border-left: 4px solid #a78bfa; padding: 10px 14px; }
.title { font-weight: 600; font-size: 15px; margin-bottom: 4px; }
.title a { color: #93c5fd; text-decoration: none; }
.title a:hover { text-decoration: underline; }
.meta { font-size: 12px; color: #8b96a8; margin-bottom: 10px; }
.meta a { color: #93c5fd; text-decoration: none; }
.phase, .info-row {
  display: flex; align-items: center; gap: 8px;
  padding: 4px 0; font-size: 13px;
}
.phase .icon { font-size: 14px; min-width: 18px; text-align: center; }
.phase .label, .info-row .label { min-width: 130px; color: #cbd5e1; }
.phase .detail, .info-row .detail { color: #8b96a8; font-size: 12px; }
.info-row .label { padding-left: 26px; color: #94a3b8; }
.phase.fail .detail { color: #fca5a5; }
.phase a, .info-row a { color: #93c5fd; text-decoration: none; }
.since { color: #64748b; font-size: 11px; }
.info-row + .phase, .badges + .info-row, .info-row + .info-row { border-top: 1px dotted transparent; }
.preview, .apk {
  display: inline-block; margin-top: 8px; padding: 6px 10px;
  background: #1e3a5f; border-radius: 6px;
  font-size: 12px; color: #bfdbfe; text-decoration: none;
}
.preview:hover, .apk:hover { background: #2c4d7a; }
.apk { background: #1e4d3a; color: #bbf7d0; margin-right: 8px; }
.apk:hover { background: #2c6a4d; }
.badges { display: flex; flex-wrap: wrap; gap: 6px; margin: 4px 0 8px 0; font-size: 11px; }
.badge {
  display: inline-block; padding: 2px 8px; border-radius: 10px;
  background: #2c3340; color: #cbd5e1;
}
.badge.ready    { background: #14532d; color: #bbf7d0; }
.badge.building { background: #713f12; color: #fde68a; }
.badge.failed   { background: #7f1d1d; color: #fecaca; }
.badge.pending  { background: #1e3a5f; color: #bfdbfe; }
.badge.jira     { background: #4c1d95; color: #ddd6fe; }
.merges { font-size: 12px; color: #8b96a8; margin-top: 10px; padding-top: 10px; border-top: 1px solid #2c3340; }
.merges a { color: #93c5fd; text-decoration: none; padding-right: 8px; }
.closed { font-size: 12px; padding: 4px 0; }
.closed a { color: #93c5fd; text-decoration: none; }
.closed-list { background: #1a2029; border: 1px solid #2c3340; border-radius: 10px; padding: 10px 14px; }
.empty { color: #8b96a8; font-style: italic; padding: 20px 0; text-align: center; }
/* AI-fields visualisatie (Fase 2 PR 3) */
.lvl { display: inline-block; background: #2c3340; color: #93c5fd;
       padding: 2px 8px; border-radius: 10px; font-size: 11px;
       margin-left: 6px; font-weight: bold; }
.phase-pill { display: inline-block; padding: 2px 8px; border-radius: 10px;
              font-size: 11px; margin-left: 6px; }
.phase-pill.active   { background: #1e3a8a; color: #bfdbfe; }
.phase-pill.done     { background: #065f46; color: #a7f3d0; }
.phase-pill.awaiting { background: #713f12; color: #fde68a; }
.pipeline { display: flex; gap: 4px; margin: 6px 0; align-items: center;
            font-size: 11px; }
.step { padding: 3px 10px; border-radius: 14px; border: 1px solid #2c3340;
        background: #1a2029; color: #8b96a8; }
.step.active   { background: #1e3a8a; color: #dbeafe; border-color: #1e3a8a; }
.step.done     { background: #065f46; color: #a7f3d0; border-color: #065f46; }
.step.failed   { background: #7f1d1d; color: #fecaca; border-color: #7f1d1d; }
.step-sep { color: #2c3340; }
.handover-banner { margin: 12px 0 20px; }
.handover-banner-grid { display: grid; gap: 10px;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); }
.handover-banner-card { background: #052e16; border: 1px solid #065f46;
  border-radius: 8px; padding: 12px 14px; }
.handover-banner-card .hb-title { color: #a7f3d0; font-weight: 600;
  font-size: 14px; margin-bottom: 4px; }
.handover-banner-card .hb-meta { color: #6f8a7f; font-size: 12px;
  margin-bottom: 8px; }
.handover-banner-card .hb-cta { display: inline-block;
  color: #4ade80; text-decoration: none; font-weight: 600; font-size: 13px; }
.handover-banner-card .hb-cta:hover { text-decoration: underline; }
.card-actions { display: flex; gap: 8px; margin-top: 10px;
                padding-top: 10px; border-top: 1px solid #2c3340; }
.card-btn { display: inline-block; padding: 6px 14px; font-size: 13px;
            font-weight: 600; border-radius: 6px;
            background: #2c3340; color: #e4e6eb; text-decoration: none;
            border: 1px solid #3a414f; }
.card-btn:hover { background: #3a414f; }
.card-btn.primary { background: #1e3a8a; color: #dbeafe;
                    border-color: #1e3a8a; }
.card-btn.primary:hover { background: #1e40af; }
"""


# Mapping: phase-value → (stage-step actief, stages die "done" zijn).
# Stages-volgorde: refine, develop, review, test.
_PIPELINE_STAGES = ["refine", "develop", "review", "test"]
_PHASE_TO_STAGE = {
    "refining":         ("refine",  "active"),
    "refined":          ("refine",  "done"),
    "developing":       ("develop", "active"),
    "developed":        ("develop", "done"),
    "reviewing":        ("review",  "active"),
    "reviewed-ok":      ("review",  "done"),
    "reviewed-changes": ("review",  "done"),   # leiding terug naar dev — laat het als done staan, dev wordt opnieuw active als 'ie opnieuw start
    "testing":          ("test",    "active"),
    "tested-ok":        ("test",    "done"),
    "tested-fail":      ("test",    "failed"),
    "awaiting-po":      (None,      None),     # geen stage-info; phase-pill toont 't apart
}


def _render_pipeline_bar(phase: str) -> str:
    """Render een ◯─◯─◯─◯-balk waar de juiste stage gecolored is.

    De pipeline is altijd alle 4 zichtbaar, ook al hebben we nog geen
    reviewer/tester-agents — dat maakt 'm voorspelbaar leesbaar."""
    if not phase or phase == "awaiting-po":
        # Geen phase = geen invulling; toon de balk wel zodat de PR-card
        # niet inconsistent springt qua hoogte.
        active_stage, state = None, None
    else:
        active_stage, state = _PHASE_TO_STAGE.get(phase, (None, None))

    active_idx = (
        _PIPELINE_STAGES.index(active_stage) if active_stage in _PIPELINE_STAGES else -1
    )
    parts: list[str] = []
    for i, stage in enumerate(_PIPELINE_STAGES):
        cls = ""
        if active_idx >= 0:
            if i < active_idx:
                cls = "done"
            elif i == active_idx:
                cls = state or "active"
        parts.append(f'<span class="step {cls}">{stage}</span>')
    return '<div class="pipeline">' + '<span class="step-sep">─</span>'.join(parts) + '</div>'


def _render_phase_pill(phase: str) -> str:
    if not phase:
        return ""
    if phase == "awaiting-po":
        cls = "awaiting"
    elif phase in ("refined", "developed", "reviewed-ok",
                   "reviewed-changes", "tested-ok", "tested-fail"):
        cls = "done"
    else:
        cls = "active"
    return f'<span class="phase-pill {cls}">phase: {escape(phase)}</span>'


def _render_level_badge(ai_level: int) -> str:
    if ai_level < 0:
        return ""
    return f'<span class="lvl">L{ai_level}</span>'


def _render_runner_row(card: PRCard) -> str:
    if card.runner_state == "none":
        return (
            '<div class="info-row">'
            '<span class="label">claude runner</span>'
            '<span class="detail">—</span>'
            "</div>"
        )
    log_link = f' <a href="/runner/{escape(card.runner_job_name)}/log">Log →</a>'
    return (
        f'<div class="info-row">'
        f'<span class="label">claude runner</span>'
        f'<span class="detail">{escape(card.runner_text)}{log_link}</span>'
        f"</div>"
    )


def _fmt_tokens(n: int) -> str:
    """1234 -> '1.2K'; 123 -> '123'."""
    if n >= 1000:
        return f"{n / 1000:.1f}K"
    return str(n)


def _render_factory_row(story_key: str, card) -> str:
    """Tokens + cost-rij voor een PR-card of JIRA-card. Toont niets als
    er geen DB-data is voor deze story."""
    if not story_key or (card.tokens_input == 0 and card.tokens_output == 0
                         and card.tokens_cache_read == 0 and card.cost_usd == 0):
        return ""
    detail = (
        f"{_fmt_tokens(card.tokens_input)} in / "
        f"{_fmt_tokens(card.tokens_output)} out · "
        f"cache-read {_fmt_tokens(card.tokens_cache_read)} · "
        f"≈ ${card.cost_usd:.4f}"
    )
    timeline_link = f' <a href="/story/{escape(story_key)}">Details →</a>'
    return (
        f'<div class="info-row">'
        f'<span class="label">factory</span>'
        f'<span class="detail">{escape(detail)}{timeline_link}</span>'
        f"</div>"
    )


_LOG_CSS = """
* { box-sizing: border-box; }
body {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  margin: 0; padding: 16px;
  background: #0f1419; color: #e4e6eb;
  max-width: 1200px; margin-inline: auto;
  font-size: 14px; line-height: 1.45;
}
h1 { font-size: 18px; margin: 0 0 4px; }
h2 { font-size: 15px; margin: 16px 0 8px; }
a { color: #93c5fd; text-decoration: none; }
a:hover { text-decoration: underline; }
.meta { color: #8b96a8; font-size: 12px; margin-bottom: 16px; }
.notice { color: #fde68a; background: #713f12; padding: 12px; border-radius: 8px; }
pre {
  background: #1a2029; border: 1px solid #2c3340; border-radius: 8px;
  padding: 12px; overflow-x: auto;
  font-family: "Fira Mono", "Consolas", monospace; font-size: 12px;
  line-height: 1.5; color: #cbd5e1; white-space: pre-wrap; word-break: break-word;
}
"""


def _render_log_page(
    job_name: str,
    status_text: str,
    pr_title: str,
    pr_url: str,
    log_text: Optional[str],
    pod_gone: bool,
    is_running: bool,
    log_status: int = 200,
) -> str:
    refresh = '<meta http-equiv="refresh" content="5">' if is_running else ""
    pr_html = ""
    if pr_title and pr_url:
        pr_html = f'<p><a href="{escape(pr_url)}" target="_blank">↗ PR: {escape(pr_title)}</a></p>'
    elif pr_title:
        pr_html = f"<p>{escape(pr_title)}</p>"
    if pod_gone:
        content = (
            '<div class="notice">Pod is inmiddels opgeruimd (&gt;2u geleden voltooid).'
            " De log is niet meer beschikbaar.</div>"
        )
    elif log_text is None:
        # Specifieke hint per HTTP-status — anders staart de gebruiker
        # naar "Log kon niet worden opgehaald" zonder aanknopingspunt.
        if log_status == 403:
            hint = (
                "HTTP 403 Forbidden — de status-dashboard ServiceAccount "
                "mist <code>pods/log</code>-rechten. Fix: <code>oc apply -f "
                "deploy/status-dashboard/rbac.yaml</code>."
            )
        elif log_status == 404:
            hint = (
                "HTTP 404 Not Found — pod bestaat niet (meer). Vermoedelijk "
                "tussen pod-lookup en log-fetch opgeruimd."
            )
        elif log_status == 400:
            hint = (
                "HTTP 400 — pod accepteert geen log-request (waitContainer, "
                "init-container, of meerdere containers zonder explicit name)."
            )
        elif log_status == 0:
            hint = (
                "In-cluster K8s-API onbereikbaar — ServiceAccount-token mist "
                "of netwerkprobleem."
            )
        else:
            hint = f"HTTP {log_status}."
        content = (
            '<div class="notice">Log kon niet worden opgehaald. '
            f"{hint}</div>"
        )
    else:
        content = f"<pre>{escape(log_text)}</pre>"
    return f"""<!doctype html>
<html lang="nl">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  {refresh}
  <title>Log — {escape(job_name)}</title>
  <style>{_LOG_CSS}</style>
</head>
<body>
  <h1>Claude Runner Log</h1>
  {pr_html}
  <div class="meta">Job: <code>{escape(job_name)}</code> · {escape(status_text)}</div>
  <p><a href="/">← terug naar dashboard</a></p>
  <h2>Loguitvoer (laatste 2000 regels)</h2>
  {content}
</body>
</html>"""


def _format_events_as_pretty_log(events: list[dict]) -> str:
    """Reconstrueer de jq-prettyprint-stijl van runner.sh uit DB-events.

    Format moet ongeveer overeenkomen met wat in de pod-log staat —
    voor consistentie tussen live en archief-views.
    """
    def trim(s: str, n: int = 220) -> str:
        s = "" if s is None else str(s)
        return s[:n] + "…" if len(s) > n else s

    lines: list[str] = []
    for e in events:
        kind = e.get("kind", "unknown")
        payload = e.get("payload") or {}
        if not isinstance(payload, dict):
            lines.append(f"· {kind}")
            continue
        try:
            if kind == "system" and payload.get("subtype") == "init":
                lines.append(
                    f"🛠  init session={payload.get('session_id', '?')[:8]}… "
                    f"model={payload.get('model', '?')}"
                )
            elif kind == "assistant":
                content = (payload.get("message") or {}).get("content") or []
                for c in content:
                    ct = c.get("type")
                    if ct == "text":
                        text = (c.get("text") or "").strip()
                        if text:
                            lines.append(f"💬 {trim(text, 800)}")
                    elif ct == "tool_use":
                        inp = c.get("input") or {}
                        lines.append(f"→ {c.get('name', '?')} {trim(json.dumps(inp, default=str), 220)}")
                    else:
                        lines.append(f"· a.{ct}")
            elif kind == "user":
                content = (payload.get("message") or {}).get("content") or []
                for c in content:
                    if c.get("type") == "tool_result":
                        inner = c.get("content") or ""
                        if isinstance(inner, list):
                            inner = "".join((x.get("text") or "") for x in inner if isinstance(x, dict))
                        lines.append(f"← {trim(str(inner), 220)}")
                    else:
                        lines.append(f"· u.{c.get('type', '?')}")
            elif kind == "result":
                usage = payload.get("usage") or {}
                dur = int((payload.get("duration_ms") or 0) // 1000)
                lines.append(
                    f"✅ done {dur}s "
                    f"in={usage.get('input_tokens', 0)} out={usage.get('output_tokens', 0)} "
                    f"cost=${payload.get('total_cost_usd', 0):.4f}"
                )
            elif kind == "raw":
                lines.append(f"(raw) {trim(payload.get('text', ''), 200)}")
            else:
                lines.append(f"· {kind}")
        except Exception as ex:
            lines.append(f"· {kind} (format error: {ex})")
    return "\n".join(lines)


def _render_story_missing(key: str) -> str:
    return f"""<!doctype html>
<html lang="nl">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Story — {escape(key)}</title>
  <style>{_LOG_CSS}</style>
</head>
<body>
  <h1>Story {escape(key)}</h1>
  <p><a href="/">← terug naar dashboard</a></p>
  <div class="notice">Geen factory-data voor deze story. Mogelijke oorzaken:
    de story is nooit door een runner verwerkt (vóór Fase 1), de DB is
    onbereikbaar, of de story-key klopt niet.</div>
</body>
</html>"""


def _fmt_ts(ts) -> str:
    """datetime → 'YYYY-MM-DD HH:MM:SS UTC' of '—'."""
    if ts is None:
        return "—"
    return ts.strftime("%Y-%m-%d %H:%M:%S UTC")


def _render_stories_index(rows: list[dict]) -> str:
    """Tabel-overzicht van alle stories uit factory.story_runs."""
    if not rows:
        body_html = (
            '<p class="notice">Geen factory-data. Mogelijke oorzaken: DB '
            'leeg (nog geen story door de pipeline gegaan), of '
            'FACTORY_DATABASE_URL is niet geconfigureerd.</p>'
        )
    else:
        rows_html = []
        for r in rows:
            key = r["story_key"]
            dur_s = (r["duration_ms_sum"] or 0) // 1000
            wallclock_s = 0
            if r["started_at"] and r["ended_at"]:
                wallclock_s = int((r["ended_at"] - r["started_at"]).total_seconds())
            status_icon = (
                "✅" if (r["final_status"] or "").lower() in ("klaar", "gereed", "done")
                else "🟡" if not r["ended_at"]
                else "—"
            )
            rows_html.append(f"""
            <tr>
              <td>{status_icon}</td>
              <td><a href="/story/{escape(key)}"><strong>{escape(key)}</strong></a></td>
              <td>{escape(_fmt_ts(r["started_at"]))}</td>
              <td>{escape(r["final_status"] or "lopend")}</td>
              <td style="text-align:right">{r["run_count"]}</td>
              <td style="text-align:right">{escape(_fmt_seconds(dur_s))}</td>
              <td style="text-align:right">{escape(_fmt_seconds(wallclock_s)) if wallclock_s else "—"}</td>
              <td style="text-align:right">{_fmt_tokens(r["input"])}</td>
              <td style="text-align:right">{_fmt_tokens(r["output"])}</td>
              <td style="text-align:right">{_fmt_tokens(r["cache_read"])}</td>
              <td style="text-align:right">${r["cost_usd"]:.4f}</td>
              <td>
                <a href="/story/{escape(key)}">details →</a>
                · <a href="/story/{escape(key)}/handover">briefing →</a>
              </td>
            </tr>""")

        # Totaalrij
        sum_runs   = sum(r["run_count"] for r in rows)
        sum_dur    = sum((r["duration_ms_sum"] or 0) // 1000 for r in rows)
        sum_input  = sum(r["input"] for r in rows)
        sum_output = sum(r["output"] for r in rows)
        sum_cache  = sum(r["cache_read"] for r in rows)
        sum_cost   = sum(r["cost_usd"] for r in rows)
        totals_html = f"""
        <tfoot>
          <tr class="totals-row">
            <td></td><td><strong>Totaal ({len(rows)} stories)</strong></td>
            <td></td><td></td>
            <td style="text-align:right"><strong>{sum_runs}</strong></td>
            <td style="text-align:right"><strong>{escape(_fmt_seconds(sum_dur))}</strong></td>
            <td></td>
            <td style="text-align:right"><strong>{_fmt_tokens(sum_input)}</strong></td>
            <td style="text-align:right"><strong>{_fmt_tokens(sum_output)}</strong></td>
            <td style="text-align:right"><strong>{_fmt_tokens(sum_cache)}</strong></td>
            <td style="text-align:right"><strong>${sum_cost:.4f}</strong></td>
            <td></td>
          </tr>
        </tfoot>"""

        body_html = f"""
        <table>
          <thead>
            <tr>
              <th></th><th>Story</th><th>Gestart</th><th>Status</th>
              <th style="text-align:right">Agents</th>
              <th style="text-align:right">Agent-tijd</th>
              <th style="text-align:right">Wallclock</th>
              <th style="text-align:right">In</th>
              <th style="text-align:right">Out</th>
              <th style="text-align:right">Cache-r</th>
              <th style="text-align:right">Cost</th>
              <th>Links</th>
            </tr>
          </thead>
          <tbody>{''.join(rows_html)}</tbody>
          {totals_html}
        </table>"""

    return f"""<!doctype html>
<html lang="nl">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Stories — overzicht</title>
  <style>{_LOG_CSS}
    table {{ border-collapse: collapse; width: 100%; margin: 8px 0; }}
    th, td {{ padding: 6px 10px; text-align: left;
              border-bottom: 1px solid #2c3340; font-size: 13px; }}
    th {{ background: #1a2029; color: #8b96a8; font-weight: normal; }}
    tbody tr:hover {{ background: #1f2530; }}
    .totals-row td {{ background: #1a2029; border-top: 2px solid #3a414f;
                       border-bottom: none; }}
    .notice {{ color: #8b96a8; padding: 12px; background: #1a2029;
               border-radius: 6px; }}
  </style>
</head>
<body>
  <h1>Stories — overzicht ({len(rows)})</h1>
  <p><a href="/">← terug naar dashboard</a></p>
  {body_html}
</body>
</html>"""


def _render_story_page(
    data: dict,
    jira_title: str = "",
    prs: Optional[list[dict]] = None,
    commits: Optional[list[dict]] = None,
) -> str:
    key = data["story_key"]
    t = data["totals"]
    runs = data["runs"]
    final = data.get("final_status") or "lopend"
    duration_total = sum(r.get("duration_ms", 0) or 0 for r in runs)

    rows_html = []
    for r in runs:
        icon = "✅" if r["outcome"] == "success" else ("❌" if "fail" in r["outcome"] else "🟡")
        dur = _fmt_seconds((r["duration_ms"] or 0) // 1000) if r["duration_ms"] else "—"
        log_link = (
            f'<a href="/runner/{escape(r["job_name"])}/log">Log →</a>'
            if r["job_name"] else "—"
        )
        rows_html.append(f"""
        <tr>
          <td>{icon}</td>
          <td><code>{escape(r["role"])}</code></td>
          <td>{escape(_fmt_ts(r["started_at"]))}</td>
          <td>{escape(dur)}</td>
          <td>{r["num_turns"]}</td>
          <td style="text-align:right">{_fmt_tokens(r["input"])}</td>
          <td style="text-align:right">{_fmt_tokens(r["output"])}</td>
          <td style="text-align:right">{_fmt_tokens(r["cache_read"])}</td>
          <td style="text-align:right">${r["cost_usd"]:.4f}</td>
          <td>{log_link}</td>
        </tr>""")

    # External links: JIRA + PR's + commits.
    jira_url = f"{JIRA_BASE_URL}/browse/{key}" if JIRA_BASE_URL else ""
    branch = f"ai/{key}"
    branch_url = (
        f"https://github.com/{GITHUB_OWNER}/{GITHUB_REPO}/tree/{branch}"
        if GITHUB_OWNER and GITHUB_REPO else ""
    )

    links_html = '<div class="links-card">'
    title_text = jira_title or "(geen titel opgehaald)"
    links_html += f'<div class="links-title">{escape(title_text)}</div>'
    links_html += '<div class="links-row">'
    if jira_url:
        links_html += f'<a href="{escape(jira_url)}" target="_blank">JIRA-ticket →</a>'
    if branch_url:
        links_html += f'<a href="{escape(branch_url)}" target="_blank">Branch <code>{escape(branch)}</code> →</a>'
    links_html += f'<a href="/story/{escape(key)}/handover">Briefing →</a>'
    links_html += '</div>'
    links_html += '</div>'

    # Commando-knoppen — schrijven een @claude:command:<cmd> comment naar
    # JIRA; de poller voert 't binnen één tick (30s) uit. Delete + re-
    # implement zijn destructief, dus die hebben een JS-confirm.
    def _btn(cmd: str, label: str, danger: bool, confirm: bool) -> str:
        confirm_attr = ""
        if confirm:
            msg = f"Weet je zeker dat je &apos;{cmd}&apos; wilt uitvoeren op {key}? Dit is niet ongedaan te maken."
            confirm_attr = f' onsubmit="return confirm(\'{msg}\');"'
        klass = "cmd-btn danger" if danger else "cmd-btn"
        return (
            f'<form method="POST" action="/story/{escape(key)}/cmd/{cmd}" '
            f'style="display:inline"{confirm_attr}>'
            f'<button class="{klass}" type="submit">{escape(label)}</button>'
            f'</form>'
        )

    cmd_banner = ""
    flash_cmd = request.args.get("cmd") if request else None
    if flash_cmd:
        cmd_banner = (
            f'<div class="cmd-flash">Commando &apos;{escape(flash_cmd)}&apos; '
            f'gepost naar JIRA — poller pakt het op in de volgende tick.</div>'
        )
    cmds_html = (
        '<div class="commands-card">'
        f'{cmd_banner}'
        '<div class="commands-title">@claude:command — handmatige acties</div>'
        f'{_btn("pause", "⏸ Pause", danger=False, confirm=False)} '
        f'{_btn("merge", "✓ Merge (squash)", danger=False, confirm=True)} '
        f'{_btn("delete", "🗑 Delete (cancel)", danger=True, confirm=True)} '
        f'{_btn("re-implement", "↻ Re-implement", danger=True, confirm=True)}'
        '<div class="commands-hint">'
        'Je kunt deze ook in JIRA als comment posten: '
        '<code>@claude:command:&lt;cmd&gt; - optionele uitleg</code>'
        '</div>'
        '</div>'
    )

    # PR's
    prs = prs or []
    if prs:
        pr_rows = []
        for p in prs:
            num = p.get("number")
            title = p.get("title") or ""
            state = p.get("state") or ""
            merged_at = p.get("merged_at")
            state_text = "merged" if merged_at else state
            pr_url = p.get("html_url") or ""
            preview_url = PREVIEW_URL_FORMAT.replace("{pr}", str(num))
            pr_rows.append(
                f'<tr>'
                f'<td><a href="{escape(pr_url)}" target="_blank">#{num}</a></td>'
                f'<td>{escape(title)}</td>'
                f'<td>{escape(state_text)}</td>'
                f'<td><a href="{escape(preview_url)}" target="_blank">preview →</a></td>'
                f'</tr>'
            )
        prs_section = (
            f'<h2>PR\'s ({len(prs)})</h2>'
            f'<table><thead><tr><th>#</th><th>Title</th><th>State</th><th>Preview</th></tr></thead>'
            f'<tbody>{"".join(pr_rows)}</tbody></table>'
        )
    else:
        prs_section = ""

    # Commits
    commits = commits or []
    if commits:
        commit_rows = []
        for c in commits[:30]:
            sha = (c.get("sha") or "")[:7]
            url = c.get("html_url") or ""
            msg = ((c.get("commit") or {}).get("message") or "").splitlines()[0]
            author = ((c.get("commit") or {}).get("author") or {}).get("name") or ""
            when = ((c.get("commit") or {}).get("author") or {}).get("date") or ""
            commit_rows.append(
                f'<tr>'
                f'<td><a href="{escape(url)}" target="_blank"><code>{escape(sha)}</code></a></td>'
                f'<td>{escape(msg)}</td>'
                f'<td>{escape(author)}</td>'
                f'<td>{escape(when[:19].replace("T", " "))}</td>'
                f'</tr>'
            )
        commits_section = (
            f'<h2>Commits op <code>{escape(branch)}</code> ({len(commits)})</h2>'
            f'<table><thead><tr><th>SHA</th><th>Message</th><th>Author</th><th>When</th></tr></thead>'
            f'<tbody>{"".join(commit_rows)}</tbody></table>'
        )
    else:
        commits_section = ""

    return f"""<!doctype html>
<html lang="nl">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Story — {escape(key)}</title>
  <style>{_LOG_CSS}
    table {{ border-collapse: collapse; width: 100%; margin: 8px 0; }}
    th, td {{ padding: 6px 10px; text-align: left;
              border-bottom: 1px solid #2c3340; font-size: 13px;
              vertical-align: top; }}
    th {{ background: #1a2029; color: #8b96a8; font-weight: normal; }}
    tbody tr:hover {{ background: #1f2530; }}
    .totals {{ background: #1a2029; border: 1px solid #2c3340;
              border-radius: 8px; padding: 10px 14px; font-size: 13px; }}
    .totals strong {{ color: #e4e6eb; }}
    .links-card {{ background: #1a2029; border: 1px solid #2c3340;
                   border-radius: 8px; padding: 12px 16px; margin: 12px 0; }}
    .links-card .links-title {{ font-size: 16px; color: #e4e6eb;
                                margin-bottom: 6px; }}
    .links-card .links-row {{ display: flex; gap: 16px; flex-wrap: wrap;
                              font-size: 14px; }}
    .links-card a {{ color: #4a9eff; text-decoration: none; }}
    .links-card a:hover {{ text-decoration: underline; }}
    .commands-card {{ background: #1a2029; border: 1px solid #2c3340;
                      border-radius: 8px; padding: 12px 16px; margin: 12px 0; }}
    .commands-title {{ font-size: 14px; color: #8b96a8; margin-bottom: 8px; }}
    .cmd-btn {{ background: #2c3340; color: #e4e6eb;
                border: 1px solid #3a414f; border-radius: 6px;
                padding: 6px 12px; font-size: 13px; cursor: pointer;
                margin-right: 4px; }}
    .cmd-btn:hover {{ background: #3a414f; }}
    .cmd-btn.danger {{ background: #3a1a1a; border-color: #7f1d1d;
                       color: #fecaca; }}
    .cmd-btn.danger:hover {{ background: #7f1d1d; }}
    .commands-hint {{ font-size: 12px; color: #6f7a8a; margin-top: 8px; }}
    .commands-hint code {{ background: #11151c; padding: 2px 5px;
                            border-radius: 3px; }}
    .cmd-flash {{ background: #052e16; color: #a7f3d0;
                  border: 1px solid #065f46; border-radius: 6px;
                  padding: 8px 12px; margin-bottom: 10px; font-size: 13px; }}
    table.overview-mini {{ width: auto; min-width: 320px; max-width: 600px;
                            margin: 12px 0; border-collapse: collapse;
                            background: #1a2029; border: 1px solid #2c3340;
                            border-radius: 8px; overflow: hidden; }}
    table.overview-mini th {{ text-align: left; padding: 6px 12px;
                               background: #11151c; color: #8b96a8;
                               font-weight: normal; font-size: 12px;
                               width: 180px; }}
    table.overview-mini td {{ padding: 6px 12px; font-size: 13px;
                               color: #e4e6eb; }}
    table.overview-mini tr + tr th, table.overview-mini tr + tr td {{
        border-top: 1px solid #2c3340; }}
  </style>
</head>
<body>
  <h1>Story {escape(key)}</h1>
  <p><a href="/">← dashboard</a> · <a href="/stories">alle stories →</a></p>
  {links_html}
  {cmds_html}

  <table class="overview-mini">
    <tr><th>Gestart</th><td>{escape(_fmt_ts(data["started_at"]))}</td></tr>
    <tr><th>Geëindigd</th><td>{escape(_fmt_ts(data.get("ended_at")))}</td></tr>
    <tr><th>Final status</th><td><strong>{escape(final)}</strong></td></tr>
    <tr><th>Aantal agent-runs</th><td>{len(runs)}</td></tr>
    <tr><th>Agent-tijd (CPU)</th><td>{escape(_fmt_seconds(duration_total // 1000))}</td></tr>
    <tr><th>Wallclock</th><td>{escape(_fmt_seconds(int((data["ended_at"] - data["started_at"]).total_seconds())) if data.get("ended_at") and data.get("started_at") else "—")}</td></tr>
    <tr><th>Input tokens</th><td>{_fmt_tokens(t["input"])}</td></tr>
    <tr><th>Output tokens</th><td>{_fmt_tokens(t["output"])}</td></tr>
    <tr><th>Cache-read tokens</th><td>{_fmt_tokens(t["cache_read"])}</td></tr>
    <tr><th>Cache-creation tokens</th><td>{_fmt_tokens(t["cache_creation"])}</td></tr>
    <tr><th>Geschatte kosten</th><td><strong>${t["cost_usd"]:.4f}</strong></td></tr>
  </table>

  <h2>Agent-runs ({len(runs)})</h2>
  <table>
    <thead>
      <tr>
        <th></th><th>Rol</th><th>Gestart</th><th>Duur</th><th>Turns</th>
        <th style="text-align:right">In</th>
        <th style="text-align:right">Out</th>
        <th style="text-align:right">Cache-r</th>
        <th style="text-align:right">Cost</th>
        <th>Log</th>
      </tr>
    </thead>
    <tbody>
      {''.join(rows_html) if rows_html else '<tr><td colspan="10">Geen agent-runs.</td></tr>'}
    </tbody>
  </table>

  {prs_section}
  {commits_section}
</body>
</html>"""


# ─── Handover-pagina ──────────────────────────────────────────────────────
#
# Briefing voor de menselijke reviewer/tester aan het einde van de
# pipeline. Aggregeert per agent-rol de eind-samenvatting uit
# `factory.agent_runs.summary_text`, parsed naar sub-secties (Aannames,
# Gedaan, Niet gedaan, …). Bedoeld om in één blik te kunnen beoordelen
# of een PR klaar is om te mergen.

# Sectie-koppen die de agents moeten gebruiken in hun summary_text.
# Gespeld zoals in de system-prompts (runner.sh).
_REFINER_HEADING_ASSUMPTIONS = "Aannames:"
_DEVELOPER_HEADING_PROSE = "Samenvatting:"
_DEVELOPER_HEADING_DONE = "Gedaan:"
_DEVELOPER_HEADING_SKIPPED = "Niet gedaan / aangepast:"
_REVIEWER_HEADING_PROSE = "Samenvatting:"
_REVIEWER_HEADING_FINDINGS = "Bevindingen:"
_REVIEWER_HEADING_VERDICT = "Verdict:"
_TESTER_HEADING_PROSE = "Samenvatting:"
_TESTER_HEADING_STEPS = "Stappenrapport:"
_TESTER_HEADING_FINDINGS = "Bevindingen:"
_TESTER_HEADING_FOR_HUMAN = "Opvallend voor mens — handmatig checken:"
# Legacy-koppen voor backwards-compat met testers die vóór deze PR
# liepen. Worden ge-parsed als ze er staan, anders genegeerd.
_TESTER_HEADING_TESTED_LEGACY = "Getest:"
_TESTER_HEADING_RESULTS_LEGACY = "Resultaat per test:"
_TESTER_HEADING_FOR_HUMAN_LEGACY = "Opvallend voor mens:"


def _strip_refiner_json_line(text: str) -> str:
    """Verwijder de outcome-JSON-regel uit een refiner-summary."""
    # Match `{"phase": …}` op een eigen regel (one-liner of compact).
    return re.sub(
        r"\n?\s*\{\s*\"phase\".*?\}\s*$",
        "",
        text,
        flags=re.DOTALL,
    ).strip()


def _split_sections(text: str, headings: list[str]) -> dict[str, str]:
    """Splits `text` op de gegeven koppen (in volgorde van voorkomen).

    Returnt {heading: section_body} plus een speciale key '__intro' voor
    alles vóór de eerste kop. Een afwezige kop ontbreekt simpelweg.
    Vergelijking is case-sensitive en moet aan begin van een regel staan.
    """
    if not text:
        return {}
    # Vind posities van alle koppen.
    hits: list[tuple[int, str]] = []
    for h in headings:
        for m in re.finditer(rf"^[ \t]*{re.escape(h)}[ \t]*$", text, flags=re.MULTILINE):
            hits.append((m.start(), h))
    hits.sort()
    if not hits:
        return {"__intro": text.strip()}
    out: dict[str, str] = {}
    intro = text[: hits[0][0]].strip()
    if intro:
        out["__intro"] = intro
    for i, (pos, h) in enumerate(hits):
        body_start = pos + len(h)
        # Sla over de regel zelf (whitespace + newline).
        nl = text.find("\n", body_start)
        if nl >= 0:
            body_start = nl + 1
        body_end = hits[i + 1][0] if i + 1 < len(hits) else len(text)
        out[h] = text[body_start:body_end].strip()
    return out


def _bullets_to_html(body: str) -> str:
    """'- foo\\n- bar' → '<ul><li>foo</li><li>bar</li></ul>', prose-fallback."""
    lines = [ln.rstrip() for ln in body.splitlines() if ln.strip()]
    if not lines:
        return ""
    if all(ln.lstrip().startswith(("-", "*", "•")) for ln in lines):
        items = "".join(
            f"<li>{escape(ln.lstrip()[1:].lstrip())}</li>" for ln in lines
        )
        return f"<ul>{items}</ul>"
    # Geen bullets → laat als prose-paragraaf.
    return f"<p>{escape(body)}</p>"


def _markdown_table_to_html(body: str) -> str:
    """Parse een GFM-tabel ('| a | b |' rows + '|---|---|'-separator) naar
    een echte <table>. Niet-tabel-input valt terug naar bullets/prose.

    Heuristiek: een tabel-blok is een aaneengesloten reeks regels die
    allemaal beginnen met '|'. Eerste rij = header, separator-rijen
    (alleen '|', '-', ':' en spaces) worden geskipt. Andere rijen = body."""
    raw_lines = [ln.rstrip() for ln in body.splitlines() if ln.strip()]
    if not raw_lines:
        return ""
    if not all(ln.lstrip().startswith("|") for ln in raw_lines):
        # Geen pure tabel → val terug op bullets/prose-renderer.
        return _bullets_to_html(body)

    def split_row(line: str) -> list[str]:
        s = line.strip()
        if s.startswith("|"):
            s = s[1:]
        if s.endswith("|"):
            s = s[:-1]
        return [c.strip() for c in s.split("|")]

    is_sep = re.compile(r"^[\s\-:|]+$")
    header_cells: list[str] = []
    body_rows: list[list[str]] = []
    for ln in raw_lines:
        cells = split_row(ln)
        if is_sep.match(ln):
            continue
        if not header_cells:
            header_cells = cells
        else:
            body_rows.append(cells)

    if not header_cells:
        return _bullets_to_html(body)

    thead = (
        "<thead><tr>"
        + "".join(f"<th>{escape(c)}</th>" for c in header_cells)
        + "</tr></thead>"
    )
    tbody_rows = []
    for row in body_rows:
        # Pad/truncate naar lengte van header.
        while len(row) < len(header_cells):
            row.append("")
        row = row[: len(header_cells)]
        cells_html = "".join(f"<td>{escape(c)}</td>" for c in row)
        tbody_rows.append(f"<tr>{cells_html}</tr>")
    tbody = "<tbody>" + "".join(tbody_rows) + "</tbody>"
    return f'<table class="report-table">{thead}{tbody}</table>'


def _section_card(title: str, body_html: str, status: str = "ok") -> str:
    """Wrapper-card voor een handover-sectie."""
    icon = {"ok": "✅", "wait": "⏳", "miss": "—"}.get(status, "•")
    return (
        f'<section class="handover-section status-{status}">'
        f'<h2>{icon} {escape(title)}</h2>'
        f"{body_html}"
        f"</section>"
    )


def _latest_run_by_role(runs: list[dict], role: str) -> Optional[dict]:
    """Laatste run voor de gegeven role, of None."""
    matches = [r for r in runs if r.get("role") == role]
    return matches[-1] if matches else None


def _render_handover_page(data: dict, jira_title: str = "") -> str:
    key = data["story_key"]
    runs = data["runs"]
    refiner = _latest_run_by_role(runs, "refiner")
    developer = _latest_run_by_role(runs, "developer")
    reviewer = _latest_run_by_role(runs, "reviewer")
    tester = _latest_run_by_role(runs, "tester")
    jira_url = (
        f"{JIRA_BASE_URL}/browse/{key}" if JIRA_BASE_URL else ""
    )
    title_text = jira_title or key

    # Refiner-sectie
    if refiner and refiner.get("summary_text"):
        ref_text = _strip_refiner_json_line(refiner["summary_text"])
        ref_sections = _split_sections(ref_text, [_REFINER_HEADING_ASSUMPTIONS])
        intro = ref_sections.get("__intro", "")
        ass = ref_sections.get(_REFINER_HEADING_ASSUMPTIONS, "")
        ref_html = ""
        if intro:
            ref_html += f'<p class="prose">{escape(intro)}</p>'
        if ass:
            ref_html += "<h3>Aannames</h3>" + _bullets_to_html(ass)
        elif not intro:
            ref_html = '<p class="muted">Geen refiner-samenvatting beschikbaar.</p>'
        refiner_card = _section_card("Refiner — context en aannames", ref_html)
    else:
        refiner_card = _section_card(
            "Refiner — context en aannames",
            '<p class="muted">Nog niet gedraaid.</p>',
            status="miss",
        )

    # Developer-sectie
    if developer and developer.get("summary_text"):
        dev_sections = _split_sections(
            developer["summary_text"],
            [_DEVELOPER_HEADING_PROSE, _DEVELOPER_HEADING_DONE, _DEVELOPER_HEADING_SKIPPED],
        )
        prose = dev_sections.get(_DEVELOPER_HEADING_PROSE) or dev_sections.get("__intro", "")
        done = dev_sections.get(_DEVELOPER_HEADING_DONE, "")
        skipped = dev_sections.get(_DEVELOPER_HEADING_SKIPPED, "")
        dev_html = ""
        if prose:
            dev_html += f'<p class="prose">{escape(prose)}</p>'
        if done:
            dev_html += "<h3>Gedaan</h3>" + _bullets_to_html(done)
        if skipped:
            dev_html += "<h3>Niet gedaan / aangepast</h3>" + _bullets_to_html(skipped)
        if not dev_html:
            dev_html = '<p class="muted">Geen developer-samenvatting beschikbaar.</p>'
        developer_card = _section_card("Developer — wat is gebouwd", dev_html)
    else:
        developer_card = _section_card(
            "Developer — wat is gebouwd",
            '<p class="muted">Nog niet gedraaid.</p>',
            status="miss",
        )

    # Reviewer-sectie (Fase 4)
    if reviewer and reviewer.get("summary_text"):
        rev_sections = _split_sections(
            _strip_refiner_json_line(reviewer["summary_text"]),
            [_REVIEWER_HEADING_PROSE, _REVIEWER_HEADING_FINDINGS, _REVIEWER_HEADING_VERDICT],
        )
        prose = rev_sections.get(_REVIEWER_HEADING_PROSE) or rev_sections.get("__intro", "")
        findings = rev_sections.get(_REVIEWER_HEADING_FINDINGS, "")
        verdict = rev_sections.get(_REVIEWER_HEADING_VERDICT, "").strip()
        rev_html = ""
        if prose:
            rev_html += f'<p class="prose">{escape(prose)}</p>'
        if findings:
            rev_html += "<h3>Bevindingen</h3>" + _bullets_to_html(findings)
        if verdict:
            verdict_class = "ok" if verdict.upper().startswith("OK") else "changes"
            rev_html += (
                f'<p class="verdict verdict-{verdict_class}">'
                f"Verdict: <strong>{escape(verdict)}</strong></p>"
            )
        if not rev_html:
            rev_html = '<p class="muted">Geen reviewer-samenvatting beschikbaar.</p>'
        reviewer_card = _section_card("Reviewer — code-review", rev_html)
    else:
        reviewer_card = _section_card(
            "Reviewer — code-review",
            '<p class="muted">Nog niet gedraaid. Komt automatisch zodra de developer een PR heeft gepusht.</p>',
            status="wait",
        )

    # Tester-sectie (Fase 5 MVP+). Parsed zowel het nieuwe Stappenrapport-
    # formaat (markdown-tabel) als de legacy Getest/Resultaat-bullets.
    if tester and tester.get("summary_text"):
        tst_sections = _split_sections(
            _strip_refiner_json_line(tester["summary_text"]),
            [
                _TESTER_HEADING_PROSE,
                _TESTER_HEADING_STEPS,
                _TESTER_HEADING_FINDINGS,
                _TESTER_HEADING_FOR_HUMAN,
                _TESTER_HEADING_TESTED_LEGACY,
                _TESTER_HEADING_RESULTS_LEGACY,
                _TESTER_HEADING_FOR_HUMAN_LEGACY,
            ],
        )
        prose = tst_sections.get(_TESTER_HEADING_PROSE) or tst_sections.get("__intro", "")
        steps = tst_sections.get(_TESTER_HEADING_STEPS, "")
        findings = tst_sections.get(_TESTER_HEADING_FINDINGS, "")
        for_human = (
            tst_sections.get(_TESTER_HEADING_FOR_HUMAN, "")
            or tst_sections.get(_TESTER_HEADING_FOR_HUMAN_LEGACY, "")
        )
        # Legacy fallback: oude rapporten hadden Getest + Resultaat ipv tabel.
        tested_legacy = tst_sections.get(_TESTER_HEADING_TESTED_LEGACY, "")
        results_legacy = tst_sections.get(_TESTER_HEADING_RESULTS_LEGACY, "")

        tst_html = ""
        if prose:
            tst_html += f'<p class="prose">{escape(prose)}</p>'
        if steps:
            tst_html += "<h3>Stappenrapport</h3>" + _markdown_table_to_html(steps)
        elif tested_legacy or results_legacy:
            if tested_legacy:
                tst_html += "<h3>Getest</h3>" + _bullets_to_html(tested_legacy)
            if results_legacy:
                tst_html += "<h3>Resultaat per test</h3>" + _bullets_to_html(results_legacy)
        if findings:
            tst_html += "<h3>Bevindingen</h3>" + _bullets_to_html(findings)
        if for_human:
            tst_html += "<h3>Opvallend voor mens — handmatig checken</h3>" + _bullets_to_html(for_human)
        if not tst_html:
            tst_html = '<p class="muted">Geen tester-samenvatting beschikbaar.</p>'
        tester_card = _section_card("Tester — test-rapport", tst_html)
    else:
        tester_card = _section_card(
            "Tester — test-rapport",
            '<p class="muted">Nog niet gedraaid. Komt automatisch na een succesvolle reviewer-ronde.</p>',
            status="wait",
        )

    return f"""<!doctype html>
<html lang="nl">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Briefing — {escape(key)}</title>
  <style>{_LOG_CSS}
    .handover-section {{ background: #1a2029; border: 1px solid #2c3340;
      border-radius: 8px; padding: 14px 18px; margin: 12px 0; }}
    .handover-section h2 {{ margin: 0 0 8px 0; font-size: 16px; color: #e4e6eb; }}
    .handover-section h3 {{ margin: 12px 0 4px 0; font-size: 13px;
      color: #8b96a8; font-weight: 600; text-transform: uppercase;
      letter-spacing: 0.04em; }}
    .handover-section ul {{ margin: 4px 0 8px 20px; padding: 0; }}
    .handover-section li {{ margin: 2px 0; font-size: 13px; line-height: 1.5; }}
    .handover-section p.prose {{ font-size: 14px; line-height: 1.55;
      color: #d8dde6; margin: 4px 0 6px 0; white-space: pre-wrap; }}
    .handover-section .muted {{ color: #6f7a8a; font-style: italic; font-size: 13px; }}
    .handover-section.status-miss h2 {{ color: #8b96a8; }}
    .verdict {{ margin: 8px 0 4px 0; padding: 6px 10px; border-radius: 4px;
      font-size: 13px; display: inline-block; }}
    .verdict-ok {{ background: #052e16; color: #4ade80;
      border: 1px solid #065f46; }}
    .verdict-changes {{ background: #2c1810; color: #fbbf24;
      border: 1px solid #713f12; }}
    table.report-table {{ border-collapse: collapse; width: 100%;
      margin: 8px 0; font-size: 13px; }}
    table.report-table th {{ background: #2c3340; color: #e4e6eb;
      text-align: left; padding: 6px 10px; border-bottom: 1px solid #3a414f; }}
    table.report-table td {{ padding: 6px 10px; border-bottom: 1px solid #2c3340;
      vertical-align: top; }}
    table.report-table tr:hover td {{ background: #1f2530; }}
    .links-card {{ background: #1a2029; border: 1px solid #2c3340;
      border-radius: 8px; padding: 12px 18px; margin: 12px 0;
      display: flex; gap: 18px; flex-wrap: wrap; font-size: 14px; }}
    .links-card a {{ color: #4a9eff; text-decoration: none; }}
    .links-card a:hover {{ text-decoration: underline; }}
  </style>
</head>
<body>
  <h1>🧪 Briefing — {escape(key)}</h1>
  <p><a href="/">← terug naar dashboard</a> · <a href="/story/{escape(key)}">timeline →</a></p>
  <h2 style="margin-top: 18px;">{escape(title_text)}</h2>

  <div class="links-card">
    {f'<a href="{escape(jira_url)}" target="_blank">JIRA-ticket →</a>' if jira_url else ''}
    <span class="muted" style="color:#6f7a8a">PR + preview-link komen via de [DEVELOPER]-comment in JIRA.</span>
  </div>

  {refiner_card}
  {developer_card}
  {reviewer_card}
  {tester_card}
</body>
</html>"""


def render_phase(p: Phase) -> str:
    icon = STATUS_ICONS.get(p.status, "?")
    detail = escape(p.detail) if p.detail else ""
    if p.link and p.status in ("fail", "running"):
        detail = f'<a href="{escape(p.link)}" target="_blank">{detail or "↗ logs"}</a>'
    since_html = (
        f' <span class="since">· {escape(p.since)} geleden</span>'
        if p.since else ""
    )
    return (
        f'<div class="phase {escape(p.status)}">'
        f'<span class="icon">{icon}</span>'
        f'<span class="label">{escape(p.label)}</span>'
        f'<span class="detail">{detail}{since_html}</span>'
        f"</div>"
    )


def render_main(card: MainCard) -> str:
    phases_html = "".join(render_phase(p) for p in card.phases)
    merges_html = " · ".join(
        f'<a href="{escape(m["html_url"])}" title="{escape(m["title"])}">'
        f'#{m["number"]} ({escape(m["age"])})</a>'
        for m in card.recent_merges
    )
    merges_block = (
        f'<div class="merges">Recente merges: {merges_html}</div>' if merges_html else ""
    )
    apk_block = ""
    if card.apk_url:
        apk_block = (
            f'<a class="apk" href="{escape(card.apk_url)}" target="_blank">'
            f"📱 APK · {escape(card.apk_filename)} ({escape(card.apk_age)} geleden)"
            "</a>"
        )
    return (
        '<div class="card prod">'
        f'<div class="title"><a href="https://github.com/{escape(GITHUB_OWNER)}/{escape(GITHUB_REPO)}/tree/main" target="_blank">'
        f"🟢 Production</a> — {escape(APP_BASE_URL)}</div>"
        f'<div class="meta">main @ <code>{escape(card.sha_short)}</code> — {escape(card.message)} ({escape(card.age)} geleden)</div>'
        f"{phases_html}"
        f"{apk_block}"
        f"{merges_block}"
        "</div>"
    )


def render_pr(card: PRCard) -> str:
    phases_html = "".join(render_phase(p) for p in card.phases)
    preview = (
        f'<a class="preview" href="{escape(card.preview_url)}" target="_blank">'
        f"🌐 {escape(card.preview_url.replace('https://', ''))}</a>"
    )

    # Status-badge bovenaan: enkel de PR-state. JIRA komt nu als
    # info-rij hieronder, mét sinds-timestamp.
    badge_html = ""
    if card.pr_state and card.pr_state_label:
        badge_html = (
            f'<div class="badges">'
            f'<span class="badge {escape(card.pr_state)}">'
            f"{escape(card.pr_state_label)}</span></div>"
        )

    # Info-rijen: branch/author + last commit + JIRA-status. Zelfde layout
    # als phases zodat 't visueel consistent oogt.
    info_rows: list[str] = []
    info_rows.append(
        f'<div class="info-row">'
        f'<span class="label">branch</span>'
        f'<span class="detail"><code>{escape(card.branch)}</code> · door {escape(card.author)}</span>'
        f"</div>"
    )
    commit_age_html = (
        f' <span class="since">· {escape(card.last_commit_age)} geleden</span>'
        if card.last_commit_age else ""
    )
    info_rows.append(
        f'<div class="info-row">'
        f'<span class="label">last commit</span>'
        f'<span class="detail"><code>{escape(card.head_sha)}</code>{commit_age_html}</span>'
        f"</div>"
    )
    if card.jira_status:
        jira_age_html = (
            f' <span class="since">· sinds {escape(card.jira_status_age)}</span>'
            if card.jira_status_age else ""
        )
        info_rows.append(
            f'<div class="info-row">'
            f'<span class="label">JIRA-status</span>'
            f'<span class="detail">{escape(card.jira_status)}{jira_age_html}</span>'
            f"</div>"
        )
    info_rows.append(_render_runner_row(card))
    # Story-key uit branch afleiden voor de factory-row.
    m = _JIRA_BRANCH_RE.match(card.branch)
    story_key = m.group(1) if m else ""
    fac_row = _render_factory_row(story_key, card)
    if fac_row:
        info_rows.append(fac_row)

    level_html = _render_level_badge(card.ai_level)
    phase_html = _render_phase_pill(card.ai_phase)
    pipeline_html = _render_pipeline_bar(card.ai_phase)

    return (
        '<div class="card pr">'
        f'<div class="title"><a href="{escape(card.html_url)}" target="_blank">'
        f"🟡 PR #{card.number} — {escape(card.title)}</a>{level_html}{phase_html}</div>"
        f"{badge_html}"
        f"{pipeline_html}"
        f'{"".join(info_rows)}'
        f"{phases_html}"
        f"{preview}"
        "</div>"
    )


def render_jira(card: JIRACard) -> str:
    title_inner = f"🤖 {escape(card.key)} — {escape(card.title)}"
    title = (
        f'<a href="{escape(card.jira_url)}" target="_blank">{title_inner}</a>'
        if card.jira_url else title_inner
    )
    level_html = _render_level_badge(card.ai_level)
    phase_html = _render_phase_pill(card.ai_phase)
    pipeline_html = _render_pipeline_bar(card.ai_phase)
    # Bouw de meta-regel: status (sinds X) · job-info (indien aanwezig).
    # Bij een actieve/recente Job hangt er een "Log →"-link aan zodat je
    # kan meekijken vóórdat de PR überhaupt bestaat.
    parts = [f"{escape(card.status)} sinds {escape(card.age)}"]
    if card.job_state:
        icon = STATUS_ICONS.get(card.job_status, "?")
        job_html = f"{icon} Job {escape(card.job_state)}"
        if card.job_name:
            job_html += f' <a href="/runner/{escape(card.job_name)}/log">Log →</a>'
        parts.append(job_html)
    elif card.status == "AI Ready":
        parts.append("wacht op poller (≤ 30s)")
    meta = " · ".join(parts)
    # Factory-row (alleen tokens + cost) als er DB-data is.
    fac_html = ""
    if card.tokens_input or card.tokens_output or card.tokens_cache_read or card.cost_usd:
        fac_detail = (
            f"{_fmt_tokens(card.tokens_input)} in / "
            f"{_fmt_tokens(card.tokens_output)} out · "
            f"cache-read {_fmt_tokens(card.tokens_cache_read)} · "
            f"≈ ${card.cost_usd:.4f}"
        )
        fac_html = (
            f'<div class="meta">factory: {escape(fac_detail)}</div>'
        )
    # Duidelijke knop onderin de kaart — was vroeger een inline 'Timeline →'-
    # link in de factory-row, nu een prominente 'Details'-knop. Briefing
    # blijft als secundaire knop ernaast.
    details_btns = (
        f'<div class="card-actions">'
        f'<a class="card-btn primary" href="/story/{escape(card.key)}">Details →</a>'
        f'<a class="card-btn" href="/story/{escape(card.key)}/handover">Briefing →</a>'
        f'</div>'
    )
    return (
        f'<div class="card jira">'
        f'<div class="title">{title}{level_html}{phase_html}</div>'
        f"{pipeline_html}"
        f'<div class="meta">{meta}</div>'
        f"{fac_html}"
        f"{details_btns}"
        f"</div>"
    )


def render_closed(cards: list[ClosedCard]) -> str:
    if not cards:
        return ""
    rows = "".join(
        f'<div class="closed"><a href="{escape(c.html_url)}" target="_blank">'
        f"#{c.number}</a> — {escape(c.title)} <span style='color:#8b96a8'>"
        f"({escape(c.merged_age)} geleden)</span></div>"
        for c in cards
    )
    return f'<div class="closed-list">{rows}</div>'


def render_handover_banner(cards: list[JIRACard]) -> str:
    """Banner-sectie voor stories die klaar staan voor menselijke
    test/merge: status AI IN REVIEW + phase=tested-ok. Tot Fase 5 leeft
    deze sectie waarschijnlijk leeg — dat is OK; render_page slaat 'm
    dan over."""
    ready = [
        c for c in cards
        if c.status == "AI IN REVIEW" and c.ai_phase == "tested-ok"
    ]
    if not ready:
        return ""
    items = []
    for c in ready:
        title = escape(f"{c.key} — {c.title}")
        items.append(
            f'<div class="handover-banner-card">'
            f'<div class="hb-title">{title}</div>'
            f'<div class="hb-meta">{escape(c.status)} sinds {escape(c.age)}</div>'
            f'<a class="hb-cta" href="/story/{escape(c.key)}/handover">Open briefing →</a>'
            f'</div>'
        )
    return (
        '<section class="handover-banner">'
        f'<h1 style="margin: 16px 0 8px;">🧪 Klaar voor jouw test ({len(ready)})</h1>'
        f'<div class="handover-banner-grid">{"".join(items)}</div>'
        '</section>'
    )


def render_page(state: dict) -> str:
    main_html = render_main(state["main"])
    ai_cards = state.get("ai_active", [])
    handover_html = render_handover_banner(ai_cards)
    ai_html = "".join(render_jira(c) for c in ai_cards)
    ai_section = (
        f'<h1 style="margin-top: 24px;">🤖 AI bezig ({len(ai_cards)})</h1>{ai_html}'
        if ai_cards else ""
    )
    prs_html = (
        "".join(render_pr(p) for p in state["open_prs"])
        if state["open_prs"]
        else '<div class="empty">Geen open PR\'s.</div>'
    )
    closed_html = render_closed(state["closed_prs"])
    return f"""<!doctype html>
<html lang="nl">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta http-equiv="refresh" content="{REFRESH_SEC}">
  <title>PNF Status</title>
  <style>{CSS}</style>
</head>
<body>
  <h1>Personal News Feed — status</h1>
  <div class="sub">Auto-refresh elke {REFRESH_SEC}s · cache {CACHE_TTL_SEC}s · fetched at {escape(state['fetched_at'])} · <a href="/stories">alle stories →</a> · <a href="https://dashboard.vdzonsoftware.nl">nieuwe dashboard-app →</a> · <a href="https://dashboard.vdzonsoftware.nl/download/dashboard.apk">📱 Android APK</a></div>

  {main_html}

  {handover_html}

  {ai_section}

  <h1 style="margin-top: 24px;">Open PR's ({len(state['open_prs'])})</h1>
  {prs_html}

  <h1 style="margin-top: 24px;">Recent gemerged</h1>
  {closed_html}
</body>
</html>"""


# ─── flask ────────────────────────────────────────────────────────────────


app = Flask(__name__)


@app.route("/")
def index() -> Response:
    try:
        state = build_state()
        body = render_page(state)
        return Response(body, mimetype="text/html; charset=utf-8")
    except Exception as e:
        log.exception("render faalde: %s", e)
        return Response(
            f"<html><body><h1>Error</h1><pre>{escape(str(e))}</pre></body></html>",
            status=500,
            mimetype="text/html",
        )


@app.route("/healthz")
def healthz() -> Response:
    return Response("ok", mimetype="text/plain")


# Story-key: KAN-XX of vergelijkbaar. Beperk wat we accepteren — voorkomt
# path-injection en houdt de DB-query simpel.
_STORY_KEY_RE = re.compile(r"^[A-Z][A-Z0-9]+-[0-9]+$")


@app.route("/stories")
def stories_index() -> Response:
    rows = factory_all_stories()
    body = _render_stories_index(rows)
    return Response(body, mimetype="text/html; charset=utf-8")


@app.route("/story/<key>")
def story_timeline(key: str) -> Response:
    if not _STORY_KEY_RE.match(key):
        return Response("Ongeldige story-key.", status=400, mimetype="text/plain")
    data = factory_story_timeline(key)
    if data is None:
        body = _render_story_missing(key)
    else:
        # Externe data lazy ophalen — alle drie best-effort, faalt
        # veilig met lege defaults.
        jira_title = _jira_fetch_issue_title(key)
        prs = gh_prs_for_branch(f"ai/{key}")
        commits = gh_commits_for_branch(f"ai/{key}", limit=30)
        body = _render_story_page(data, jira_title=jira_title, prs=prs, commits=commits)
    return Response(body, mimetype="text/html; charset=utf-8")


def _jira_fetch_issue_title(key: str) -> str:
    """Eén losse JIRA-call om de issue-titel te krijgen. Best-effort —
    op fout geven we gewoon "" terug zodat de handover-pagina alsnog
    rendert met alleen de KAN-key als kop."""
    meta = _jira_fetch_issue_meta(key)
    return meta.get("title", "")


def _jira_fetch_issue_meta(key: str) -> dict:
    """Eén JIRA-call die summary + status + ai-phase ophaalt. Output:
    {title, status, ai_phase}. Best-effort: lege strings op fout."""
    out = {"title": "", "status": "", "ai_phase": ""}
    if not (JIRA_BASE_URL and JIRA_EMAIL and JIRA_API_KEY):
        return out
    # AI Phase-custom-field-ID lazy-resolven (één keer per process).
    _discover_ai_field_ids()
    phase_field_id = _ai_field_id_cache.get("phase")
    fields_param = "summary,status"
    if phase_field_id:
        fields_param += f",{phase_field_id}"
    try:
        r = _jira_session.get(
            f"{JIRA_BASE_URL}/rest/api/3/issue/{key}",
            params={"fields": fields_param},
            timeout=5,
        )
    except requests.RequestException:
        return out
    if r.status_code != 200:
        return out
    f = (r.json().get("fields") or {})
    out["title"] = f.get("summary") or ""
    out["status"] = ((f.get("status") or {}).get("name")) or ""
    if phase_field_id:
        out["ai_phase"] = f.get(phase_field_id) or ""
    return out


_VALID_COMMANDS = {"delete", "merge", "pause", "re-implement"}


def _post_jira_command_comment(key: str, command: str) -> bool:
    """Post een @claude:command:<cmd>-comment naar JIRA. De poller pikt
    'm op in de volgende tick en voert 't commando uit."""
    if not (JIRA_BASE_URL and JIRA_EMAIL and JIRA_API_KEY):
        return False
    body_text = f"@claude:command:{command} (via dashboard)"
    adf = {
        "body": {
            "type": "doc",
            "version": 1,
            "content": [{
                "type": "paragraph",
                "content": [{"type": "text", "text": body_text}],
            }],
        }
    }
    try:
        r = _jira_session.post(
            f"{JIRA_BASE_URL}/rest/api/3/issue/{key}/comment",
            json=adf,
            headers={"Content-Type": "application/json"},
            timeout=10,
        )
    except requests.RequestException as e:
        log.warning("post command-comment %s/%s faalde: %s", key, command, e)
        return False
    return r.status_code in (200, 201)


@app.route("/story/<key>/cmd/<command>", methods=["POST"])
def story_command(key: str, command: str) -> Response:
    """Schrijf een @claude:command:<cmd>-comment in de JIRA-story zodat
    de poller 't oppakt. Geen directe actie hier — alle execution loopt
    via de poller (single path)."""
    if not _STORY_KEY_RE.match(key):
        return Response("Ongeldige story-key.", status=400, mimetype="text/plain")
    if command not in _VALID_COMMANDS:
        return Response(f"Onbekend commando: {command}", status=400, mimetype="text/plain")
    if not _post_jira_command_comment(key, command):
        return Response(
            f"Comment-post voor commando '{command}' faalde. Check de log.",
            status=502, mimetype="text/plain",
        )
    # Terug naar de detail-page met een banner-flag.
    return redirect(f"/story/{key}?cmd={command}", code=303)


@app.route("/story/<key>/handover")
def story_handover(key: str) -> Response:
    if not _STORY_KEY_RE.match(key):
        return Response("Ongeldige story-key.", status=400, mimetype="text/plain")
    data = factory_story_timeline(key)
    if data is None:
        body = _render_story_missing(key)
    else:
        body = _render_handover_page(data, jira_title=_jira_fetch_issue_title(key))
    return Response(body, mimetype="text/html; charset=utf-8")


@app.route("/runner/<job_name>/log")
def runner_log(job_name: str) -> Response:
    if not _JOB_NAME_RE.match(job_name):
        return Response("Ongeldige job-naam.", status=400, mimetype="text/plain")

    job_data = kubectl_json("get", "job", job_name, "-n", FACTORY_NS)
    job_status = job_data.get("status", {})
    labels = (job_data.get("metadata", {}) or {}).get("labels", {}) or {}

    conds = job_status.get("conditions", []) or []
    complete = any(c.get("type") == "Complete" and c.get("status") == "True" for c in conds)
    failed_job = any(c.get("type") == "Failed" and c.get("status") == "True" for c in conds)
    start_ts = job_status.get("startTime", "")
    completion_ts = job_status.get("completionTime", "")
    is_running = not complete and not failed_job

    if complete:
        dur = _fmt_seconds(_duration_seconds(start_ts, completion_ts))
        status_text = f"✅ finished ({dur}, {_ago(completion_ts)} geleden)"
    elif failed_job:
        status_text = f"❌ failed ({_ago(completion_ts or start_ts)} geleden)"
    elif start_ts:
        status_text = f"🟢 running ({_duration_seconds(start_ts)}s)"
    else:
        status_text = "🟡 pending"

    # PR-info opzoeken via gecachede state.
    pr_title = ""
    pr_url = ""
    pr_num_label = labels.get("pr-num", "")
    story_id_label = labels.get("story-id", "")
    try:
        cached = build_state()
        for pr in cached.get("open_prs", []):
            if pr_num_label and str(pr.number) == str(pr_num_label):
                pr_title = pr.title
                pr_url = pr.html_url
                break
            if story_id_label:
                bm = _AI_BRANCH_RE.match(pr.branch)
                if bm and _sanitize_story_id(bm.group(1)) == story_id_label:
                    pr_title = pr.title
                    pr_url = pr.html_url
                    break
    except Exception as exc:
        log.warning("PR-lookup voor log-pagina faalde: %s", exc)

    # Pod zoeken (automatisch label job-name=<job_name> door K8s).
    pods_data = kubectl_json("get", "pods", "-n", FACTORY_NS, "-l", f"job-name={job_name}")
    pods = pods_data.get("items", [])

    log_text: Optional[str] = None
    log_status = 200
    pod_gone = False

    if pods:
        pod_name = pods[0].get("metadata", {}).get("name", "")
        log_text, log_status = k8s_pod_log(pod_name)
    else:
        ref_ts = completion_ts or start_ts
        if ref_ts and _duration_seconds(ref_ts) > 7200:
            pod_gone = True

    # Fallback: pod onbereikbaar of weg → reconstrueer uit factory-DB.
    # Werkt ook voor runs van weken oud, zolang ze ooit factory-report.py
    # hebben uitgevoerd.
    if log_text is None:
        ar = factory_lookup_agent_run_by_job(job_name)
        if ar is not None:
            events = factory_events_for_run(ar["id"])
            if events:
                log_text = _format_events_as_pretty_log(events)
                log_status = 200  # 'success-via-archive'
                pod_gone = False  # we hébben de log
                # Status-text upgraden naar archief-context als de pod
                # weg was; aanvankelijke status uit Job kan ook stale zijn.
                if ar.get("ended_at"):
                    age = _ago(ar["ended_at"].isoformat() if ar["ended_at"] else "")
                    status_text = (
                        f"📦 archief — {ar['role']} · "
                        f"outcome={ar['outcome']} · {age} geleden"
                    )

    return Response(
        _render_log_page(
            job_name, status_text, pr_title, pr_url,
            log_text, pod_gone, is_running, log_status,
        ),
        mimetype="text/html; charset=utf-8",
    )


# ─── JSON API (Fase 1 — Flutter-dashboard backend) ────────────────────────
#
# Naast de bestaande HTML-routes geven we de state ook als JSON terug op
# /api/v1/*. CORS is open voor de Flutter-dashboard-origin (default
# https://dashboard.vdzonsoftware.nl). Auth via simpele JWT met één user
# 'admin' (wachtwoord in DASHBOARD_ADMIN_PASSWORD env-var).
#
# Bewust geen externe deps (PyJWT etc.) — minimale HS256-implementatie
# in <30 regels met hmac/json/base64.

import functools
import hashlib
import hmac
import secrets as _secrets

DASHBOARD_ADMIN_PASSWORD = os.environ.get("DASHBOARD_ADMIN_PASSWORD", "")
DASHBOARD_CORS_ORIGIN = os.environ.get(
    "DASHBOARD_CORS_ORIGIN", "https://dashboard.vdzonsoftware.nl"
)
# JWT-signing-key: bij voorkeur uit JWT_SECRET (sealed-secret), valt
# terug op een random secret als de env-var ontbreekt. Random-fallback =
# clients loggen na pod-restart opnieuw in; met de env-var blijven ze
# ingelogd over restarts heen.
_JWT_SECRET = os.environ.get("JWT_SECRET") or _secrets.token_hex(32)
JWT_TTL_SEC = int(os.environ.get("DASHBOARD_JWT_TTL_SEC", str(7 * 24 * 3600)))


def _b64url(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def _b64url_decode(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def jwt_sign(payload: dict) -> str:
    """HS256-JWT met _JWT_SECRET. payload['exp'] in seconds-since-epoch."""
    header = _b64url(json.dumps({"alg": "HS256", "typ": "JWT"},
                                separators=(",", ":")).encode())
    body = _b64url(json.dumps(payload, separators=(",", ":")).encode())
    sig = _b64url(hmac.new(_JWT_SECRET.encode(),
                           f"{header}.{body}".encode(),
                           hashlib.sha256).digest())
    return f"{header}.{body}.{sig}"


def jwt_verify(token: str) -> Optional[dict]:
    """Verifieer + decode. Returnt payload of None bij ongeldig/expired."""
    try:
        h, b, s = token.split(".")
    except ValueError:
        return None
    expected = _b64url(hmac.new(_JWT_SECRET.encode(),
                                f"{h}.{b}".encode(),
                                hashlib.sha256).digest())
    if not hmac.compare_digest(s, expected):
        return None
    try:
        payload = json.loads(_b64url_decode(b))
    except (ValueError, json.JSONDecodeError):
        return None
    if payload.get("exp", 0) < time.time():
        return None
    return payload


def _add_cors_headers(resp: Response) -> Response:
    resp.headers["Access-Control-Allow-Origin"] = DASHBOARD_CORS_ORIGIN
    resp.headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
    resp.headers["Access-Control-Allow-Headers"] = "Authorization, Content-Type"
    resp.headers["Access-Control-Max-Age"] = "600"
    resp.headers["Vary"] = "Origin"
    return resp


@app.after_request
def _maybe_attach_cors(resp: Response) -> Response:
    """CORS-headers alleen op /api/*-routes; HTML-pagina's blijven onveranderd."""
    if request.path.startswith("/api/"):
        _add_cors_headers(resp)
    return resp


def require_jwt(fn):
    """Decorator: 401 als de Authorization-header geen geldige JWT bevat."""
    @functools.wraps(fn)
    def wrapper(*args, **kwargs):
        auth = request.headers.get("Authorization", "")
        if not auth.startswith("Bearer "):
            return jsonify(error="missing bearer token"), 401
        if jwt_verify(auth[7:]) is None:
            return jsonify(error="invalid or expired token"), 401
        return fn(*args, **kwargs)
    return wrapper


@app.route("/api/v1/auth/login", methods=["POST", "OPTIONS"])
def api_login() -> Response:
    if request.method == "OPTIONS":
        return _add_cors_headers(Response("", status=204))
    if not DASHBOARD_ADMIN_PASSWORD:
        return jsonify(error="dashboard auth not configured (no DASHBOARD_ADMIN_PASSWORD)"), 503
    data = request.get_json(silent=True) or {}
    username = (data.get("username") or "").strip()
    password = data.get("password") or ""
    # Constant-time vergelijking voor wachtwoord.
    if username != "admin" or not hmac.compare_digest(password, DASHBOARD_ADMIN_PASSWORD):
        return jsonify(error="invalid credentials"), 401
    exp = int(time.time()) + JWT_TTL_SEC
    token = jwt_sign({"sub": "admin", "exp": exp})
    return jsonify(token=token, expires_at=exp, username="admin")


@app.route("/api/v1/state", methods=["GET", "OPTIONS"])
@require_jwt
def api_state() -> Response:
    try:
        state = build_state()
    except Exception as e:
        log.exception("api_state faalde: %s", e)
        return jsonify(error=str(e)), 500
    return jsonify(_state_to_dict(state))


@app.route("/api/v1/stories", methods=["GET", "OPTIONS"])
@require_jwt
def api_stories() -> Response:
    return jsonify(stories=[_story_row_to_dict(r) for r in factory_all_stories()])


@app.route("/api/v1/stories/<key>", methods=["GET", "OPTIONS"])
@require_jwt
def api_story_detail(key: str) -> Response:
    if not _STORY_KEY_RE.match(key):
        return jsonify(error="bad key"), 400
    data = factory_story_timeline(key)
    if data is None:
        return jsonify(error="not found"), 404
    meta = _jira_fetch_issue_meta(key)
    return jsonify({
        "story": _story_timeline_to_dict(data),
        "jira_title": meta.get("title", ""),
        "jira_status": meta.get("status", ""),
        "ai_phase": meta.get("ai_phase", ""),
        "prs": gh_prs_for_branch(f"ai/{key}"),
        "commits": gh_commits_for_branch(f"ai/{key}", limit=30),
    })


@app.route("/api/v1/stories/<key>/handover", methods=["GET", "OPTIONS"])
@require_jwt
def api_story_handover(key: str) -> Response:
    if not _STORY_KEY_RE.match(key):
        return jsonify(error="bad key"), 400
    data = factory_story_timeline(key)
    if data is None:
        return jsonify(error="not found"), 404
    return jsonify({
        "story_key": key,
        "jira_title": _jira_fetch_issue_title(key),
        "refiner":   _agent_summary_dict(_latest_run_by_role(data["runs"], "refiner")),
        "developer": _agent_summary_dict(_latest_run_by_role(data["runs"], "developer")),
        "reviewer":  _agent_summary_dict(_latest_run_by_role(data["runs"], "reviewer")),
        "tester":    _agent_summary_dict(_latest_run_by_role(data["runs"], "tester")),
    })


@app.route("/api/v1/stories/<key>/cmd/<command>", methods=["POST", "OPTIONS"])
@require_jwt
def api_story_command(key: str, command: str) -> Response:
    if request.method == "OPTIONS":
        return _add_cors_headers(Response("", status=204))
    if not _STORY_KEY_RE.match(key):
        return jsonify(error="bad key"), 400
    if command not in _VALID_COMMANDS:
        return jsonify(error="unknown command"), 400
    if not _post_jira_command_comment(key, command):
        return jsonify(error="comment-post failed"), 502
    return jsonify(ok=True, command=command, key=key)


@app.route("/api/v1/stories/<key>/active-job", methods=["GET", "OPTIONS"])
@require_jwt
def api_story_active_job(key: str) -> Response:
    """Geef de huidig-lopende agent-run voor een story terug (als die er
    is). Bron: factory.agent_runs WHERE ended_at IS NULL voor de
    laatste story_run.
    """
    if not _STORY_KEY_RE.match(key):
        return jsonify(error="bad key"), 400
    if not _factory_db_available():
        return jsonify(active=None)
    try:
        with psycopg.connect(FACTORY_DATABASE_URL) as conn, conn.cursor() as cur:
            cur.execute(
                """SELECT ar.id, ar.role, ar.job_name, ar.started_at
                   FROM factory.agent_runs ar
                   JOIN factory.story_runs sr ON sr.id = ar.story_run_id
                   WHERE sr.story_key = %s AND ar.ended_at IS NULL
                   ORDER BY ar.started_at DESC LIMIT 1""",
                (key,),
            )
            r = cur.fetchone()
    except Exception as e:
        log.warning("active-job lookup faalde: %s", e)
        return jsonify(active=None)
    if not r:
        return jsonify(active=None)
    return jsonify(active={
        "id": r[0], "role": r[1], "job_name": r[2], "started_at": _iso(r[3]),
    })


# Patroon voor [REFINER]/[REVIEWER]/[TESTER]-vragen-comments — de
# laatste daarvan is de PO-vraag waar nu op gewacht wordt.
_PO_QUESTION_RE = re.compile(r"^\[(REFINER|REVIEWER|TESTER)\]\s+Vragen|^\[(REFINER|REVIEWER|TESTER)\]", re.MULTILINE)


@app.route("/api/v1/stories/<key>/po-question", methods=["GET", "OPTIONS"])
@require_jwt
def api_story_po_question(key: str) -> Response:
    """Geef de laatste agent-vraag terug uit de JIRA-comment-thread.
    Returnt null als er geen actieve vraag is."""
    if not _STORY_KEY_RE.match(key):
        return jsonify(error="bad key"), 400
    if not (JIRA_BASE_URL and JIRA_EMAIL and JIRA_API_KEY):
        return jsonify(question=None)
    try:
        r = _jira_session.get(
            f"{JIRA_BASE_URL}/rest/api/3/issue/{key}/comment",
            params={"maxResults": "100", "orderBy": "-created"},
            timeout=10,
        )
    except requests.RequestException:
        return jsonify(question=None)
    if r.status_code != 200:
        return jsonify(question=None)
    # Loop nieuwste-eerst tot we een agent-comment met "Vragen voor" vinden.
    for c in r.json().get("comments", []):
        body = c.get("body")
        text = _adf_to_plain(body) if body else ""
        if _PO_QUESTION_RE.search(text) and "Vragen" in text:
            return jsonify(question={
                "comment_id": c.get("id"),
                "text": text,
                "created": c.get("created"),
            })
    return jsonify(question=None)


def _adf_to_plain(node) -> str:
    """Vlak een Atlassian-Document-Format-tree naar plain text."""
    if node is None:
        return ""
    if isinstance(node, list):
        return "".join(_adf_to_plain(c) for c in node)
    if isinstance(node, dict):
        if node.get("type") == "text":
            return node.get("text") or ""
        content = node.get("content")
        out = _adf_to_plain(content) if content else ""
        t = node.get("type")
        if t == "paragraph":
            out += "\n"
        elif t == "hardBreak":
            out += "\n"
        return out
    return ""


@app.route("/api/v1/stories/<key>/po-answer", methods=["POST", "OPTIONS"])
@require_jwt
def api_story_po_answer(key: str) -> Response:
    """Plaats een PO-antwoord-comment + transition naar AI Queued.
    Body: {"text": "..."}. De poller pakt de story dan op via
    Stap 3 (AI Queued + phase=awaiting-po → resume_phase)."""
    if request.method == "OPTIONS":
        return _add_cors_headers(Response("", status=204))
    if not _STORY_KEY_RE.match(key):
        return jsonify(error="bad key"), 400
    data = request.get_json(silent=True) or {}
    text = (data.get("text") or "").strip()
    if not text:
        return jsonify(error="text is required"), 400
    if not (JIRA_BASE_URL and JIRA_EMAIL and JIRA_API_KEY):
        return jsonify(error="JIRA not configured"), 503
    # 1. Post comment
    body = {
        "body": {
            "type": "doc", "version": 1,
            "content": [{
                "type": "paragraph",
                "content": [{"type": "text", "text": text}],
            }],
        }
    }
    try:
        rc = _jira_session.post(
            f"{JIRA_BASE_URL}/rest/api/3/issue/{key}/comment",
            json=body, headers={"Content-Type": "application/json"},
            timeout=10,
        )
    except requests.RequestException as e:
        return jsonify(error=f"comment post failed: {e}"), 502
    if rc.status_code not in (200, 201):
        return jsonify(error="comment post failed", status=rc.status_code), 502

    # 2. Transition naar AI Queued (als er een transition naar dat target is).
    try:
        tr = _jira_session.get(
            f"{JIRA_BASE_URL}/rest/api/3/issue/{key}/transitions", timeout=10,
        ).json()
        target_name = "AI Queued"
        tr_id = next(
            (t["id"] for t in tr.get("transitions", [])
             if (t.get("to") or {}).get("name") == target_name),
            None,
        )
        if tr_id:
            _jira_session.post(
                f"{JIRA_BASE_URL}/rest/api/3/issue/{key}/transitions",
                json={"transition": {"id": tr_id}},
                headers={"Content-Type": "application/json"},
                timeout=10,
            )
    except Exception as e:
        log.warning("po-answer transition faalde: %s", e)
        # Comment is wel geplaatst — niet fataal.

    return jsonify(ok=True)


@app.route("/api/v1/runner/<job_name>/log", methods=["GET", "OPTIONS"])
@require_jwt
def api_runner_log(job_name: str) -> Response:
    if not _JOB_NAME_RE.match(job_name):
        return jsonify(error="bad job name"), 400
    log_text, status_int = _fetch_pod_log_text(job_name)
    return jsonify(job=job_name, log=log_text, status=status_int)


@app.route("/api/v1/healthz", methods=["GET"])
def api_healthz() -> Response:
    return jsonify(ok=True)


# ─── JSON-serialisatie helpers voor de API ────────────────────────────────


def _state_to_dict(state: dict) -> dict:
    """Plat de top-level state-dict af naar JSON-vriendelijke vorm."""
    return {
        "fetched_at": state.get("fetched_at", ""),
        "main": _main_card_to_dict(state.get("main")),
        "ai_active": [_jira_card_to_dict(c) for c in state.get("ai_active", [])],
        "open_prs":  [_pr_card_to_dict(c)   for c in state.get("open_prs", [])],
        "closed_prs": [
            {
                "number": c.number, "title": c.title,
                "html_url": c.html_url, "merged_age": c.merged_age,
                "branch": c.branch, "merged_at": c.merged_at,
                "head_sha": c.head_sha, "story_key": c.story_key,
                "tokens_input": c.tokens_input,
                "tokens_output": c.tokens_output,
                "tokens_cache_read": c.tokens_cache_read,
                "cost_usd": c.cost_usd,
                "run_count": c.run_count,
            }
            for c in state.get("closed_prs", [])
        ],
    }


def _main_card_to_dict(m) -> Optional[dict]:
    if m is None:
        return None
    return {
        "sha": getattr(m, "sha", ""),
        "sha_age": getattr(m, "sha_age", ""),
        "preview_url": getattr(m, "preview_url", ""),
        "phases": [_phase_to_dict(p) for p in getattr(m, "phases", [])],
    }


def _phase_to_dict(p) -> dict:
    return {"label": p.label, "status": p.status,
            "detail": p.detail, "link": p.link, "since": p.since}


def _jira_card_to_dict(c) -> dict:
    return {
        "key": c.key, "title": c.title, "status": c.status,
        "jira_url": c.jira_url, "age": c.age,
        "job_state": c.job_state, "job_status": c.job_status, "job_name": c.job_name,
        "tokens_input": c.tokens_input, "tokens_output": c.tokens_output,
        "tokens_cache_read": c.tokens_cache_read, "cost_usd": c.cost_usd,
        "ai_level": c.ai_level, "ai_phase": c.ai_phase,
        "run_count": c.run_count,
    }


def _pr_card_to_dict(c) -> dict:
    return {
        "number": c.number, "title": c.title, "html_url": c.html_url,
        "branch": c.branch, "head_sha": c.head_sha, "author": c.author,
        "updated_age": c.updated_age, "preview_url": c.preview_url,
        "last_commit_age": c.last_commit_age,
        "phases": [_phase_to_dict(p) for p in c.phases],
        "jira_status": c.jira_status,
        "ai_phase": c.ai_phase,
        "runner_state": c.runner_state,
        "runner_text": c.runner_text,
        "runner_job_name": c.runner_job_name,
    }


def _story_row_to_dict(r: dict) -> dict:
    """Eén rij uit factory_all_stories() naar JSON-vriendelijk dict."""
    return {
        "id": r["id"],
        "story_key": r["story_key"],
        "started_at": _iso(r.get("started_at")),
        "ended_at":   _iso(r.get("ended_at")),
        "final_status": r["final_status"],
        "input": r["input"], "output": r["output"],
        "cache_read": r["cache_read"], "cache_creation": r["cache_creation"],
        "cost_usd": r["cost_usd"],
        "run_count": r["run_count"],
        "duration_ms_sum": r["duration_ms_sum"],
    }


def _story_timeline_to_dict(data: dict) -> dict:
    """De volledige timeline (story_run + agent_runs)."""
    return {
        "id": data["id"],
        "story_key": data["story_key"],
        "started_at": _iso(data["started_at"]),
        "ended_at":   _iso(data.get("ended_at")),
        "final_status": data.get("final_status"),
        "totals": data["totals"],
        "runs": [_agent_run_to_dict(r) for r in data["runs"]],
    }


def _agent_run_to_dict(r: dict) -> dict:
    return {
        "id": r["id"], "role": r["role"], "job_name": r["job_name"],
        "model": r["model"], "effort": r["effort"], "level": r["level"],
        "started_at": _iso(r.get("started_at")),
        "ended_at":   _iso(r.get("ended_at")),
        "outcome": r["outcome"],
        "input": r["input"], "output": r["output"],
        "cache_read": r["cache_read"], "cache_creation": r["cache_creation"],
        "cost_usd": r["cost_usd"],
        "num_turns": r["num_turns"], "duration_ms": r["duration_ms"],
        "summary_text": r.get("summary_text", ""),
    }


def _agent_summary_dict(r: Optional[dict]) -> Optional[dict]:
    if r is None:
        return None
    return {
        "role": r["role"], "started_at": _iso(r.get("started_at")),
        "outcome": r["outcome"], "summary_text": r.get("summary_text", ""),
    }


def _iso(ts) -> Optional[str]:
    if ts is None:
        return None
    return ts.isoformat() if hasattr(ts, "isoformat") else str(ts)


def _fetch_pod_log_text(job_name: str) -> tuple[str, int]:
    """Pak de Pod van een runner-Job + pak de logs. Returnt (text, status_code).
    Gebruikt door zowel HTML-route als API-route."""
    try:
        pods = kubectl_json("get", "pod", "-n", FACTORY_NS,
                             "-l", f"job-name={job_name}", "-o", "json")
    except Exception:
        return ("(pod fetch faalde)", 502)
    items = (pods or {}).get("items", []) or []
    if not items:
        return ("(geen pod gevonden voor deze job)", 404)
    pod_name = items[0]["metadata"]["name"]
    try:
        out = subprocess.run(
            ["oc", "logs", pod_name, "-n", FACTORY_NS, "--tail=2000"],
            capture_output=True, text=True, timeout=15,
        )
        return (out.stdout or out.stderr, 0 if out.returncode == 0 else out.returncode)
    except Exception as e:
        return (f"(log fetch faalde: {e})", 502)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8080)
