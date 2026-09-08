#!/usr/bin/env bash
#
# Neon endpoint-config (SF-1739).
#
# Zet de read/write-endpoint van het Neon-project op de kostenzuinige
# waarden en leest daarna de effectieve waarden read-only terug:
#
#   suspend_timeout_seconds  = 300   (compute mag na 5 min stilte slapen)
#   autoscaling_limit_min_cu = 0.25
#   autoscaling_limit_max_cu = 1     (compute-cap: geen 8x-uitschieters)
#
# Idempotent: staat de endpoint al goed, dan wordt er niets gepatcht en
# alleen teruggelezen. Het script kan dus zo vaak gedraaid worden als je
# wilt.
#
# Gebruik:
#   export NEON_API_KEY=...          # Neon API-token (nooit committen)
#   export NEON_PROJECT_ID=...       # bv. square-wave-12014142
#   ./deploy/neon-endpoint-config.sh            # zet + verifieer
#   ./deploy/neon-endpoint-config.sh --verify   # alleen read-only tonen
#
# Credentials komen uitsluitend uit environment-variabelen; het script
# logt geen sleutels of tokens en schrijft niets naar de repo.
# Volgt het env-var-/API-patroon van deploy/preview-ns-labeller/labeller.sh.

set -uo pipefail

NEON_API="${NEON_API:-https://console.neon.tech/api/v2}"
SUSPEND_TIMEOUT_SECONDS="${SUSPEND_TIMEOUT_SECONDS:-300}"
AUTOSCALING_MIN_CU="${AUTOSCALING_MIN_CU:-0.25}"
AUTOSCALING_MAX_CU="${AUTOSCALING_MAX_CU:-1}"

VERIFY_ONLY=0
if [[ "${1:-}" == "--verify" ]]; then
  VERIFY_ONLY=1
fi

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) [neon-endpoint] $*"; }
die() { log "FOUT: $*"; exit 1; }

for tool in curl jq; do
  command -v "$tool" >/dev/null 2>&1 || die "'$tool' is niet geïnstalleerd"
done

[[ -n "${NEON_API_KEY:-}" ]]    || die "NEON_API_KEY ontbreekt (export 'm, niet committen)"
[[ -n "${NEON_PROJECT_ID:-}" ]] || die "NEON_PROJECT_ID ontbreekt"

# ─── Neon REST API helper ────────────────────────────────────────────────
# Echoot "<body>\n<http-status>"; de Authorization-header wordt nooit gelogd.
neon_curl() {
  local method="$1"; local path="$2"; local body="${3:-}"
  local args=(-sS -m 30 -H "Authorization: Bearer $NEON_API_KEY"
              -H "Accept: application/json"
              -X "$method" -w '\n%{http_code}'
              "${NEON_API}${path}")
  if [[ -n "$body" ]]; then
    args+=(-H "Content-Type: application/json" -d "$body")
  fi
  curl "${args[@]}"
}

neon_call() {
  # neon_call <METHOD> <PATH> [<body>] → echoot alleen de body, faalt bij non-2xx.
  local raw status body
  raw=$(neon_curl "$@") || return 1
  status="${raw##*$'\n'}"
  body="${raw%$'\n'*}"
  if [[ "$status" != "200" && "$status" != "201" && "$status" != "202" ]]; then
    log "API $1 $2 → HTTP $status: ${body:0:300}"
    return 1
  fi
  echo "$body"
}

# ─── endpoint opzoeken ───────────────────────────────────────────────────

endpoints_json=$(neon_call GET "/projects/${NEON_PROJECT_ID}/endpoints") \
  || die "kon endpoints niet ophalen (check NEON_API_KEY / NEON_PROJECT_ID)"

# Alleen de read/write-endpoint van de default (productie-)branch.
branches_json=$(neon_call GET "/projects/${NEON_PROJECT_ID}/branches") \
  || die "kon branches niet ophalen"
default_branch_id=$(echo "$branches_json" | jq -r '.branches[] | select(.default==true) | .id' | head -1)
[[ -n "$default_branch_id" && "$default_branch_id" != "null" ]] \
  || die "kon de default branch niet bepalen"

endpoint_id=$(echo "$endpoints_json" | jq -r --arg b "$default_branch_id" \
  '.endpoints[] | select(.branch_id==$b and .type=="read_write") | .id' | head -1)
[[ -n "$endpoint_id" && "$endpoint_id" != "null" ]] \
  || die "geen read_write-endpoint gevonden op de default branch"

log "project=${NEON_PROJECT_ID} branch=${default_branch_id} endpoint=${endpoint_id}"

show_effective() {
  local json
  json=$(neon_call GET "/projects/${NEON_PROJECT_ID}/endpoints/${endpoint_id}") \
    || die "kon endpoint niet teruglezen"
  echo "$json" | jq -r '.endpoint | {
      id,
      branch_id,
      type,
      suspend_timeout_seconds,
      autoscaling_limit_min_cu,
      autoscaling_limit_max_cu,
      current_state
    }'
}

# ─── huidige waarden ─────────────────────────────────────────────────────

current=$(neon_call GET "/projects/${NEON_PROJECT_ID}/endpoints/${endpoint_id}") \
  || die "kon endpoint niet ophalen"
cur_suspend=$(echo "$current" | jq -r '.endpoint.suspend_timeout_seconds // empty')
cur_min=$(echo    "$current" | jq -r '.endpoint.autoscaling_limit_min_cu // empty')
cur_max=$(echo    "$current" | jq -r '.endpoint.autoscaling_limit_max_cu // empty')
log "huidig: suspend_timeout_seconds=${cur_suspend:-?} min_cu=${cur_min:-?} max_cu=${cur_max:-?}"

if (( VERIFY_ONLY )); then
  log "read-only verificatie — effectieve waarden:"
  show_effective
  exit 0
fi

# jq -e vergelijkt numeriek, zodat 1 en 1.0 niet als verschil tellen.
if echo "$current" | jq -e \
     --argjson s "$SUSPEND_TIMEOUT_SECONDS" \
     --argjson mn "$AUTOSCALING_MIN_CU" \
     --argjson mx "$AUTOSCALING_MAX_CU" \
     '.endpoint | (.suspend_timeout_seconds == $s)
        and (.autoscaling_limit_min_cu == $mn)
        and (.autoscaling_limit_max_cu == $mx)' >/dev/null; then
  log "endpoint staat al goed — niets te patchen (idempotent)"
  log "effectieve waarden:"
  show_effective
  exit 0
fi

# ─── patchen ─────────────────────────────────────────────────────────────

payload=$(jq -n \
  --argjson s "$SUSPEND_TIMEOUT_SECONDS" \
  --argjson mn "$AUTOSCALING_MIN_CU" \
  --argjson mx "$AUTOSCALING_MAX_CU" \
  '{endpoint: {
      suspend_timeout_seconds: $s,
      autoscaling_limit_min_cu: $mn,
      autoscaling_limit_max_cu: $mx
    }}')

log "patchen naar suspend=${SUSPEND_TIMEOUT_SECONDS}s min_cu=${AUTOSCALING_MIN_CU} max_cu=${AUTOSCALING_MAX_CU}"
neon_call PATCH "/projects/${NEON_PROJECT_ID}/endpoints/${endpoint_id}" "$payload" >/dev/null \
  || die "patch faalde — endpoint ongewijzigd gelaten"

log "patch ok — effectieve waarden (read-only teruggelezen):"
show_effective
