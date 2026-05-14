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
POLL_INTERVAL_SEC = int(os.environ.get("POLL_INTERVAL_SEC", "30"))
MAX_CONCURRENT_JOBS = int(os.environ.get("MAX_CONCURRENT_JOBS", "2"))
CLAUDE_RUNNER_IMAGE = os.environ.get(
    "CLAUDE_RUNNER_IMAGE", "ghcr.io/robbertvdzon/claude-runner:main"
)
RUNNER_NAMESPACE = os.environ.get("RUNNER_NAMESPACE", "personal-news-feed")
# DB voor de factory-observability (Fase 1). Bevat schema `factory` met
# story_runs / agent_runs / agent_events. Wordt door de HTTP-endpoint
# `/agent-run/complete` beschreven; lege string betekent niet-
# geconfigureerd en de endpoint geeft 503.
FACTORY_DATABASE_URL = os.environ.get("FACTORY_DATABASE_URL", "")
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


def fetch_ai_ready_issues() -> list[dict]:
    """Geef alle issues in source-status terug — key + summary + description."""
    jql = f'project={JIRA_PROJECT} AND status="{JIRA_SOURCE_STATUS}" ORDER BY created ASC'
    r = jira(
        "GET",
        "/rest/api/3/search/jql",
        params={"jql": jql, "fields": "summary,description,status"},
    )
    if r.status_code != 200:
        log.warning("search faalde: %s %s", r.status_code, r.text[:200])
        return []
    return r.json().get("issues", [])


def issue_to_task_md(issue: dict) -> str:
    """Bouw een task.md uit een JIRA-issue."""
    key = issue["key"]
    summary = issue["fields"].get("summary", "(geen titel)")
    desc_node = issue["fields"].get("description")
    body = adf_to_markdown(desc_node).strip() if desc_node else "_(geen beschrijving)_"
    return f"# {summary}\n\n_JIRA-issue: {key}_\n\n{body}\n"


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


def spawn_runner_job(
    issue_key: str,
    task_md: str,
    pr_number: Optional[int] = None,
    trigger_comment_id: Optional[int] = None,
) -> Optional[str]:
    """Maak ConfigMap + Job voor één issue. Returns job-name of None bij failure.

    Comment-mode (S-09): als pr_number én trigger_comment_id meegegeven worden,
    krijgt het Job die env-vars zodat de runner na z'n push een 'rocket'-reactie
    plaatst op het trigger-comment (op faal: 'confused')."""
    short = sanitize_id(issue_key)
    stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
    job_name = f"claude-run-{short}-{stamp}"
    cm_name = f"{job_name}-task"

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
        {"name": "REPO_URL", "value": REPO_URL},
        {"name": "BASE_BRANCH", "value": "main"},
        {"name": "BRANCH_PREFIX", "value": "ai/"},
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
                    "containers": [
                        {
                            "name": "runner",
                            "image": CLAUDE_RUNNER_IMAGE,
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
        patch = {
            "metadata": {
                "ownerReferences": [{
                    "apiVersion": "batch/v1",
                    "kind": "Job",
                    "name": job_name,
                    "uid": job_uid,
                    "blockOwnerDeletion": True,
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
        "spawned Job %s voor %s (mode=%s)",
        job_name,
        issue_key,
        "comment" if is_comment_mode else "story",
    )
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
    # Eerst: AI IN REVIEW → kijken of er gemergede PR's zijn (cheap call).
    try:
        check_review_for_merges()
    except Exception as e:
        log.exception("merge-check faalde: %s", e)

    # S-09: PR-comment iteratieloop — @claude-triggers oppakken.
    try:
        process_pr_comments()
    except Exception as e:
        log.exception("pr-comment loop faalde: %s", e)

    issues = fetch_ai_ready_issues()
    if not issues:
        return

    active = count_active_runner_jobs()
    log.info(
        "found %d %r issue(s); active runner-jobs: %d/%d",
        len(issues),
        JIRA_SOURCE_STATUS,
        active,
        MAX_CONCURRENT_JOBS,
    )

    for issue in issues:
        if active >= MAX_CONCURRENT_JOBS:
            log.info("capacity bereikt, rest wacht op volgende poll")
            return
        key = issue["key"]
        log.info("  oppakken: %s", key)

        # Atomic claim: transition (als 'er een race is wint slechts één).
        if not transition_issue(key, JIRA_TARGET_STATUS):
            log.warning("  transition voor %s faalde — skip deze ronde", key)
            continue

        task_md = issue_to_task_md(issue)
        job_name = spawn_runner_job(key, task_md)
        if not job_name:
            log.error(
                "  spawn faalde voor %s — issue staat nu in %s; manueel terugzetten",
                key,
                JIRA_TARGET_STATUS,
            )
            continue

        # JIRA-comment: "in progress" met klikbare links naar branch + logs
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

        active += 1


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
                    ended_at, outcome, input_tokens, output_tokens)
                   VALUES (%s, %s, %s, %s, %s, %s, now(), %s, %s, %s)
                   RETURNING id""",
                (
                    story_run_id, role, job_name,
                    data.get("model") or None,
                    data.get("effort") or None,
                    int(data.get("level", 0) or 0),
                    data.get("outcome", ""),
                    input_tokens, output_tokens,
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
                   SET total_input_tokens  = total_input_tokens  + %s,
                       total_output_tokens = total_output_tokens + %s
                   WHERE id = %s""",
                (input_tokens, output_tokens, story_run_id),
            )

            conn.commit()

        log.info(
            "recorded agent_run %d (story=%s role=%s tokens=%d→%d events=%d)",
            agent_run_id, story_key, role,
            input_tokens, output_tokens, len(event_rows),
        )
        return jsonify({
            "ok": True,
            "agent_run_id": agent_run_id,
            "story_run_id": story_run_id,
        })
    except Exception as e:
        log.exception("agent-run-complete schrijven faalde: %s", e)
        return jsonify({"error": str(e)}), 500


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
