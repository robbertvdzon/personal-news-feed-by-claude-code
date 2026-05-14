#!/usr/bin/env bash
#
# Autonome Claude-runner. Pakt één story op (uit /task/task.md), doet de
# wijziging op een feature-branch en opent een PR.
#
# Verwachte env-vars:
#   CLAUDE_CODE_OAUTH_TOKEN  secret — Claude Code OAuth-token van
#                            `claude setup-token`, ~1 jaar geldig, billed
#                            tegen het Max-abonnement i.p.v. API-quota
#   GITHUB_TOKEN             secret (PAT met `repo`-scope)
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
for v in CLAUDE_CODE_OAUTH_TOKEN GITHUB_TOKEN REPO_URL STORY_ID; do
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

# S-09 comment-mode: als de poller ons spawn't via een PR-comment, krijgen
# we PR_NUMBER + TRIGGER_COMMENT_ID. Na een succesvolle push reageren we
# 🚀 op het trigger-comment; bij elke andere afloop 😕. De gebruiker ziet
# zo direct dat z'n feedback opgepakt + (al dan niet) verwerkt is.
TRIGGER_COMMENT_ID="${TRIGGER_COMMENT_ID:-}"
PR_NUMBER_FROM_ENV="${PR_NUMBER:-}"
RUNNER_REACTED=0

echo "[runner] story:  $STORY_ID"
echo "[runner] branch: $BRANCH (from $BASE_BRANCH)"
echo "[runner] repo:   $REPO_URL"
if [[ -n "$TRIGGER_COMMENT_ID" ]]; then
  echo "[runner] mode:   comment-iteratie (PR #${PR_NUMBER_FROM_ENV}, trigger=${TRIGGER_COMMENT_ID})"
fi

# Owner/repo afleiden uit REPO_URL, gebruikt voor reaction-API calls.
REPO_SLUG="$(echo "$REPO_URL" | sed -E 's|^https?://github\.com/||; s|\.git$||')"

react_to_trigger() {
  # $1: reactie-content (rocket, confused, +1, eyes, heart, hooray, laugh, -1)
  if [[ -z "$TRIGGER_COMMENT_ID" ]]; then
    return 0
  fi
  echo "[runner] react '$1' on comment $TRIGGER_COMMENT_ID"
  if gh api -X POST \
       "repos/${REPO_SLUG}/issues/comments/${TRIGGER_COMMENT_ID}/reactions" \
       -f "content=$1" >/dev/null 2>&1; then
    RUNNER_REACTED=1
  else
    echo "[runner] (react '$1' faalde — niet kritiek)"
  fi
}

on_exit() {
  local rc=$?
  # Als we in comment-mode zijn en nog niets gereageerd hebben, dan is dit
  # een faal-pad (commit-msg-check, geen commits, push-error, etc.). Plaats
  # een 'confused'-reactie zodat de gebruiker weet dat de iteratie vastliep.
  if [[ -n "$TRIGGER_COMMENT_ID" && $RUNNER_REACTED -eq 0 ]]; then
    # Subshell zodat een gefaalde curl/gh niet de exit-code overschrijft.
    (react_to_trigger "confused") || true
  fi
  # Factory-rapportage: POST usage + events naar de poller. Best-effort —
  # script eindigt altijd op 0, dus exit-code-bewaring is gewaarborgd.
  if [[ -f /usr/local/bin/factory-report.py ]]; then
    export RUNNER_OUTCOME
    if (( rc != 0 )) && [[ -z "${RUNNER_OUTCOME:-}" ]]; then
      RUNNER_OUTCOME="failed"
    fi
    (python3 /usr/local/bin/factory-report.py) || true
  fi
}
trap on_exit EXIT

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
# Shallow clone heeft alleen main; we moeten de branch expliciet als
# remote-tracking ref ophalen, anders heeft 'git checkout <branch>' geen
# ref om naar te resolven en valt 'ie om met "pathspec did not match".
if git ls-remote --exit-code --heads origin "$BRANCH" >/dev/null 2>&1; then
  echo "[runner] branch bestaat al remote — fetch + checkout"
  git fetch origin "+refs/heads/${BRANCH}:refs/remotes/origin/${BRANCH}"
  git checkout -B "$BRANCH" "origin/${BRANCH}"
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

# Extra context als dit een iteratie op een bestaande PR is (S-09).
if [[ -n "$TRIGGER_COMMENT_ID" ]]; then
  SYSTEM_PROMPT+="

