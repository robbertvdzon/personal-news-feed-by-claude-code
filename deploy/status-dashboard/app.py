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
PROD_NS = os.environ.get("PROD_NS", "personal-news-feed")
PREVIEW_NS_PREFIX = os.environ.get("PREVIEW_NS_PREFIX", "pnf-pr-")
APP_BASE_URL = os.environ.get("APP_BASE_URL", "https://vdzonsoftware.nl")
PREVIEW_URL_FORMAT = os.environ.get(
    "PREVIEW_URL_FORMAT", "https://pnf-pr-{pr}.vdzonsoftware.nl"
)
CACHE_TTL_SEC = int(os.environ.get("CACHE_TTL_SEC", "10"))
REFRESH_SEC = int(os.environ.get("REFRESH_SEC", "10"))

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


# ─── data-modellen ────────────────────────────────────────────────────────


@dataclass
class Phase:
    label: str
    status: str  # 'pass', 'running', 'fail', 'pending', 'unknown'
    detail: str = ""
    link: str = ""


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


@dataclass
class MainCard:
    sha: str
    sha_short: str
    message: str
    age: str
    phases: list[Phase] = field(default_factory=list)
    recent_merges: list[dict] = field(default_factory=list)


@dataclass
class ClosedCard:
    number: int
    title: str
    html_url: str
    merged_age: str


# ─── helpers voor fase-status afleiden ────────────────────────────────────


def _run_to_status(run: Optional[dict]) -> tuple[str, str, str]:
    """Map workflow-run naar (status, detail, link)."""
    if not run:
        return ("pending", "—", "")
    state = run.get("status")  # queued/in_progress/completed
    conc = run.get("conclusion")  # success/failure/cancelled/...
    link = run.get("html_url", "")
    if state != "completed":
        return ("running", state or "running", link)
    if conc == "success":
        return ("pass", "", link)
    return ("fail", conc or "failed", link)


def _job_to_status(job: Optional[dict]) -> tuple[str, str, str]:
    if not job:
        return ("pending", "—", "")
    state = job.get("status")
    conc = job.get("conclusion")
    link = job.get("html_url", "")
    if state != "completed":
        return ("running", state or "running", link)
    if conc == "success":
        return ("pass", "", link)
    return ("fail", conc or "failed", link)


def _find_job(jobs: list[dict], name_substr: str) -> Optional[dict]:
    """Eerste job die name_substr in z'n naam heeft."""
    for j in jobs:
        if name_substr in (j.get("name") or ""):
            return j
    return None


def _app_phase(app: Optional[dict]) -> Phase:
    """ArgoCD Application status → Phase."""
    if not app:
        return Phase(label="argocd sync", status="pending", detail="—")
    status = (app.get("status") or {}).get("sync", {}).get("status")
    health = (app.get("status") or {}).get("health", {}).get("status")
    if status == "Synced" and health == "Healthy":
        return Phase(label="argocd sync", status="pass", detail="Synced/Healthy")
    if status == "Synced" and health == "Progressing":
        return Phase(label="argocd sync", status="running", detail="Synced/Progressing")
    if status == "OutOfSync":
        return Phase(label="argocd sync", status="running", detail="OutOfSync")
    return Phase(
        label="argocd sync",
        status="running" if status else "pending",
        detail=f"{status or '—'}/{health or '—'}",
    )


