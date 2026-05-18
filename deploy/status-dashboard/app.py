#!/usr/bin/env python3
"""
Personal News Feed — status dashboard backend.

JSON-API op /api/v1/* die GitHub + ArgoCD + Kubernetes + JIRA + de
factory-DB (token/cost-statistieken per story-run) samenharkt. De
Flutter-dashboard-app (dashboard.vdzonsoftware.nl) consumeert deze API.

Data-bronnen:
  * GitHub REST API   — PR's, workflow runs, jobs per run
  * Kubernetes API    — Application-objecten (argocd) + pods (alle
                        personal-news-feed + pnf-pr-*) + claude-runner
                        Jobs (factory)
  * JIRA REST API     — story-status, custom-fields, comments
  * Factory-DB        — agent_runs + story_runs uit de claude-runner-flow

De oude server-side HTML-pagina is verwijderd (2026-05-17) — de
Flutter-app is de single source of truth voor de UI. Alleen
/api/v1/healthz blijft als anoniem endpoint voor K8s-probes.
"""

import base64
import json
import logging
import os
import re
import subprocess
import time
from dataclasses import dataclass, field
from typing import Optional

import requests
from flask import Flask, Response, jsonify, request

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
# OpenShift-console host voor klikbare "Open in OpenShift"-links per
# K8s Job. Leeg laten = de frontend toont alleen de jobnaam zonder link.
OPENSHIFT_CONSOLE_HOST = os.environ.get("OPENSHIFT_CONSOLE_HOST", "")

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


def gh_recent_runs_for_branch(branch: str, limit: int = 10) -> list[dict]:
    """Recente workflow-runs voor een branch (over alle workflows). Voor
    de 'recente builds'-lijsten op het dashboard. Nieuwste eerst."""
    data = gh(
        f"/repos/{GITHUB_OWNER}/{GITHUB_REPO}/actions/runs",
        params={"branch": branch, "per_page": str(limit)},
    )
    if not data:
        return []
    return data.get("workflow_runs") or []


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


