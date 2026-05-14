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
from flask import Flask, Response

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
        "JIRA_ACTIVE_STATUSES", "AI Ready,AI IN PROGRESS"
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


# ─── JIRA helpers ─────────────────────────────────────────────────────────


_jira_session = requests.Session()
if JIRA_EMAIL and JIRA_API_KEY:
    _jira_session.auth = (JIRA_EMAIL, JIRA_API_KEY)
    _jira_session.headers.update({"Accept": "application/json"})


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
    try:
        r = _jira_session.get(
            f"{JIRA_BASE_URL}/rest/api/3/search/jql",
            params={
                "jql": jql,
                "fields": "summary,status,updated",
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

    # AI bezig: filter op de active subset.
    jira_cards: list[JIRACard] = []
    active_jobs = k8s_jobs(FACTORY_NS, label_selector="app=claude-runner")
    for issue in all_tracked:
        key = issue.get("key", "")
        fields = issue.get("fields", {}) or {}
        status_name = (fields.get("status") or {}).get("name", "")
        if status_name not in JIRA_ACTIVE_STATUSES:
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

    # Sorteer: IN PROGRESS bovenaan, daarna AI Ready, beide op leeftijd.
    status_rank = {s: i for i, s in enumerate(JIRA_ACTIVE_STATUSES[::-1])}
    jira_cards.sort(key=lambda c: (-status_rank.get(c.status, -1), c.age))

    # Recent gemerged (laatste 24u)
    closed_cards: list[ClosedCard] = []
    for pr in gh_list_recent_closed_prs(10):
        closed_cards.append(
            ClosedCard(
                number=pr["number"],
                title=pr.get("title", ""),
                html_url=pr.get("html_url", ""),
                merged_age=_ago(pr.get("merged_at", "")),
            )
        )

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
"""


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

    return (
        '<div class="card pr">'
        f'<div class="title"><a href="{escape(card.html_url)}" target="_blank">'
        f"🟡 PR #{card.number} — {escape(card.title)}</a></div>"
        f"{badge_html}"
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
    return (
        f'<div class="card jira">'
        f'<div class="title">{title}</div>'
        f'<div class="meta">{meta}</div>'
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


def render_page(state: dict) -> str:
    main_html = render_main(state["main"])
    ai_cards = state.get("ai_active", [])
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
  <div class="sub">Auto-refresh elke {REFRESH_SEC}s · cache {CACHE_TTL_SEC}s · fetched at {escape(state['fetched_at'])}</div>

  {main_html}

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

    return Response(
        _render_log_page(
            job_name, status_text, pr_title, pr_url,
            log_text, pod_gone, is_running, log_status,
        ),
        mimetype="text/html; charset=utf-8",
    )


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8080)
