#!/usr/bin/env bash
#
# Autonome Claude-runner. Pakt één story op (uit /task/task.md), doet de
# wijziging op een feature-branch en opent een PR.
#
# Verwachte env-vars:
#   ANTHROPIC_API_KEY    secret
#   GITHUB_TOKEN         secret (PAT met `repo`-scope)
#   REPO_URL             https://github.com/<owner>/<repo>.git
#   BRANCH_PREFIX        bv. "ai/" — branchnaam wordt <prefix><STORY_ID>
#   STORY_ID             logische ID, bv. "PNF-42" of "poc-001"
#   BASE_BRANCH          waar de branch van vertakt + waar de PR heen gaat
#                        (default: main)
#
# Mounts:
#   /task/task.md        plain-tekst story-beschrijving (vanuit ConfigMap)

set -euo pipefail

# ---------- pre-flight ----------
for v in ANTHROPIC_API_KEY GITHUB_TOKEN REPO_URL STORY_ID; do
  if [[ -z "${!v:-}" ]]; then
    echo "FATAL: env $v is leeg" >&2; exit 1
  fi
done

if [[ ! -f /task/task.md ]]; then
  echo "FATAL: /task/task.md ontbreekt (ConfigMap niet gemount?)" >&2; exit 1
fi

BRANCH_PREFIX="${BRANCH_PREFIX:-ai/}"
BASE_BRANCH="${BASE_BRANCH:-main}"
BRANCH="${BRANCH_PREFIX}${STORY_ID}"

echo "[runner] story:  $STORY_ID"
echo "[runner] branch: $BRANCH (from $BASE_BRANCH)"
echo "[runner] repo:   $REPO_URL"

# ---------- git config ----------
git config --global user.name  "claude-runner"
git config --global user.email "claude-runner@vdzon.com"
git config --global init.defaultBranch main
git config --global advice.detachedHead false

# gh CLI auth via env-var
export GH_TOKEN="$GITHUB_TOKEN"

# Authenticated clone via PAT (in URL). De helper-store laat 'm niet
# op disk staan; alleen de werk-clone heeft 'm in z'n remote-URL.
AUTH_REPO_URL="$(echo "$REPO_URL" | sed -E "s|^https://|https://x-access-token:${GITHUB_TOKEN}@|")"

# ---------- clone ----------
echo "[runner] clone $REPO_URL → /work/repo"
git clone --depth 50 --branch "$BASE_BRANCH" "$AUTH_REPO_URL" /work/repo
cd /work/repo

# Bestaat de branch al remote? Dan checkout'en (iteratie-loop, S-09).
if git ls-remote --exit-code --heads origin "$BRANCH" >/dev/null 2>&1; then
  echo "[runner] branch bestaat al remote — checkout en pull"
  git fetch origin "$BRANCH"
  git checkout "$BRANCH"
  git pull --ff-only origin "$BRANCH"
else
  echo "[runner] nieuwe branch $BRANCH"
  git checkout -b "$BRANCH"
fi

# ---------- Claude draaien ----------
# We bouwen een prompt die:
#   - de story injecteert
#   - regels stelt (branch, commit-msg-format, spec-update)
#   - Claude expliciet vrijbrief geeft om Bash + Edit te gebruiken
#
# `--permission-mode bypassPermissions`: pod is isolated; auto-accept
# tool-use. NB: dit is alleen veilig binnen een runner-pod, niet voor
# interactief gebruik.

SYSTEM_PROMPT="Je bent een autonome software engineer. Implementeer de
gegeven story op de bestaande codebase. Regels:

1. Je werkt op branch '$BRANCH'. Pusht of merget zelf NOOIT — die stap doe ik
   later vanuit dit script.
2. Commits: gebruik commit-message-format '$STORY_ID: <korte beschrijving>'.
3. Verifieer dat je wijzigingen compileren. Voor backend (Kotlin/Maven):
   draai 'cd newsfeedbackend/newsfeedbackend && mvn -q -DskipTests compile'.
   Bij compile-fail: los op vóór je commit.
4. Update relevante specs in /specs/ als de wijziging de docs raakt.
5. Schrijf één of meer commits — atomair als 't kan.
6. Stop nadat alle wijzigingen lokaal gecommit zijn. Push doet het script.

Story staat in /work/repo/.task.md."

# Story-bestand inkopiëren zodat Claude 'm met Read kan lezen
cp /task/task.md /work/repo/.task.md

# Niet-interactief: --print mode, één keer draaien, output naar log.
# --output-format stream-json zou parsing makkelijker maken; voor PoC
# is platte tekst genoeg.
echo "[runner] Claude start..."
set +e
claude \
  --append-system-prompt "$SYSTEM_PROMPT" \
  --permission-mode bypassPermissions \
  --print \
  "Implementeer de story uit .task.md. Volg de regels uit de system prompt." \
  2>&1 | tee /tmp/claude.log
CLAUDE_EXIT=$?
set -e

echo "[runner] Claude exit=$CLAUDE_EXIT"

# Opruimen vóór we committen
rm -f /work/repo/.task.md

# ---------- verifieer dat er iets is gewijzigd ----------
# Claude moet zelf gecommit hebben volgens regel 5; check of er commits
# zijn t.o.v. base.
NEW_COMMITS=$(git log --oneline "origin/${BASE_BRANCH}..HEAD" 2>/dev/null | wc -l | tr -d ' ')
if [[ "$NEW_COMMITS" == "0" ]]; then
  echo "[runner] geen nieuwe commits — niets te pushen. Exiting met code 2."
  exit 2
fi
echo "[runner] $NEW_COMMITS nieuwe commit(s):"
git log --oneline "origin/${BASE_BRANCH}..HEAD"

# ---------- valideer commit-msg-conventie ----------
# Zie specs/branch-commit-convention.md: commits op ai/<id> moeten
# beginnen met "<id>: ". Voorkomt dat we naar GitHub pushen waar de
# CI 'm toch zou afkeuren.
echo "[runner] check commit-message-conventie"
bad=0
while IFS= read -r line; do
  [[ -z "$line" ]] && continue
  sha="${line%% *}"
  msg="${line#* }"
  if ! [[ "$msg" =~ ^${STORY_ID}:\ .+ ]]; then
    echo "[runner]   FAIL commit $sha: '$msg'"
    bad=1
  fi
done < <(git log --format='%H %s' "origin/${BASE_BRANCH}..HEAD")
if [[ $bad -ne 0 ]]; then
  echo "[runner] commit-message-conventie geschonden — niet pushen."
  echo "[runner] Verwacht format: '${STORY_ID}: <beschrijving>'"
  exit 3
fi
echo "[runner] commit-messages OK"

# ---------- push ----------
echo "[runner] push naar origin/$BRANCH"
git push -u origin "$BRANCH"

# ---------- PR open of update ----------
# Bestaat 'er al een PR voor deze branch? Dan slaan we creëren over.
if gh pr view "$BRANCH" --json number >/dev/null 2>&1; then
  echo "[runner] PR bestaat al; nieuwe commits zijn gepusht."
  gh pr view "$BRANCH" --json url --jq .url
else
  echo "[runner] open nieuwe PR"
  # Body: eerste 200 chars van task + link naar log
  PR_BODY=$(printf '## Story\n\n%s\n\n---\n\n_Geopend door claude-runner._\n' "$(cat /task/task.md)")
  gh pr create \
    --base "$BASE_BRANCH" \
    --head "$BRANCH" \
    --title "$STORY_ID: $(head -1 /task/task.md | sed 's/^# //;s/^[0-9]*\. //')" \
    --body "$PR_BODY"
fi

echo "[runner] klaar."