def kubectl_run(*args, input_data: Optional[str] = None,
                timeout: int = 15) -> subprocess.CompletedProcess:
    """kubectl-wrapper voor mutaties (apply/delete/patch). Geen JSON-
    parsing; caller leest stdout/stderr + returncode zelf zodat 'ie
    een nette HTTP-fout kan terugsturen aan de client.

    Logged niet automatisch bij fout — het log-niveau hangt af van
    of de fout fataal is voor de caller (bv. 409 op delete = OK)."""
    return subprocess.run(
        ["kubectl", *args],
        input=input_data,
        capture_output=True, text=True,
        timeout=timeout, check=False,
    )


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
    "level":        "AI Level",
    "phase":        "AI Phase",
    "token_budget": "AI Token Budget",
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
    # Recente GH-workflow-runs voor main (top 10). Voor de 'recente builds'-
    # lijst op de Production-kaart van het Flutter-dashboard.
    recent_runs: list[dict] = field(default_factory=list)


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
    # Budget-info (KAN-42). 0 = veld leeg (geen budget-balk in UI).
    token_budget: int = 0


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
        recent_runs=[
            {
                "id": r.get("id"),
                "name": r.get("name", ""),
                "status": r.get("status", ""),          # queued/in_progress/completed
                "conclusion": r.get("conclusion", ""),  # success/failure/cancelled/...
                "html_url": r.get("html_url", ""),
                "created_at": r.get("created_at", ""),
                "updated_at": r.get("updated_at", ""),
                "event": r.get("event", ""),
                "head_sha": (r.get("head_sha") or "")[:7],
                "age": _ago(r.get("updated_at", "") or r.get("created_at", "")),
            }
            for r in gh_recent_runs_for_branch("main", limit=10)
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
        budget_id = _ai_field_id("token_budget")
        if lvl_id and fields.get(lvl_id) is not None:
            try:
                card.ai_level = int(fields[lvl_id])
            except (TypeError, ValueError):
                pass
        if phase_id:
            card.ai_phase = fields.get(phase_id) or ""
        if budget_id and fields.get(budget_id) is not None:
            try:
                card.token_budget = int(fields[budget_id])
            except (TypeError, ValueError):
                pass
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


# ─── flask ────────────────────────────────────────────────────────────────


app = Flask(__name__)


# Story-key: KAN-XX of vergelijkbaar. Beperk wat we accepteren — voorkomt
# path-injection en houdt de DB-query simpel. Gebruikt door alle
# /api/v1/stories/<key>/* routes als eerste guard.
_STORY_KEY_RE = re.compile(r"^[A-Z][A-Z0-9]+-[0-9]+$")


def _jira_fetch_issue_title(key: str) -> str:
    """Eén losse JIRA-call om de issue-titel te krijgen. Best-effort —
    op fout geven we gewoon "" terug zodat de handover-pagina alsnog
    rendert met alleen de KAN-key als kop."""
    meta = _jira_fetch_issue_meta(key)
    return meta.get("title", "")


def _jira_fetch_issue_meta(key: str) -> dict:
    """Eén JIRA-call die summary + status + ai-phase + token_budget
    ophaalt. Output: {title, status, ai_phase, token_budget}.
    Best-effort: lege strings/0 op fout."""
    out = {"title": "", "status": "", "ai_phase": "", "token_budget": 0}
    if not (JIRA_BASE_URL and JIRA_EMAIL and JIRA_API_KEY):
        return out
    _discover_ai_field_ids()
    phase_field_id = _ai_field_id_cache.get("phase")
    budget_field_id = _ai_field_id_cache.get("token_budget")
    fields_param = "summary,status"
    if phase_field_id:
        fields_param += f",{phase_field_id}"
    if budget_field_id:
        fields_param += f",{budget_field_id}"
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
    if budget_field_id and f.get(budget_field_id) is not None:
        try:
            out["token_budget"] = int(f[budget_field_id])
        except (TypeError, ValueError):
            pass
    return out


_VALID_COMMANDS = {"delete", "merge", "pause", "re-implement"}


def _post_jira_raw_comment(key: str, text: str) -> bool:
    """Post een vrije-tekst comment naar JIRA. Gebruikt door alle
    command/budget-flows hier."""
    if not (JIRA_BASE_URL and JIRA_EMAIL and JIRA_API_KEY):
        return False
    adf = {
        "body": {
            "type": "doc", "version": 1,
            "content": [{
                "type": "paragraph",
                "content": [{"type": "text", "text": text}],
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
        log.warning("post-comment %s faalde: %s", key, e)
        return False
    return r.status_code in (200, 201)


def _post_jira_command_comment(key: str, command: str) -> bool:
    """Post een @claude:command:<cmd>-comment naar JIRA. De poller pikt
    'm op in de volgende tick en voert 't commando uit."""
    return _post_jira_raw_comment(key, f"@claude:command:{command} (via dashboard)")


# ─── JSON API (Flutter-dashboard backend) ────────────────────────────────
#
# Alle endpoints zitten onder /api/v1/*. CORS is open voor de Flutter-
# dashboard-origin (default https://dashboard.vdzonsoftware.nl). Auth via
# simpele JWT met één user 'admin' (wachtwoord in DASHBOARD_ADMIN_PASSWORD
# env-var). De oude server-side HTML-pagina is verwijderd (2026-05-17);
# de Flutter-app is single source of truth voor de UI.
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
    resp.headers["Access-Control-Allow-Methods"] = "GET, POST, DELETE, OPTIONS"
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
        "token_budget": meta.get("token_budget", 0),
        "prs": gh_prs_for_branch(f"ai/{key}"),
        "commits": gh_commits_for_branch(f"ai/{key}", limit=30),
        "pr_builds": [
            {
                "id": r.get("id"),
                "name": r.get("name", ""),
                "status": r.get("status", ""),
                "conclusion": r.get("conclusion", ""),
                "html_url": r.get("html_url", ""),
                "created_at": r.get("created_at", ""),
                "updated_at": r.get("updated_at", ""),
                "event": r.get("event", ""),
                "head_sha": (r.get("head_sha") or "")[:7],
                "age": _ago(r.get("updated_at", "") or r.get("created_at", "")),
            }
            for r in gh_recent_runs_for_branch(f"ai/{key}", limit=15)
        ],
    })


@app.route("/api/v1/stories/<key>/handover", methods=["GET", "OPTIONS"])
@require_jwt
def api_story_handover(key: str) -> Response:
    if not _STORY_KEY_RE.match(key):
        return jsonify(error="bad key"), 400
    data = factory_story_timeline(key)
    if data is None:
        return jsonify(error="not found"), 404
    runs = data["runs"]

    def all_by_role(role: str) -> list[dict]:
        return [d for d in (_agent_summary_dict(r) for r in runs if r.get("role") == role) if d]

    return jsonify({
        "story_key": key,
        "jira_title": _jira_fetch_issue_title(key),
        # Alle runs per rol (oudst eerst), zodat een story die heen-en-weer
        # ging tussen developer ↔ reviewer alle iteraties laat zien.
        "refiner":   all_by_role("refiner"),
        "developer": all_by_role("developer"),
        "reviewer":  all_by_role("reviewer"),
        "tester":    all_by_role("tester"),
        "po_dialogue": _po_dialogue_from_jira(key),
    })


def _po_dialogue_from_jira(key: str) -> list[dict]:
    """Loop alle comments oudste-eerst, koppel elke agent-vraag-comment
    aan de eerstvolgende niet-agent-comment (= het PO-antwoord). Result:
    chronologische lijst {agent, question_text, question_created,
    answer_text, answer_created}. Lege lijst als JIRA niet geconfigureerd
    of geen vragen."""
    if not (JIRA_BASE_URL and JIRA_EMAIL and JIRA_API_KEY):
        return []
    try:
        r = _jira_session.get(
            f"{JIRA_BASE_URL}/rest/api/3/issue/{key}/comment",
            params={"maxResults": "200", "orderBy": "+created"},
            timeout=10,
        )
    except requests.RequestException:
        return []
    if r.status_code != 200:
        return []
    comments = r.json().get("comments", []) or []

    # Detecteer agent-vragen (REFINER/REVIEWER/TESTER + "Vragen" in body).
    out: list[dict] = []
    pending: Optional[dict] = None  # de laatste open agent-vraag
    for c in comments:
        body = c.get("body")
        text = _adf_to_plain(body).strip() if body else ""
        if not text:
            continue
        m = _PO_QUESTION_RE.search(text)
        is_agent_question = bool(m) and "Vragen" in text
        is_agent_msg = bool(re.match(r"^\[(REFINER|REVIEWER|TESTER|DEVELOPER)\]", text))
        is_factory_marker = "[factory]" in text.lower() or "@claude:command" in text.lower()
        if is_agent_question:
            agent = (m.group(1) or m.group(2) or "").lower() if m else ""
            pending = {
                "agent": agent,
                "question_text": text,
                "question_created": c.get("created"),
                "answer_text": "",
                "answer_created": None,
            }
            out.append(pending)
            continue
        # Niet-agent + niet-factory-marker = PO-comment. Koppel aan de
        # laatste openstaande vraag.
        if pending and not is_agent_msg and not is_factory_marker and not pending["answer_text"]:
            pending["answer_text"] = text
            pending["answer_created"] = c.get("created")
            pending = None
    return out


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


# Resume-mapping: per AI Phase de juiste (target_status, target_phase).
# Voor de '-ing'-phases (job liep mid-stride toen pause kwam) rollen we
# de phase een stap terug naar de voorgaande '-ed/-ok'-vorm zodat de
# poller's dispatch-regels de juiste rol opnieuw spawnen. Voor de overige
# phases laten we de phase staan en mikken alleen op het juiste status-
# bucket. target_phase = None betekent: phase ongewijzigd.
_RESUME_MAP: dict[str, tuple[str, Optional[str]]] = {
    # interrupted (mid-run, killed door pause): phase terugrollen
    "refining":         ("AI Ready",     None),
    "developing":       ("AI Queued",    "refined"),
    "reviewing":        ("AI IN REVIEW", "developed"),
    "testing":          ("AI IN REVIEW", "reviewed-ok"),
    # voltooide phases — poller pikt 'm op via z'n bestaande dispatch
    "refined":          ("AI Queued",    None),
    "developed":        ("AI IN REVIEW", None),
    "reviewed-ok":      ("AI IN REVIEW", None),
    "reviewed-changes": ("AI IN REVIEW", None),
    "tested-fail":      ("AI IN REVIEW", None),
    "tested-ok":        ("AI IN REVIEW", None),
    # PO-vraag of budget-pauze: resume via stap 3 (gebruikt resume_phase)
    "awaiting-po":      ("AI Queued",    None),
}


@app.route("/api/v1/stories/<key>/resume", methods=["POST", "OPTIONS"])
@require_jwt
def api_story_resume(key: str) -> Response:
    """Hervat een gepauzeerde story. Target-status wordt afgeleid uit
    de huidige AI Phase via _RESUME_MAP — een tester die mid-run werd
    gekilled (phase=testing) wordt zo correct naar AI IN REVIEW +
    reviewed-ok teruggezet zodat de poller een nieuwe tester spawnt.
    Lege/onbekende phase → AI Ready (refiner draait from scratch).

    Optioneel body {"budget_value": <int>}: zet AI Token Budget eerst
    op die waarde (direct op het custom-field, geen comment).
    """
    if request.method == "OPTIONS":
        return _add_cors_headers(Response("", status=204))
    if not _STORY_KEY_RE.match(key):
        return jsonify(error="bad key"), 400
    if not (JIRA_BASE_URL and JIRA_EMAIL and JIRA_API_KEY):
        return jsonify(error="JIRA not configured"), 503

    data = request.get_json(silent=True) or {}
    budget_set = None
    raw = data.get("budget_value")
    if raw is not None:
        try:
            budget_set = int(raw)
        except (TypeError, ValueError):
            return jsonify(error="budget_value moet een int zijn"), 400
        if budget_set <= 0:
            return jsonify(error="budget_value moet > 0 zijn"), 400

    # Stap 0: huidige phase ophalen om de juiste target af te leiden.
    meta = _jira_fetch_issue_meta(key)
    current_phase = (meta.get("ai_phase") or "").strip()
    target_status, target_phase = _RESUME_MAP.get(
        current_phase, ("AI Ready", None)
    )

    # Stap 1: optioneel budget + (eventueel) phase-rewind in één PUT.
    _discover_ai_field_ids()
    fields_payload: dict[str, object] = {}
    if budget_set is not None:
        bid = _ai_field_id_cache.get("token_budget")
        if not bid:
            return jsonify(error="AI Token Budget custom-field niet gevonden"), 500
        fields_payload[bid] = budget_set
    if target_phase is not None and target_phase != current_phase:
        pid = _ai_field_id_cache.get("phase")
        if not pid:
            return jsonify(error="AI Phase custom-field niet gevonden"), 500
        fields_payload[pid] = target_phase
    if fields_payload:
        try:
            r = _jira_session.put(
                f"{JIRA_BASE_URL}/rest/api/3/issue/{key}",
                json={"fields": fields_payload},
                headers={"Content-Type": "application/json"},
                timeout=10,
            )
        except requests.RequestException as e:
            return jsonify(error=f"field-update faalde: {e}"), 502
        if r.status_code not in (200, 204):
            return jsonify(error=f"field-update HTTP {r.status_code}"), 502

    # Stap 2: transitie. Lookup id dynamisch want cache leeft per pod.
    try:
        tr = _jira_session.get(
            f"{JIRA_BASE_URL}/rest/api/3/issue/{key}/transitions", timeout=10,
        ).json()
    except requests.RequestException as e:
        return jsonify(error=f"transitions lookup faalde: {e}"), 502
    target_id = None
    for t in tr.get("transitions", []):
        if (t.get("to") or {}).get("name") == target_status:
            target_id = t.get("id")
            break
    if not target_id:
        return jsonify(
            error=f"transitie naar {target_status!r} niet beschikbaar"
        ), 409
    try:
        rt = _jira_session.post(
            f"{JIRA_BASE_URL}/rest/api/3/issue/{key}/transitions",
            json={"transition": {"id": target_id}},
            headers={"Content-Type": "application/json"},
            timeout=10,
        )
    except requests.RequestException as e:
        return jsonify(error=f"transitie faalde: {e}"), 502
    if rt.status_code not in (200, 204):
        return jsonify(error=f"transitie HTTP {rt.status_code}"), 502
    return jsonify(
        ok=True, key=key,
        from_phase=current_phase or None,
        to_status=target_status,
        to_phase=target_phase,
        budget_set=budget_set,
    )


@app.route("/api/v1/stories/<key>/active-job", methods=["GET", "OPTIONS"])
@require_jwt
def api_story_active_job(key: str) -> Response:
    """Geef de huidig-lopende claude-runner Job voor een story terug. Bron:
    K8s Jobs met label app=claude-runner,story-id=<kebab-key> die nog
    niet Complete/Failed zijn. agent_runs is geen bruikbare bron — die
    krijgt pas een rij ná afloop van de run.
    """
    if not _STORY_KEY_RE.match(key):
        return jsonify(error="bad key"), 400
    story_label = re.sub(r"[^a-z0-9-]+", "-", key.lower()).strip("-")[:30]
    try:
        jobs = k8s_jobs(
            FACTORY_NS,
            label_selector=f"app=claude-runner,story-id={story_label}",
        )
    except Exception as e:
        log.warning("active-job kubectl faalde: %s", e)
        return jsonify(active=None)
    # Alleen niet-afgeronde Jobs (geen Complete/Failed condition).
    running = []
    for j in jobs:
        conds = j.get("status", {}).get("conditions", []) or []
        done = any(
            c.get("type") in ("Complete", "Failed") and c.get("status") == "True"
            for c in conds
        )
        if done:
            continue
        running.append(j)
    if not running:
        return jsonify(active=None)
    # Pak de jongste (creationTimestamp DESC).
    running.sort(
        key=lambda j: j.get("metadata", {}).get("creationTimestamp", ""),
        reverse=True,
    )
    j = running[0]
    meta = j.get("metadata", {}) or {}
    job_name = meta.get("name", "")
    labels = meta.get("labels", {}) or {}
    role = labels.get("role") or "agent"
    # `startTime` op de Job-status is het moment dat de eerste pod start;
    # creationTimestamp is het schedule-moment. We tonen startTime indien
    # beschikbaar (kortste 'echte' duur), met creationTimestamp als fallback.
    started_at = (
        j.get("status", {}).get("startTime")
        or meta.get("creationTimestamp")
        or ""
    )
    console_url = (
        f"https://{OPENSHIFT_CONSOLE_HOST}/k8s/ns/{FACTORY_NS}/jobs/{job_name}"
        if OPENSHIFT_CONSOLE_HOST else ""
    )
    return jsonify(active={
        "id": 0,
        "role": role,
        "job_name": job_name,
        "started_at": started_at,
        "console_url": console_url,
    })


# Patroon voor [REFINER]/[REVIEWER]/[TESTER]-vragen-comments — de
# laatste daarvan is de PO-vraag waar nu op gewacht wordt.
_PO_QUESTION_RE = re.compile(r"^\[(REFINER|REVIEWER|TESTER)\]\s+Vragen|^\[(REFINER|REVIEWER|TESTER)\]", re.MULTILINE)


@app.route("/api/v1/stories/<key>/po-question", methods=["GET", "OPTIONS"])
@require_jwt
def api_story_po_question(key: str) -> Response:
    """Geef de laatste agent-vraag terug uit de JIRA-comment-thread.
    Returnt null als de story niet in 'AI Needs Info' staat — een vraag
    die al beantwoord is, is geen actieve vraag meer (de poller heeft de
    status dan al weggetransitioned)."""
    if not _STORY_KEY_RE.match(key):
        return jsonify(error="bad key"), 400
    if not (JIRA_BASE_URL and JIRA_EMAIL and JIRA_API_KEY):
        return jsonify(question=None)
    # Gate op JIRA-status: alleen bij 'AI Needs Info' is er een
    # openstaande vraag voor de PO. Voorkomt stale vragen op stories die
    # al verder zijn (zoals KAN-46 waar de refiner z'n vraag al verwerkt
    # heeft).
    meta = _jira_fetch_issue_meta(key)
    if meta.get("status", "") != "AI Needs Info":
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


@app.route("/api/v1/stories/<key>/attachments", methods=["GET", "OPTIONS"])
@require_jwt
def api_story_attachments(key: str) -> Response:
    """Geef alle image-attachments van een JIRA-issue terug. Bedoeld voor
    tester-screenshots. URLs zijn relatief naar /api/v1/...
    /attachments/<id>/raw zodat de frontend ze met de eigen JWT kan
    laden zonder JIRA-creds te kennen."""
    if not _STORY_KEY_RE.match(key):
        return jsonify(error="bad key"), 400
    if not (JIRA_BASE_URL and JIRA_EMAIL and JIRA_API_KEY):
        return jsonify(attachments=[])
    try:
        r = _jira_session.get(
            f"{JIRA_BASE_URL}/rest/api/3/issue/{key}",
            params={"fields": "attachment"},
            timeout=10,
        )
    except requests.RequestException:
        return jsonify(attachments=[])
    if r.status_code != 200:
        return jsonify(attachments=[])
    atts = ((r.json().get("fields") or {}).get("attachment") or [])
    out = []
    for a in atts:
        mime = a.get("mimeType", "") or ""
        if not mime.startswith("image/"):
            continue
        aid = str(a.get("id", ""))
        out.append({
            "id": aid,
            "filename": a.get("filename", ""),
            "mime_type": mime,
            "size": a.get("size", 0),
            "created": a.get("created", ""),
            "raw_url": f"/api/v1/stories/{key}/attachments/{aid}/raw",
        })
    # Oudste eerst — screenshots zijn typisch in test-volgorde.
    out.sort(key=lambda x: x.get("created", ""))
    return jsonify(attachments=out)


# Story-key matched op de outer regex; attachment-id beperken tot cijfers
# voor veiligheid (JIRA attachment IDs zijn numeriek).
@app.route(
    "/api/v1/stories/<key>/attachments/<aid>/raw",
    methods=["GET", "OPTIONS"],
)
@require_jwt
def api_story_attachment_raw(key: str, aid: str) -> Response:
    """Proxy: stream de attachment-bytes van JIRA terug naar de client
    met onze eigen credentials. Caller hoeft dus geen JIRA-token te
    kennen — JWT is genoeg."""
    if not _STORY_KEY_RE.match(key):
        return jsonify(error="bad key"), 400
    if not re.match(r"^[0-9]+$", aid):
        return jsonify(error="bad attachment id"), 400
    if not (JIRA_BASE_URL and JIRA_EMAIL and JIRA_API_KEY):
        return jsonify(error="JIRA not configured"), 503
    try:
        r = _jira_session.get(
            f"{JIRA_BASE_URL}/rest/api/3/attachment/content/{aid}",
            stream=True,
            timeout=15,
            allow_redirects=True,
        )
    except requests.RequestException as e:
        return jsonify(error=f"upstream: {e}"), 502
    if r.status_code != 200:
        return jsonify(error="upstream", status=r.status_code), r.status_code
    # Pass through MIME-type + body. Geen streaming naar Flask-Response
    # nodig — screenshots zijn klein (≤ enkele MB).
    return Response(
        r.content,
        mimetype=r.headers.get("Content-Type", "application/octet-stream"),
    )


# ─── Claude-tab: factory-agents + interactieve sessies (KAN-61) ──────────
#
# Twee endpoint-groepen voor de "Claude"-tab van het dashboard:
#
#   GET  /api/v1/claude-factory-agents
#       Read-only lijst van actief draaiende claude-runner Jobs (de
#       factory-pipeline: refiner/developer/reviewer/tester). Eén kaartje
#       per Job met story_key + rol + duur + status.
#
#   GET    /api/v1/claude-sessions
#   POST   /api/v1/claude-sessions          {"name": "<uniek>"}
#   DELETE /api/v1/claude-sessions/<name>
#       Long-running interactieve pods met de Claude Code CLI in
#       /remote-modus. Verschijnen in de Anthropic Claude-app van de PO
#       zodat 'ie vanaf z'n mobiel cluster-commando's kan sturen.
#
# Beide bronnen leven onder K8s-labels:
#   app=claude-runner       — factory-Jobs (jira-poller spawnt)
#   app=claude-interactive  — interactieve sessies (dit dashboard spawnt)

# Hard cap: max N actieve interactieve sessies tegelijk. Story KAN-61
# spreekt over "per user" maar het dashboard heeft één admin-account,
# dus de cap is effectief systeem-breed.
MAX_INTERACTIVE_SESSIONS = int(
    os.environ.get("MAX_INTERACTIVE_SESSIONS", "3")
)
CLAUDE_INTERACTIVE_IMAGE = os.environ.get(
    "CLAUDE_INTERACTIVE_IMAGE",
    "ghcr.io/robbertvdzon/claude-tester:main",
)
CLAUDE_INTERACTIVE_SA = os.environ.get(
    "CLAUDE_INTERACTIVE_SA", "claude-interactive"
)
REPO_URL = os.environ.get(
    "REPO_URL",
    "https://github.com/robbertvdzon/personal-news-feed-by-claude-code.git",
)

# K8s-veilige sessienaam: kleine letters/cijfers/streepjes, 1–22 tekens.
# Beperkt OOK wat we als pad-parameter accepteren in DELETE.
#
# Waarom 22 chars? Een pod-naam moet binnen de DNS-1123 label-limit van
# 63 chars passen. Voor een Job-aangemaakte pod is dat
#   "claude-interactive-" (19) + <name> + "-YYYYMMDD-HHMMSS" (16) + "-XXXXX" (6) = 41 + len(name)
# zodat 63 - 41 = 22 chars max voor de user-input. Bij hogere waarden
# truncated K8s meestal, maar dan komt de naam in de UI niet overeen
# met de werkelijke pod-naam — beter strak afdwingen aan de bron.
_SESSION_NAME_RE = re.compile(r"^[a-z][a-z0-9-]{0,21}$")
_SESSION_NAME_MAX = 22

# Entrypoint-script dat in de pod draait. We injecteren 'm via
# `command: ["bash", "-c", <script>]` op de Job-container; géén ConfigMap-
# mount (voorheen was dat wel zo, maar dat kostte een extra RBAC-rule
# voor configmaps/create én een 2e kubectl-apply met owner-ref-patch —
# foutgevoelig). Self-contained in deze module zodat het dashboard-image
# geen kustomize-mount nodig heeft.
_INTERACTIVE_ENTRYPOINT_SH = r"""set -euo pipefail
echo "[claude-interactive] sessie '${SESSION_NAME:-?}' start"
for v in CLAUDE_CODE_OAUTH_TOKEN GITHUB_TOKEN REPO_URL SESSION_NAME; do
  if [[ -z "${!v:-}" ]]; then
    echo "FATAL: env $v is leeg" >&2; exit 1
  fi
done
rm -rf /work/repo
mkdir -p /work
cd /work
echo "[claude-interactive] git clone $REPO_URL → /work/repo"
git -c credential.helper="!f() { echo username=token; echo password=$GITHUB_TOKEN; }; f" \
    clone --depth 1 --branch main "$REPO_URL" repo
cd /work/repo
cat > /tmp/welcome.md <<EOF
# Interactieve Claude-sessie — '${SESSION_NAME}'

Je hebt **admin-RBAC** op het cluster (cluster-admin ClusterRoleBinding).
Wees voorzichtig: een verkeerd commando kan productie raken.

Scope:
- pnf-software-factory  → schrijfbaar
- personal-news-feed    → schrijfbaar (PRODUCTIE — let op)
- pnf-pr-*              → schrijfbaar (previews)
- argocd                → schrijfbaar

Tools: claude, kubectl, oc, psql, git, gh, jq, Playwright/Chromium.
DB: \$PNF_DATABASE_URL en \$FACTORY_DATABASE_URL.
Werkdirectory: /work/repo (verse clone van main bij start).

Stop via de dashboard-knop — geen exit-on-idle.
EOF
echo "[claude-interactive] welkomstprompt → /tmp/welcome.md"

# ----- pre-seed claude-config zodat de first-run wizard niet blokkeert -----
# Verse pod = lege $HOME, dus claude toont anders eerst:
#   1) "kies een theme" (pijltjes-prompt)
#   2) tos-acceptatie
#   3) "what's new in deze versie"
# Onze `echo /remote` op stdin wordt door die wizard opgeslokt en bereikt
# claude nooit. Met hasCompletedOnboarding=true + theme prefilled slaat
# claude alle drie de stappen over en is /remote de eerste echte input.
CLAUDE_VER="$(claude --version 2>/dev/null | awk '{print $1}')"
mkdir -p "$HOME/.claude"
cat > "$HOME/.claude.json" <<JSON
{
  "hasCompletedOnboarding": true,
  "lastOnboardingVersion": "${CLAUDE_VER:-99.99.99}",
  "numStartups": 1,
  "projects": {
    "/work/repo": {
      "hasTrustDialogAccepted": true,
      "hasClaudeMdExternalIncludesApproved": true,
      "projectOnboardingSeenCount": 1,
      "allowedTools": []
    }
  }
}
JSON
cat > "$HOME/.claude/settings.json" <<'JSON'
{
  "theme": "dark"
}
JSON
echo "[claude-interactive] config pre-seeded (skip onboarding wizard + folder-trust)"

echo "[claude-interactive] claude start in /remote-control-modus…"
# Claude's TUI heeft een slash-command-autocomplete die opent zodra "/"
# verschijnt. Volgorde voor 'm robuust te triggeren:
#   1. wacht 10s tot claude volledig boot-ready is (welkomst-banner +
#      marketplace-notificatie + lege prompt)
#   2. typ "/remote-control" + \n  → autocomplete pakt de exact-match
#   3. wacht 2s zodat claude de autocomplete-selectie verwerkt
#   4. extra \n → submit (= execute van het slash-command)
{
  sleep 10
  printf '/remote-control\n'
  sleep 2
  printf '\n'
  tail -f /dev/null
} | script -q -c "claude --append-system-prompt \"$(cat /tmp/welcome.md)\"" /dev/null
echo "[claude-interactive] claude is afgesloten — exit"
"""


def _sanitize_session_name(name: str) -> str:
    """Strip + lowercase de PO-input voor regex-validatie."""
    return (name or "").strip().lower()


def _interactive_job_name(name: str) -> str:
    stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
    short = _sanitize_session_name(name)[:_SESSION_NAME_MAX]
    return f"claude-interactive-{short}-{stamp}"


def _job_is_active(job: dict) -> bool:
    """True zolang de Job nog niet Complete/Failed is."""
    conds = (job.get("status") or {}).get("conditions") or []
    for c in conds:
        if c.get("status") == "True" and c.get("type") in ("Complete", "Failed"):
            return False
    return True


def _factory_agent_to_dict(job: dict) -> dict:
    """Eén claude-runner Job → API-shape voor de dashboard-tab."""
    meta = job.get("metadata") or {}
    labels = meta.get("labels") or {}
    status = job.get("status") or {}
    job_name = meta.get("name", "")
    started_at = status.get("startTime") or meta.get("creationTimestamp") or ""
    # status: 'running' / 'completing' / 'failed' / 'finished'.
    # We exposen ook nog 'finished' voor compleetheid; de UI filtert
    # zelf op niet-finished.
    conds = status.get("conditions") or []
    complete = any(c.get("type") == "Complete" and c.get("status") == "True" for c in conds)
    failed = any(c.get("type") == "Failed" and c.get("status") == "True" for c in conds)
    active = int(status.get("active", 0) or 0)
    succeeded = int(status.get("succeeded", 0) or 0)
    if failed:
        state = "failed"
    elif complete:
        state = "finished"
    elif active == 0 and succeeded > 0:
        state = "completing"
    else:
        state = "running"
    # story_key: terug uit het label 'story-id' (kebab-lowercase, bv.
    # 'kan-61'). Voor UI willen we de oorspronkelijke KAN-61.
    story_label = labels.get("story-id", "") or ""
    story_key = story_label.upper() if story_label else ""
    return {
        "job_name": job_name,
        "story_key": story_key,
        "role": labels.get("role") or "",
        "mode": labels.get("mode") or "",
        "started_at": started_at,
        "state": state,
    }


def _session_to_dict(job: dict) -> dict:
    """Eén claude-interactive Job → API-shape voor het sessie-kaartje."""
    meta = job.get("metadata") or {}
    labels = meta.get("labels") or {}
    status = job.get("status") or {}
    conds = status.get("conditions") or []
    complete = any(c.get("type") == "Complete" and c.get("status") == "True" for c in conds)
    failed = any(c.get("type") == "Failed" and c.get("status") == "True" for c in conds)
    if failed:
        state = "failed"
    elif complete:
        state = "stopped"
    else:
        state = "running"
    return {
        "name": labels.get("session-name", "") or "",
        "job_name": meta.get("name", ""),
        "started_at": status.get("startTime") or meta.get("creationTimestamp") or "",
        "state": state,
    }


def _list_active_interactive_jobs() -> list[dict]:
    """Alle claude-interactive Jobs die nog niet Complete/Failed zijn."""
    items = k8s_jobs(FACTORY_NS, label_selector="app=claude-interactive")
    return [j for j in items if _job_is_active(j)]


def _build_interactive_resources(name: str) -> dict:
    """Bouw de Job-spec voor één interactieve sessie. Het entrypoint-
    script wordt inline meegegeven als `bash -c <script>` — voorheen
    ging dat via een ConfigMap-mount, maar dat vereiste een extra
    RBAC-rule (configmaps/create) en een follow-up owner-ref-patch.
    Inline is simpeler én cluster-pod heeft geen extra mount nodig."""
    job_name = _interactive_job_name(name)
    labels = {
        "app": "claude-interactive",
        "session-name": _sanitize_session_name(name)[:_SESSION_NAME_MAX],
    }
    env = [
        {"name": "SESSION_NAME", "value": _sanitize_session_name(name)[:_SESSION_NAME_MAX]},
        {"name": "REPO_URL", "value": REPO_URL},
        {"name": "GITHUB_OWNER", "value": GITHUB_OWNER},
        {"name": "GITHUB_REPO", "value": GITHUB_REPO},
        # OAuth-token = de sleutel die de pod aan de PO's Anthropic-
        # account koppelt; daardoor verschijnt de sessie binnen ~30s in
        # de mobiele Claude-app. ANTHROPIC_API_KEY NIET óók meegeven,
        # die zou de OAuth-route overschrijven (zie poller.py).
        _secret_env("CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_CODE_OAUTH_TOKEN"),
        _secret_env("GITHUB_TOKEN", "GITHUB_TOKEN"),
        _secret_env("JIRA_API_KEY", "ATLASSIAN_API_KEY"),
        _secret_env("PNF_DATABASE_URL", "PNF_DATABASE_URL"),
        _secret_env("FACTORY_DATABASE_URL", "FACTORY_DATABASE_URL"),
        # Whisper = OpenAI's audio-API; in deze repo onder PNF_OPENAI_API_KEY.
        _secret_env("PNF_OPENAI_API_KEY", "PNF_OPENAI_API_KEY"),
        _secret_env("PNF_TAVILY_API_KEY", "PNF_TAVILY_API_KEY"),
        _secret_env("PNF_ELEVENLABS_API_KEY", "PNF_ELEVENLABS_API_KEY"),
    ]
    job = {
        "apiVersion": "batch/v1",
        "kind": "Job",
        "metadata": {
            "name": job_name,
            "namespace": FACTORY_NS,
            "labels": labels,
        },
        "spec": {
            # 1u TTL na finish: pod-logs blijven nog even oproepbaar.
            "ttlSecondsAfterFinished": 3600,
            # backoffLimit + restartPolicy=OnFailure: K8s herstart de
            # pod automatisch bij crash; volledig opgeven na 3 backoffs.
            "backoffLimit": 3,
            "template": {
                "metadata": {"labels": labels},
                "spec": {
                    "restartPolicy": "OnFailure",
                    "serviceAccountName": CLAUDE_INTERACTIVE_SA,
                    "containers": [
                        {
                            "name": "claude",
                            "image": CLAUDE_INTERACTIVE_IMAGE,
                            "imagePullPolicy": "Always",
                            # Inline entrypoint via `bash -c <script>` —
                            # geen ConfigMap-mount nodig. Bash leest het
                            # script als positional $0, niet als stdin,
                            # dus stdin blijft beschikbaar voor claude.
                            "command": ["bash", "-c", _INTERACTIVE_ENTRYPOINT_SH],
                            "tty": True,
                            "stdin": True,
                            "env": env,
                            "resources": {
                                "requests": {"cpu": "100m", "memory": "256Mi"},
                                "limits": {"cpu": "500m", "memory": "1Gi"},
                            },
                        },
                    ],
                },
            },
        },
    }
    return job


def _secret_env(name: str, key: str) -> dict:
    return {
        "name": name,
        "valueFrom": {
            "secretKeyRef": {"name": "newsfeed-api-keys", "key": key},
        },
    }


def _factory_db_record_session(name: str, job_name: str) -> None:
    """Best-effort: schrijf een rij in factory.interactive_sessions. Faalt
    silent als DB onbereikbaar — K8s blijft de leidende state."""
    if not _factory_db_available():
        return
    try:
        with psycopg.connect(FACTORY_DATABASE_URL) as conn, conn.cursor() as cur:
            cur.execute(
                """INSERT INTO factory.interactive_sessions
                       (name, job_name, status)
                   VALUES (%s, %s, 'running')
                   ON CONFLICT (job_name) DO NOTHING""",
                (name, job_name),
            )
            conn.commit()
    except Exception as e:
        log.warning("interactive_sessions insert faalde: %s", e)


def _factory_db_mark_stopped(job_name: str) -> None:
    if not _factory_db_available():
        return
    try:
        with psycopg.connect(FACTORY_DATABASE_URL) as conn, conn.cursor() as cur:
            cur.execute(
                """UPDATE factory.interactive_sessions
                       SET status='stopped', ended_at=now()
                       WHERE job_name=%s AND ended_at IS NULL""",
                (job_name,),
            )
            conn.commit()
    except Exception as e:
        log.warning("interactive_sessions stop-update faalde: %s", e)


@app.route("/api/v1/claude-factory-agents", methods=["GET", "OPTIONS"])
@require_jwt
def api_claude_factory_agents() -> Response:
    """Lijst van actief draaiende claude-runner Jobs (factory-pipeline).
    Lege lijst is geldig — geen agents actief = stille tab."""
    items = k8s_jobs(FACTORY_NS, label_selector="app=claude-runner")
    active = [_factory_agent_to_dict(j) for j in items if _job_is_active(j)]
    # Nieuwste eerst zodat een net gestarte run bovenaan komt.
    active.sort(key=lambda a: a.get("started_at", ""), reverse=True)
    return jsonify(agents=active)


@app.route("/api/v1/claude-sessions", methods=["GET", "POST", "OPTIONS"])
@require_jwt
def api_claude_sessions() -> Response:
    if request.method == "OPTIONS":
        return _add_cors_headers(Response("", status=204))
    if request.method == "GET":
        active = [_session_to_dict(j) for j in _list_active_interactive_jobs()]
        active.sort(key=lambda s: s.get("started_at", ""), reverse=True)
        return jsonify(
            sessions=active,
            cap=MAX_INTERACTIVE_SESSIONS,
        )
    # POST: nieuwe sessie starten
    data = request.get_json(silent=True) or {}
    raw_name = (data.get("name") or "").strip()
    name = _sanitize_session_name(raw_name)
    if not _SESSION_NAME_RE.match(name):
        return jsonify(error=(
            f"Naam moet 1-{_SESSION_NAME_MAX} tekens zijn: kleine letters/"
            "cijfers/streepjes en beginnen met een letter."
        )), 400
    active = _list_active_interactive_jobs()
    # Naam-uniekheid binnen actieve sessies (historische namen mogen
    # opnieuw — zoals de spec voorschrijft).
    for j in active:
        if (j.get("metadata", {}).get("labels", {}) or {}).get("session-name") == name:
            return jsonify(error=f"Sessie '{name}' bestaat al."), 409
    if len(active) >= MAX_INTERACTIVE_SESSIONS:
        return jsonify(error=(
            f"Maximum {MAX_INTERACTIVE_SESSIONS} sessies bereikt — "
            "stop er eentje of wacht tot een afloopt."
        )), 409

    job = _build_interactive_resources(name)
    job_out = kubectl_run(
        "apply", "-f", "-", "-o", "json",
        input_data=json.dumps(job),
    )
    if job_out.returncode != 0:
        log.warning("Job apply faalde: %s", job_out.stderr[:200])
        return jsonify(error=f"Job apply faalde: {job_out.stderr[:200]}"), 502
    _factory_db_record_session(name, job["metadata"]["name"])
    log.info("interactive sessie '%s' gestart (job=%s)", name, job["metadata"]["name"])
    return jsonify(
        ok=True,
        session={
            "name": name,
            "job_name": job["metadata"]["name"],
            "started_at": "",
            "state": "running",
        },
    ), 201


@app.route("/api/v1/claude-sessions/<name>", methods=["DELETE", "OPTIONS"])
@require_jwt
def api_claude_session_delete(name: str) -> Response:
    if request.method == "OPTIONS":
        return _add_cors_headers(Response("", status=204))
    name = _sanitize_session_name(name)
    if not _SESSION_NAME_RE.match(name):
        return jsonify(error="ongeldige sessienaam"), 400
    # Vind de actieve Job op label-match (mogelijk meerdere historische
    # jobs met dezelfde sessie-naam, maar slechts één actief — uniqueness
    # wordt afgedwongen door de create-flow). We deleten alle matchende
    # Jobs zodat ook stragglers worden opgeruimd.
    items = k8s_jobs(FACTORY_NS, label_selector=f"app=claude-interactive,session-name={name}")
    if not items:
        return jsonify(error="sessie niet gevonden"), 404
    deleted = []
    for j in items:
        job_name = (j.get("metadata") or {}).get("name", "")
        if not job_name:
            continue
        out = kubectl_run(
            "delete", "job", job_name,
            "-n", FACTORY_NS,
            "--ignore-not-found=true",
            # propagationPolicy=Background zodat de DELETE-call snel
            # terugkomt (geen sync-wait op pod-termination); pods worden
            # via ownerReferences alsnog opgeruimd.
            "--cascade=background",
        )
        if out.returncode != 0:
            log.warning("delete job %s faalde: %s", job_name, out.stderr[:200])
            return jsonify(error=f"kubectl delete faalde: {out.stderr[:200]}"), 502
        deleted.append(job_name)
        _factory_db_mark_stopped(job_name)
    return jsonify(ok=True, deleted=deleted)


@app.route("/api/v1/healthz", methods=["GET"])
def api_healthz() -> Response:
    return jsonify(ok=True)


@app.route("/api/v1/apks", methods=["GET", "OPTIONS"])
@require_jwt
def api_apks() -> Response:
    """Build-info voor de twee APKs in de Downloads-tab.
    - personal-news-feed: via GH releases API (latest release's
      personal-news-feed.apk asset → updated_at + size)
    - dashboard: HEAD op de cdn-URL → Last-Modified + content-length
    Geen failure-modes: ontbrekende data → leeg veld."""
    pnf = {"url": "https://github.com/robbertvdzon/personal-news-feed-by-claude-code/releases/latest/download/personal-news-feed.apk"}
    dash = {"url": "https://dashboard.vdzonsoftware.nl/download/dashboard.apk"}
    # PNF: GH releases/latest
    try:
        r = gh(f"/repos/{GITHUB_OWNER}/{GITHUB_REPO}/releases/latest")
        if r:
            asset = next(
                (a for a in r.get("assets", [])
                 if a.get("name") == "personal-news-feed.apk"),
                None,
            )
            if asset:
                pnf["built_at"] = asset.get("updated_at", "")
                pnf["size"] = asset.get("size", 0)
            pnf["tag"] = r.get("tag_name", "")
    except Exception:
        pass
    # Dashboard: HEAD op de eigen URL (Last-Modified komt uit nginx)
    try:
        resp = requests.head(dash["url"], timeout=5, allow_redirects=True)
        lm = resp.headers.get("Last-Modified", "")
        if lm:
            # RFC 1123 → ISO. Voorbeeld: 'Fri, 15 May 2026 14:57:33 GMT'
            from email.utils import parsedate_to_datetime
            try:
                dash["built_at"] = parsedate_to_datetime(lm).isoformat()
            except (TypeError, ValueError):
                dash["built_at"] = lm
        cl = resp.headers.get("Content-Length")
        if cl and cl.isdigit():
            dash["size"] = int(cl)
    except Exception:
        pass
    return jsonify(pnf=pnf, dashboard=dash)


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
        "sha_age": getattr(m, "age", ""),
        "message": getattr(m, "message", ""),
        "preview_url": APP_BASE_URL,
        "phases": [_phase_to_dict(p) for p in getattr(m, "phases", [])],
        "recent_runs": list(getattr(m, "recent_runs", []) or []),
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
        "token_budget": c.token_budget,
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
    text = r.get("summary_text", "") or ""
    # Heuristiek: outcome is 'success' maar de agent heeft eigenlijk een
    # vraag aan de PO gesteld. Dan staat "Vragen voor" / "Vragen aan" in
    # de summary (= comment-tekst). De UI gebruikt deze flag om naast de
    # success-pill een 'vraag aan PO'-pill te zetten.
    had_question = bool(re.search(r"Vragen\s+(voor|aan)\b", text))
    return {
        "id": r.get("id"),
        "role": r["role"],
        "started_at": _iso(r.get("started_at")),
        "ended_at": _iso(r.get("ended_at")),
        "outcome": r["outcome"],
        "summary_text": text,
        "verdict": _extract_verdict(r.get("role", ""), text),
        "had_question": had_question,
    }


def _extract_verdict(role: str, summary: str) -> str:
    """Eindconclusie per agent. Reviewer: 'OK' / 'CHANGES'. Tester: 'PASS' /
    'FAIL' uit ai_phase-mapping. Refiner/Developer: leeg (geen ja/nee-oordeel)."""
    if not summary:
        return ""
    if role == "reviewer":
        # Zoek 'Verdict: OK' of 'Verdict: CHANGES …' onder de Verdict-heading.
        for line in summary.splitlines():
            line = line.strip()
            if line.lower().startswith("verdict:"):
                rest = line.split(":", 1)[1].strip()
                if rest.upper().startswith("OK"):
                    return "OK"
                if rest:
                    return "CHANGES"
    if role == "tester":
        # Tester eindigt vaak met '{"phase": "tested-ok"}' of '"tested-fail"'.
        # Daarnaast: 'Resultaat: PASS' of 'Resultaat: FAIL'.
        if '"phase": "tested-ok"' in summary or "tested-ok" in summary.lower():
            if '"phase": "tested-fail"' in summary:
                return "FAIL"
            return "PASS"
        if '"phase": "tested-fail"' in summary or "tested-fail" in summary.lower():
            return "FAIL"
    return ""


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
