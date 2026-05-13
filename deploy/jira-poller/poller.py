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
import sys
import time
from typing import Optional

import requests

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
REPO_URL = os.environ.get(
    "REPO_URL",
    "https://github.com/robbertvdzon/personal-news-feed-by-claude-code.git",
)
GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN", "")
# Owner + repo afleiden uit REPO_URL voor de PR-API.
_m = re.match(r"https?://github\.com/([^/]+)/([^/.]+)", REPO_URL)
GITHUB_OWNER = _m.group(1) if _m else ""
GITHUB_REPO = _m.group(2) if _m else ""

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


def spawn_runner_job(issue_key: str, task_md: str) -> bool:
    """Maak ConfigMap + Job voor één issue. Returns success."""
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

    # Job-spec (analoog aan deploy/claude-runner/job-template.yaml)
    job = {
        "apiVersion": "batch/v1",
        "kind": "Job",
        "metadata": {
            "name": job_name,
            "namespace": RUNNER_NAMESPACE,
            "labels": {"app": "claude-runner", "story-id": short},
        },
        "spec": {
            "ttlSecondsAfterFinished": 86400,
            "backoffLimit": 0,
            "template": {
                "metadata": {"labels": {"app": "claude-runner", "story-id": short}},
                "spec": {
                    "restartPolicy": "Never",
                    "containers": [
                        {
                            "name": "runner",
                            "image": CLAUDE_RUNNER_IMAGE,
                            "imagePullPolicy": "Always",
                            "env": [
                                {"name": "STORY_ID", "value": issue_key},
                                {"name": "REPO_URL", "value": REPO_URL},
                                {"name": "BASE_BRANCH", "value": "main"},
                                {"name": "BRANCH_PREFIX", "value": "ai/"},
                                # JIRA-info zodat runner z'n eigen status-
                                # transition + comments kan plaatsen (S-07).
                                {"name": "JIRA_BASE_URL", "value": JIRA_BASE_URL},
                                {"name": "JIRA_EMAIL", "value": JIRA_EMAIL},
                                {
                                    "name": "JIRA_REVIEW_STATUS",
                                    "value": JIRA_REVIEW_STATUS,
                                },
                                {
                                    "name": "ANTHROPIC_API_KEY",
                                    "valueFrom": {
                                        "secretKeyRef": {
                                            "name": "newsfeed-api-keys",
                                            "key": "PNF_ANTHROPIC_API_KEY",
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
                            ],
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

    # Apply both
    for obj in (cm, job):
        out = kubectl(
            "apply", "-f", "-", input_data=json.dumps(obj), check=False
        )
        if out.returncode != 0:
            log.error("kubectl apply faalde voor %s: %s", obj["kind"], out.stderr[:200])
            return False

    log.info("spawned Job %s voor %s", job_name, issue_key)
    return True


# ─── GitHub helpers (voor merge-check) ────────────────────────────────────


def github_pr_for_branch(branch: str) -> Optional[dict]:
    """Vind een PR (open of closed) waarvan de head-branch overeenkomt."""
    if not (GITHUB_TOKEN and GITHUB_OWNER and GITHUB_REPO):
        return None
    url = f"https://api.github.com/repos/{GITHUB_OWNER}/{GITHUB_REPO}/pulls"
    headers = {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {GITHUB_TOKEN}",
    }
    params = {"state": "all", "head": f"{GITHUB_OWNER}:{branch}", "per_page": "1"}
    try:
        r = requests.get(url, headers=headers, params=params, timeout=10)
        if r.status_code != 200:
            log.warning("GH PR-lookup %s -> %s", branch, r.status_code)
            return None
        prs = r.json()
        return prs[0] if prs else None
    except requests.RequestException as e:
        log.warning("GH PR-lookup error: %s", e)
        return None


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
        if not spawn_runner_job(key, task_md):
            log.error(
                "  spawn faalde voor %s — issue staat nu in %s; manueel terugzetten",
                key,
                JIRA_TARGET_STATUS,
            )
            continue
        active += 1


def main() -> int:
    log.info(
        "start — JIRA=%s project=%s source=%r target=%r interval=%ds max=%d",
        JIRA_BASE_URL,
        JIRA_PROJECT,
        JIRA_SOURCE_STATUS,
        JIRA_TARGET_STATUS,
        POLL_INTERVAL_SEC,
        MAX_CONCURRENT_JOBS,
    )

    # Sanity check JIRA-auth voor we beginnen
    me = jira("GET", "/rest/api/3/myself")
    if me.status_code != 200:
        log.error("JIRA-auth faalt: %s %s", me.status_code, me.text[:200])
        return 1
    log.info("JIRA-auth OK als %s", me.json().get("emailAddress"))

    while True:
        try:
            process_one_pass()
        except Exception as e:
            log.exception("pass faalde: %s", e)
        time.sleep(POLL_INTERVAL_SEC)


if __name__ == "__main__":
    sys.exit(main())
