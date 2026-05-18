#!/usr/bin/env bash
#
# Entrypoint voor een interactieve Claude-sessie (KAN-61).
#
# Wordt door de status-dashboard backend gemount als ConfigMap op
# /opt/claude-interactive/entrypoint.sh. De Job overschrijft de
# container-command zodat dit script de plaats van /usr/local/bin/
# runner.sh inneemt (claude-tester-image hergebruikt).
#
# Verwachte env-vars:
#   SESSION_NAME                       de PO-gegeven naam (uniek per actieve sessie)
#   REPO_URL                           bv. https://github.com/.../personal-news-feed-by-claude-code.git
#   GITHUB_TOKEN                       voor de clone
#   CLAUDE_AI_OAUTH_CREDENTIALS_JSON   full Claude Max OAuth-credentials-blob
#                                      (uit Mac-keychain "Claude Code-credentials").
#                                      Bevat user:sessions:claude_code +
#                                      user:mcp_servers scopes; zonder die werkt
#                                      --remote-control niet (mobile-sync uit).
#
# Doel:
#   1. Verse git-clone van main in /work/repo.
#   2. Welkomstboodschap schrijven (verwijst naar admin-RBAC-scope).
#   3. Claude CLI starten met een PTY (via `script`) + `/remote` aansturen
#      zodat de sessie via Anthropic's multi-device-sync naar de mobiel
#      verschijnt.
#
# Pod-restart-gedrag: restartPolicy=OnFailure op de Job; bij crash herstart
# Kubernetes het script vanaf nul (verse clone). Stop = `kubectl delete job`
# via de stop-knop op het dashboard.

set -euo pipefail

echo "[claude-interactive] sessie '${SESSION_NAME:-?}' start"

# ---------- pre-flight ----------
for v in CLAUDE_AI_OAUTH_CREDENTIALS_JSON GITHUB_TOKEN REPO_URL SESSION_NAME; do
  if [[ -z "${!v:-}" ]]; then
    echo "FATAL: env $v is leeg" >&2; exit 1
  fi
done

# ---------- repo-clone ----------
# /work is /work in de claude-runner-image, eigenaar groep 0 zodat een
# random OpenShift-UID kan schrijven. We laten /work/repo schoon achter
# zodat bij OnFailure-restart een verse clone gepakt wordt.
rm -rf /work/repo
mkdir -p /work
cd /work

# Token in de URL houden we uit de logs door 'm via een gh-credential-
# helper te gebruiken. `git -c credential.helper` werkt namespaced.
echo "[claude-interactive] git clone $REPO_URL → /work/repo"
git -c credential.helper="!f() { echo username=token; echo password=$GITHUB_TOKEN; }; f" \
    clone --depth 1 --branch main "$REPO_URL" repo

cd /work/repo

# ---------- welkomstboodschap ----------
# De prompt wordt ook gemount via ConfigMap (welcome.md), maar we
# schrijven 'm óók hier zodat het script self-contained is voor lokale
# tests buiten de cluster.
cat > /tmp/welcome.md <<EOF
# Interactieve Claude-sessie — '${SESSION_NAME}'

Je bent gestart in een long-running pod in OpenShift met **admin-RBAC**
op het cluster (ClusterRoleBinding: claude-interactive-admin →
cluster-admin). Wees voorzichtig: een verkeerd commando kan productie
raken.

## Scope

| Namespace                | Toegang        |
|--------------------------|----------------|
| pnf-software-factory     | schrijfbaar    |
| personal-news-feed       | schrijfbaar (= PRODUCTIE — let op) |
| pnf-pr-*                 | schrijfbaar    |
| argocd                   | schrijfbaar    |

## Beschikbare tools

- claude (jij), kubectl, oc, psql, git, gh, jq
- Browser-automation via Playwright (Chromium beschikbaar)
- DB-toegang: \$PNF_DATABASE_URL en \$FACTORY_DATABASE_URL

## Werkdirectory

\`/work/repo\` — verse git-clone van \`main\` bij start van deze sessie.

## Hoe je werkt

Je bent gestart vanuit de PO's mobiele Claude-app via /remote. Commando's
komen binnen via Anthropic's eigen multi-device-sync; output verschijnt
hier én op de mobiel. Stop deze sessie via de dashboard-knop (geen
exit-on-idle).
EOF

echo "[claude-interactive] welkomstprompt geschreven naar /tmp/welcome.md"

# ---------- pre-seed claude-config (skip onboarding wizard) ----------
# Verse pod = lege $HOME. Zonder pre-seed toont claude eerst de
# theme-picker en TOS-acceptatie; onze `/remote` op stdin wordt
# daardoor opgegeten en bereikt claude nooit.
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
  "theme": "dark",
  "skipDangerousModePermissionPrompt": true,
  "skipAutoPermissionPrompt": true
}
JSON
echo "[claude-interactive] config pre-seeded"

# ---------- full OAuth credentials (voor --remote-control) ----------
# Op Mac haalt claude credentials uit de keychain. Op Linux faalt dat;
# als alleen CLAUDE_CODE_OAUTH_TOKEN is gezet valt claude terug op een
# beperkte "Claude API"-tier (user:inference scope) waarin
# --remote-control niet bestaat. Daarom leveren we de FULL keychain-blob
# (5 scopes incl. user:sessions:claude_code + user:mcp_servers) via
# de env-var CLAUDE_AI_OAUTH_CREDENTIALS_JSON en schrijven 'm naar
# ~/.claude/.credentials.json — claude leest die file als ware de pod
# een Mac.
if [[ -n "${CLAUDE_AI_OAUTH_CREDENTIALS_JSON:-}" ]]; then
  echo "$CLAUDE_AI_OAUTH_CREDENTIALS_JSON" > "$HOME/.claude/.credentials.json"
  chmod 600 "$HOME/.claude/.credentials.json"
  unset CLAUDE_CODE_OAUTH_TOKEN
  echo "[claude-interactive] full OAuth credentials geschreven"
else
  echo "[claude-interactive] WAARSCHUWING: CLAUDE_AI_OAUTH_CREDENTIALS_JSON ontbreekt" >&2
  echo "[claude-interactive]   → --remote-control zal niet werken (mobile-sync uit)" >&2
fi

# ---------- claude start ----------
# claude weigert te starten zonder PTY. We wrappen 'm met `script` (uit
# util-linux, aanwezig in de claude-tester-image) zodat we een fake-TTY
# krijgen. De `/remote`-slash-command sturen we als eerste stdin-regel.
#
# De welkomstprompt staat in /tmp/welcome.md zodat de gebruiker 'm vanuit
# z'n eerste mobiele prompt kan oproepen ("toon /tmp/welcome.md").
echo "[claude-interactive] claude start in /remote-modus…"
{
  # tail -f houdt de stream open zodat claude niet meteen EOF ziet en
  # blokkeert tot de pod gedeleted wordt.
  tail -f /dev/null
} | script -q -c "claude --debug-file /tmp/claude-debug.log --remote-control \"$SESSION_NAME\" --append-system-prompt \"$(cat /tmp/welcome.md)\"" /dev/null

echo "[claude-interactive] claude is afgesloten — exit"