def _pods_phase(pods: list[dict], part: str) -> Phase:
    """Eén pod-fase per app-onderdeel (backend/frontend)."""
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
        restarts = sum(
            c.get("restartCount", 0)
            for c in p.get("status", {}).get("containerStatuses", []) or []
        )
        return Phase(
            label=f"{part} pod",
            status="pass",
            detail=f"Running" + (f" (restarts: {restarts})" if restarts else ""),
        )
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

    # Production
    main_commit = gh_main_head_commit() or {}
    main_sha = main_commit.get("sha", "")
    main_card = MainCard(
        sha=main_sha,
        sha_short=main_sha[:7] if main_sha else "?",
        message=(main_commit.get("commit", {}).get("message", "").splitlines() or ["—"])[0],
        age=_ago(main_commit.get("commit", {}).get("committer", {}).get("date", "")),
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
    s, d, l = _job_to_status(be_job)
    main_card.phases.append(Phase("build backend", s, d, l))
    s, d, l = _job_to_status(fe_job)
    main_card.phases.append(Phase("build frontend", s, d, l))
    if bump_job is not None:
        s, d, l = _job_to_status(bump_job)
        main_card.phases.append(Phase("bump manifests", s, d, l))
    main_card.phases.append(_app_phase(apps_by_name.get(PROD_NS)))
    main_card.phases.append(_pods_phase(pods_by_ns.get(PROD_NS, []), "backend"))
    main_card.phases.append(_pods_phase(pods_by_ns.get(PROD_NS, []), "frontend"))

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

        # validate-pr
        val_run = gh_latest_run_for_branch("validate-pr.yml", branch)
        s, d, l = _run_to_status(val_run)
        card.phases.append(Phase("validate-pr", s, d, l))

        # build-images jobs (backend + frontend)
        b_run = gh_latest_run_for_branch("build-images.yml", branch)
        b_jobs = gh_jobs_for_run(b_run["id"]) if b_run else []
        s, d, l = _job_to_status(_find_job(b_jobs, "build-backend"))
        card.phases.append(Phase("build backend", s, d, l))
        s, d, l = _job_to_status(_find_job(b_jobs, "build-frontend"))
        card.phases.append(Phase("build frontend", s, d, l))

        # ArgoCD-app voor deze PR heet typisch pnf-pr-<N>
        app_name = f"pnf-pr-{pr_num}"
        card.phases.append(_app_phase(apps_by_name.get(app_name)))

        # Pods in pnf-pr-<N>-namespace
        preview_ns = f"{PREVIEW_NS_PREFIX}{pr_num}"
        preview_pods = pods_by_ns.get(preview_ns, [])
        card.phases.append(_pods_phase(preview_pods, "backend"))
        card.phases.append(_pods_phase(preview_pods, "frontend"))

        pr_cards.append(card)

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
.title { font-weight: 600; font-size: 15px; margin-bottom: 4px; }
.title a { color: #93c5fd; text-decoration: none; }
.title a:hover { text-decoration: underline; }
.meta { font-size: 12px; color: #8b96a8; margin-bottom: 10px; }
.meta a { color: #93c5fd; text-decoration: none; }
.phase {
  display: flex; align-items: center; gap: 8px;
  padding: 4px 0; font-size: 13px;
}
.phase .icon { font-size: 14px; min-width: 18px; text-align: center; }
.phase .label { min-width: 130px; color: #cbd5e1; }
.phase .detail { color: #8b96a8; font-size: 12px; }
.phase.fail .detail { color: #fca5a5; }
.phase a { color: #93c5fd; text-decoration: none; }
.preview {
  display: inline-block; margin-top: 8px; padding: 6px 10px;
  background: #1e3a5f; border-radius: 6px;
  font-size: 12px; color: #bfdbfe; text-decoration: none;
}
.preview:hover { background: #2c4d7a; }
.merges { font-size: 12px; color: #8b96a8; margin-top: 10px; padding-top: 10px; border-top: 1px solid #2c3340; }
.merges a { color: #93c5fd; text-decoration: none; padding-right: 8px; }
.closed { font-size: 12px; padding: 4px 0; }
.closed a { color: #93c5fd; text-decoration: none; }
.closed-list { background: #1a2029; border: 1px solid #2c3340; border-radius: 10px; padding: 10px 14px; }
.empty { color: #8b96a8; font-style: italic; padding: 20px 0; text-align: center; }
"""


def render_phase(p: Phase) -> str:
    icon = STATUS_ICONS.get(p.status, "?")
    detail = escape(p.detail) if p.detail else ""
    if p.link and p.status in ("fail", "running"):
        detail = f'<a href="{escape(p.link)}" target="_blank">{detail or "↗ logs"}</a>'
    return (
        f'<div class="phase {escape(p.status)}">'
        f'<span class="icon">{icon}</span>'
        f'<span class="label">{escape(p.label)}</span>'
        f'<span class="detail">{detail}</span>'
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
    return (
        '<div class="card prod">'
        f'<div class="title"><a href="https://github.com/{escape(GITHUB_OWNER)}/{escape(GITHUB_REPO)}/tree/main" target="_blank">'
        f"🟢 Production</a> — {escape(APP_BASE_URL)}</div>"
        f'<div class="meta">main @ <code>{escape(card.sha_short)}</code> — {escape(card.message)} ({escape(card.age)} geleden)</div>'
        f"{phases_html}"
        f"{merges_block}"
        "</div>"
    )


def render_pr(card: PRCard) -> str:
    phases_html = "".join(render_phase(p) for p in card.phases)
    preview = (
        f'<a class="preview" href="{escape(card.preview_url)}" target="_blank">'
        f"🌐 {escape(card.preview_url.replace('https://', ''))}</a>"
    )
    return (
        '<div class="card pr">'
        f'<div class="title"><a href="{escape(card.html_url)}" target="_blank">'
        f"🟡 PR #{card.number} — {escape(card.title)}</a></div>"
        f'<div class="meta">branch <code>{escape(card.branch)}</code> @ <code>{escape(card.head_sha)}</code> · '
        f"door {escape(card.author)} · {escape(card.updated_age)} geleden</div>"
        f"{phases_html}"
        f"{preview}"
        "</div>"
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


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8080)