LET OP: dit is een iteratie op een al bestaande PR. /work/repo/.task.md
bevat reviewer-feedback in plaats van een nieuwe story. Verwerk elke
opmerking concreet, maar wijzig niets dat niet expliciet gevraagd wordt.
De vorige implementatie staat al op deze branch — bouw daarop voort."
fi

# Effort-niveau via system-prompt-instructie (Fase 2). De Claude CLI
# heeft geen directe --thinking-budget-flag in --print-mode, dus we
# sturen via tekst. Mapping:
#   quick   → kort overwegen, geen extended thinking
#   default → normaal
#   deep    → uitgebreid hardop denken voor je een tool-call kiest
if [[ -n "${CLAUDE_EFFORT:-}" ]]; then
  case "$CLAUDE_EFFORT" in
    quick)
      SYSTEM_PROMPT+="

EFFORT-NIVEAU: quick. Denk kort, voer snel uit. Geen lange overwegingen
vooraf — vertrouw op je eerste oordeel en corrigeer waar nodig."
      ;;
    deep)
      SYSTEM_PROMPT+="

EFFORT-NIVEAU: deep. Denk uitgebreid hardop over de aanpak vóór je
tool-calls maakt. Overweeg alternatieven, edge-cases, en motiveer
non-triviale beslissingen."
      ;;
    *)
      :  # default — geen extra instructie
      ;;
  esac
fi

# Optionele --model flag. Lege string = laat de CLI z'n eigen default
# kiezen (backwards-compat).
CLAUDE_MODEL_FLAGS=()
if [[ -n "${CLAUDE_MODEL:-}" ]]; then
  CLAUDE_MODEL_FLAGS=(--model "$CLAUDE_MODEL")
  echo "[runner] model: $CLAUDE_MODEL · effort: ${CLAUDE_EFFORT:-default}"
fi

# Story-bestand inkopiëren zodat Claude 'm met Read kan lezen
cp /task/task.md /work/repo/.task.md

# Niet-interactief: --print + --output-format stream-json. Elke regel is
# één JSON-event (system-init, assistant-text, tool_use, tool_result,
# result). We pipen door een jq-prettyprinter zodat de pod-log leesbaar
# is; de ruwe events blijven op /tmp/claude.log.jsonl voor diepgaander
# debuggen vanuit de pod.
#
# `try fromjson catch null` retourneert null bij een onleesbare regel;
# die vallen dan in de `(raw)`-tak en breken de pipeline niet.
JQ_PRETTY='
def trim($n): if . == null then "" else (tostring) | if length > $n then "\(.[0:$n])…" else . end end;
. as $raw
| (try fromjson catch null) as $e
| if $e == null then "(raw) " + ($raw | trim(200))
  elif $e.type == "system" and $e.subtype == "init" then
    "🛠  init session=\($e.session_id // "?") model=\($e.model // "?")"
  elif $e.type == "assistant" then
    ($e.message.content // []) | map(
      if .type == "text" then ("💬 " + (.text // "" | trim(800)))
      elif .type == "tool_use" then ("→ " + .name + " " + ((.input // {}) | tostring | trim(220)))
      else ("· a." + .type) end
    ) | join("\n")
  elif $e.type == "user" then
    ($e.message.content // []) | map(
      if .type == "tool_result" then
        ("← " + ((.content // "" | if type=="array" then map(.text // "") | join("") else tostring end) | trim(220)))
      else ("· u." + .type) end
    ) | join("\n")
  elif $e.type == "result" then
    "✅ done " + ((($e.duration_ms // 0)/1000) | floor | tostring) + "s in=" + (($e.usage.input_tokens // 0) | tostring) + " out=" + (($e.usage.output_tokens // 0) | tostring)
  else ("· " + ($e.type // "?"))
  end
'

echo "[runner] Claude start..."
set +e
claude \
  "${CLAUDE_MODEL_FLAGS[@]}" \
  --append-system-prompt "$SYSTEM_PROMPT" \
  --permission-mode bypassPermissions \
  --verbose \
  --output-format stream-json \
  --print \
  "Implementeer de story uit .task.md. Volg de regels uit de system prompt." \
  2>&1 \
  | tee /tmp/claude.log.jsonl \
  | jq -rR --unbuffered "$JQ_PRETTY"
# PIPESTATUS[0] = claude zelf; tee + jq's exit-codes negeren we.
CLAUDE_EXIT=${PIPESTATUS[0]}
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

# S-09: meteen na een succesvolle push reageren we 🚀 op de trigger-comment
# (no-op buiten comment-mode). Daarna kan validate-pr nog falen, maar de
# 'gepushed'-status is sowieso correct — de gebruiker ziet de CI-status
# zelf in GitHub.
react_to_trigger "rocket"

# ---------- PR open of update ----------
# Bestaat 'er al een PR voor deze branch? Dan slaan we creëren over.
if gh pr view "$BRANCH" --json number >/dev/null 2>&1; then
  echo "[runner] PR bestaat al; nieuwe commits zijn gepusht."
  gh pr view "$BRANCH" --json url --jq .url
else
  echo "[runner] open nieuwe PR"
  PR_BODY=$(printf '## Story\n\n%s\n\n---\n\n_Geopend door claude-runner._\n' "$(cat /task/task.md)")
  gh pr create \
    --base "$BASE_BRANCH" \
    --head "$BRANCH" \
    --title "$STORY_ID: $(head -1 /task/task.md | sed 's/^# //;s/^[0-9]*\. //')" \
    --body "$PR_BODY"
fi

# ---------- wait_for_validate_pr (Fase 1 selfheal) ----------
# Wacht max 5 min op de `validate-pr` CI-check. Bij fail: 1× fix-poging
# door Claude met de error-output als context.
PR_NUMBER_FOR_CI=$(gh pr view "$BRANCH" --json number --jq .number 2>/dev/null || echo "")

wait_validate() {
  local pr_num="$1"
  local timeout=300       # 5 min
  local elapsed=0
  while (( elapsed < timeout )); do
    # `gh pr checks` toont alle historische runs voor deze PR — we pakken
    # de LAATSTE (chronologisch onderaan) want oudere fails uit eerdere
    # close+reopen cycli zijn niet relevant.
    local row
    row=$(gh pr checks "$pr_num" 2>/dev/null | grep -E "^validate(\s|$)" | tail -1)
    if [[ -n "$row" ]]; then
      if echo "$row" | grep -qiE "\bpass\b"; then return 0; fi
      if echo "$row" | grep -qiE "\bfail\b"; then return 1; fi
    fi
    sleep 10
    elapsed=$((elapsed + 10))
  done
  echo "[runner] validate-pr timeout na ${timeout}s — geef het op"
  return 2
}

fetch_validate_log() {
  # Laatste run-id voor deze branch op validate-pr workflow
  local run_id
  run_id=$(gh run list --workflow=validate-pr.yml --limit 10 \
    --json databaseId,headBranch --jq ".[] | select(.headBranch == \"$BRANCH\") | .databaseId" \
    | head -1)
  if [[ -n "$run_id" ]]; then
    gh run view "$run_id" --log-failed 2>&1 | tail -40
  fi
}

attempt_fix() {
  local err="$1"
  echo "[runner] validate-pr faalt — Claude probeert te fixen (max 1 retry)"

  # Schrijf de fout in een fix-task.md die Claude kan lezen
  cat > /work/repo/.fix-task.md <<EOF
# Fix: CI-check 'validate-pr' is gefaald

Je vorige commits hebben de validate-pr CI-check laten falen.
Hier is de relevante log-output:

\`\`\`
$err
\`\`\`

Maak één commit op deze branch ($BRANCH) die het probleem oplost.
Commit message format: $STORY_ID: <korte beschrijving van de fix>.
EOF

  set +e
  claude \
    "${CLAUDE_MODEL_FLAGS[@]}" \
    --append-system-prompt "$SYSTEM_PROMPT" \
    --permission-mode bypassPermissions \
    --verbose \
    --output-format stream-json \
    --print \
    "Fix de CI-fout zoals beschreven in /work/repo/.fix-task.md." \
    2>&1 \
    | tee -a /tmp/claude.log.jsonl \
    | jq -rR --unbuffered "$JQ_PRETTY"
  set -e
  rm -f /work/repo/.fix-task.md

  # Verifieer dat er een nieuwe commit is + push
  local before after
  before=$(git rev-parse HEAD)
  after=$(git rev-parse HEAD)   # zelfde — als Claude commit, wijzigt HEAD
  # Re-check: count commits since base
  local new_total
  new_total=$(git log --oneline "origin/${BASE_BRANCH}..HEAD" 2>/dev/null | wc -l | tr -d ' ')
  if (( new_total <= NEW_COMMITS )); then
    echo "[runner] Claude maakte geen extra commit voor de fix — geef op."
    return 1
  fi
  echo "[runner] Claude maakte extra commit(s); push + wacht opnieuw"
  git push origin "$BRANCH"
  return 0
}

if [[ -n "$PR_NUMBER_FOR_CI" ]]; then
  echo "[runner] wacht op validate-pr (max 5 min)…"
  if wait_validate "$PR_NUMBER_FOR_CI"; then
    echo "[runner] validate-pr: OK ✓"
  else
    rc=$?
    if (( rc == 1 )); then
      ERR_LOG=$(fetch_validate_log)
      if attempt_fix "$ERR_LOG"; then
        echo "[runner] wacht na fix-attempt op validate-pr (max 5 min)…"
        if wait_validate "$PR_NUMBER_FOR_CI"; then
          echo "[runner] validate-pr: OK na fix ✓"
        else
          echo "[runner] validate-pr blijft falen — handmatig nodig."
        fi
      fi
    fi
  fi
fi

# ---------- preview-URL als PR-comment ----------
# Zodra de ApplicationSet de PR detecteert, spawnt 'ie een preview op
# https://pnf-pr-<num>.vdzonsoftware.nl. We posten 'm direct
# als PR-comment zodat de URL meteen klikbaar is.
PR_NUMBER=$(gh pr view "$BRANCH" --json number --jq .number 2>/dev/null || echo "")
PR_URL=""
PREVIEW_URL=""
if [[ -n "$PR_NUMBER" ]]; then
  PR_URL=$(gh pr view "$PR_NUMBER" --json url --jq .url 2>/dev/null || echo "")
  PREVIEW_URL="https://pnf-pr-${PR_NUMBER}.vdzonsoftware.nl"
  echo "[runner] preview-URL: $PREVIEW_URL"
  # Alleen comment posten als 'm er niet al staat (idempotent)
  if ! gh pr view "$PR_NUMBER" --json comments --jq '.comments[].body' 2>/dev/null | grep -q "$PREVIEW_URL"; then
    COMMENT_BODY=$(printf '🚀 **Preview-deploy** — spint over ~2 min op:\n\n%s\n\n_Klik na de deploy om de branch live te bekijken. Bij merge wordt de preview automatisch opgeruimd._' "$PREVIEW_URL")
    gh pr comment "$PR_NUMBER" --body "$COMMENT_BODY" 2>&1 || echo "[runner] (PR-comment kon niet geplaatst worden; niet kritiek)"
  fi
fi

# ---------- JIRA transition + comments (S-07) ----------
# Alleen actief als JIRA-env-vars zijn gezet (poller injecteert ze) én
# de story-id het JIRA-format heeft (b.v. KAN-42). Manual runs zonder
# JIRA blijven werken zoals voorheen.
jira_update() {
  if [[ -z "${JIRA_BASE_URL:-}" || -z "${JIRA_EMAIL:-}" || -z "${JIRA_API_KEY:-}" ]]; then
    echo "[runner] (JIRA env-vars ontbreken — skip JIRA-update)"
    return 0
  fi
  if ! [[ "$STORY_ID" =~ ^[A-Z][A-Z0-9]+-[0-9]+$ ]]; then
    echo "[runner] (STORY_ID '$STORY_ID' geen JIRA-format — skip JIRA-update)"
    return 0
  fi
  local target_status="${JIRA_REVIEW_STATUS:-AI IN REVIEW}"

  # Helper: zoek transition-id naar een specifieke status-naam
  local transitions tr_id
  transitions=$(curl -s -m 10 -u "${JIRA_EMAIL}:${JIRA_API_KEY}" \
    -H "Accept: application/json" \
    "${JIRA_BASE_URL}/rest/api/3/issue/${STORY_ID}/transitions" 2>/dev/null)
  tr_id=$(echo "$transitions" | jq -r --arg name "$target_status" \
    '.transitions[] | select(.to.name == $name) | .id' 2>/dev/null | head -1)

  if [[ -z "$tr_id" ]]; then
    echo "[runner] geen transition naar '$target_status' beschikbaar voor $STORY_ID — skip"
    return 0
  fi

  # Transition uitvoeren
  echo "[runner] JIRA: $STORY_ID → '$target_status'"
  curl -s -m 10 -o /dev/null -w "  transition HTTP %{http_code}\n" \
    -u "${JIRA_EMAIL}:${JIRA_API_KEY}" \
    -H "Content-Type: application/json" \
    -X POST \
    -d "{\"transition\":{\"id\":\"${tr_id}\"}}" \
    "${JIRA_BASE_URL}/rest/api/3/issue/${STORY_ID}/transitions"

  # AI Phase op JIRA naar de "completed"-waarde voor onze rol. Dashboard
  # gebruikt dit voor de pipeline-balk. Field-ID komt van de poller in
  # env-var JIRA_FIELD_AI_PHASE; lege string betekent veld niet gevonden
  # — dan slaan we 't over.
  if [[ -n "${JIRA_FIELD_AI_PHASE:-}" ]]; then
    local done_phase
    case "${AGENT_ROLE:-developer}" in
      refiner)   done_phase="refined"        ;;
      developer) done_phase="developed"      ;;
      reviewer)  done_phase="reviewed-ok"    ;;  # tester-trigger is reviewed-changes vs ok
      tester)    done_phase="tested-ok"      ;;
      *)         done_phase=""               ;;
    esac
    if [[ -n "$done_phase" ]]; then
      echo "[runner] JIRA: AI Phase = '$done_phase'"
      curl -s -m 10 -o /dev/null -w "  phase HTTP %{http_code}\n" \
        -u "${JIRA_EMAIL}:${JIRA_API_KEY}" \
        -H "Content-Type: application/json" \
        -X PUT \
        -d "{\"fields\":{\"${JIRA_FIELD_AI_PHASE}\":\"${done_phase}\"}}" \
        "${JIRA_BASE_URL}/rest/api/3/issue/${STORY_ID}"
    fi
  fi

  # Comment posten met PR-link + preview-URL. ADF-format vereist in v3.
  # Idempotent: check eerst of er al een comment met de PR-URL is.
  local existing
  existing=$(curl -s -m 10 -u "${JIRA_EMAIL}:${JIRA_API_KEY}" \
    -H "Accept: application/json" \
    "${JIRA_BASE_URL}/rest/api/3/issue/${STORY_ID}/comment?maxResults=100" 2>/dev/null)
  if echo "$existing" | grep -q "$PR_URL"; then
    echo "[runner] JIRA-comment bestaat al; skip"
    return 0
  fi

  # Resultaten = lijst commit-messages (= door Claude geschreven samenvattingen)
  # plus de wijzigde files. Compact en informatief.
  local commits_json files_json
  commits_json=$(git log --format='%s' "origin/${BASE_BRANCH}..HEAD" 2>/dev/null \
    | jq -R . | jq -s . 2>/dev/null || echo '[]')
  files_json=$(git diff --name-only "origin/${BASE_BRANCH}..HEAD" 2>/dev/null \
    | jq -R . | jq -s . 2>/dev/null || echo '[]')

  local comment_json
  comment_json=$(jq -n \
    --arg pr "$PR_URL" \
    --arg preview "$PREVIEW_URL" \
    --argjson commits "$commits_json" \
    --argjson files "$files_json" \
    '
    def link($text; $href):
      { type: "text", text: $text, marks: [{ type: "link", attrs: { href: $href }}] };
    def txt($s): { type: "text", text: $s };
    def par(ns): { type: "paragraph", content: ns };
    def bullet(items): { type: "bulletList", content: (items | map({ type: "listItem", content: [ par([ txt(.) ]) ] })) };

    ([
      par([ txt("🤖 Claude heeft de story uitgewerkt.") ]),
      par([ txt("Pull request: "), link($pr; $pr) ]),
      par([ txt("Test-pagina (preview-deploy, klaar na ~2 min): "), link($preview; $preview) ])
    ] +
    (if ($commits | length) > 0
      then [ par([ txt("Wijzigingen:") ]), bullet($commits) ]
      else [] end) +
    (if ($files | length) > 0
      then [ par([ txt("Bestanden gewijzigd: \($files | length)") ]), bullet($files) ]
      else [] end) +
    [
      par([ txt("Reviewen en mergen via GitHub. Bij merge gaat de story automatisch naar Klaar.") ])
    ]) as $blocks
    |
    {
      body: {
        type: "doc",
        version: 1,
        content: $blocks
      }
    }')
  curl -s -m 10 -o /dev/null -w "  comment HTTP %{http_code}\n" \
    -u "${JIRA_EMAIL}:${JIRA_API_KEY}" \
    -H "Content-Type: application/json" \
    -X POST \
    -d "$comment_json" \
    "${JIRA_BASE_URL}/rest/api/3/issue/${STORY_ID}/comment"
}

jira_update || echo "[runner] (JIRA-update faalde — niet kritiek, PR werkt)"

echo "[runner] klaar."
