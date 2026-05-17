#!/usr/bin/env bash
#
# Preview-namespace labeller (KAN-55).
#
# Voor elke ArgoCD-Application die naar pnf-pr-<N> deployt:
#   1. Namespace pnf-pr-<N> aanmaken met label argocd-managed-by (oude rol).
#   2. Neon database-branch 'pr-<N>' aanmaken (idempotent — bestaande
#      branch wordt hergebruikt).
#   3. De PNF_DATABASE_URL in de Secret 'newsfeed-api-keys' van de
#      preview-namespace overschrijven met de branch-specifieke
#      connection-URL, en de backend-pod herstarten zodat 'ie de
#      branch oppakt.
#
# Cleanup: Neon-branches met naam 'pr-<N>' waarvan de bijbehorende
# preview-namespace + ArgoCD-Application weg zijn, worden verwijderd.
#
# Idempotent: elke poll-iteratie verifieert state opnieuw. Bij Neon-API-
# fouten faalt de iteratie hard (geen stille fallback naar prod-URL).

set -uo pipefail

# ─── config ──────────────────────────────────────────────────────────────

ARGOCD_NS="${ARGOCD_NS:-argocd}"
NS_PREFIX="${NS_PREFIX:-pnf-pr-}"
INTERVAL="${INTERVAL:-30}"
SOURCE_NS="${SOURCE_NS:-personal-news-feed}"
SECRET_NAME="${SECRET_NAME:-newsfeed-api-keys}"
DB_KEY="${DB_KEY:-PNF_DATABASE_URL}"
NEON_API="${NEON_API:-https://console.neon.tech/api/v2}"
NEON_BRANCH_PREFIX="${NEON_BRANCH_PREFIX:-pr-}"
NEON_DATABASE="${NEON_DATABASE:-neondb}"
NEON_ROLE="${NEON_ROLE:-neondb_owner}"

# Verwacht via env (uit newsfeed-api-keys secret):
#   NEON_API_KEY      — token voor Neon REST API
#   NEON_PROJECT_ID   — bv. square-wave-12014142

# Neon-credentials zijn optioneel. Zonder valt de labeller terug op de
# oude NS-labeling-rol (geen branches, geen secret-patching) en logt
# één keer een waarschuwing. Zodra de credentials beschikbaar komen
# (via een latere oc-apply die de deployment-YAML met env-vars
# activeert), schakelt 'ie vanzelf over naar full Neon-mode bij de
# eerstvolgende poll. Hard-fail is bewust vermeden zodat een
# achterlopende deployment.yaml-apply de bestaande NS-labeling-functie
# niet platlegt.
NEON_ENABLED=1
if [[ -z "${NEON_API_KEY:-}" || -z "${NEON_PROJECT_ID:-}" ]]; then
  NEON_ENABLED=0
fi

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) [labeller] $*"; }

if (( NEON_ENABLED )); then
  log "start — argocd-ns=$ARGOCD_NS prefix=$NS_PREFIX neon-project=$NEON_PROJECT_ID interval=${INTERVAL}s"
else
  log "start — argocd-ns=$ARGOCD_NS prefix=$NS_PREFIX interval=${INTERVAL}s — NEON DISABLED (set NEON_API_KEY + NEON_PROJECT_ID to enable per-PR branches)"
fi

# ─── Neon REST API helpers ───────────────────────────────────────────────

neon_curl() {
  # neon_curl <METHOD> <PATH> [<json-body>]
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

neon_list_branches() {
  # Returns JSON met .branches[] — id, name, parent_id, etc.
  local raw status body
  raw=$(neon_curl GET "/projects/${NEON_PROJECT_ID}/branches" 2>/dev/null) || return 1
  status="${raw##*$'\n'}"
  body="${raw%$'\n'*}"
  if [[ "$status" != "200" ]]; then
    log "neon list-branches HTTP $status: ${body:0:200}"
    return 1
  fi
  echo "$body"
}

neon_get_default_branch_id() {
  # Default = production branch (parent voor alle PR-branches).
  local branches
  branches=$(neon_list_branches) || return 1
  echo "$branches" | jq -r '.branches[] | select(.default==true) | .id' | head -1
}

neon_find_branch_id_by_name() {
  local name="$1" branches
  branches=$(neon_list_branches) || return 1
  echo "$branches" | jq -r --arg n "$name" '.branches[] | select(.name==$n) | .id' | head -1
}

neon_create_branch() {
  # neon_create_branch <name> <parent_id>
  # Maakt branch + start een endpoint zodat 'ie querybaar is.
  local name="$1" parent="$2" raw status body
  local payload
  payload=$(jq -n --arg n "$name" --arg p "$parent" '{
    branch: {name: $n, parent_id: $p},
    endpoints: [{type: "read_write"}]
  }')
  raw=$(neon_curl POST "/projects/${NEON_PROJECT_ID}/branches" "$payload") || return 1
  status="${raw##*$'\n'}"
  body="${raw%$'\n'*}"
  if [[ "$status" != "200" && "$status" != "201" ]]; then
    log "neon create-branch '$name' HTTP $status: ${body:0:300}"
    return 1
  fi
  echo "$body" | jq -r '.branch.id'
}

neon_delete_branch() {
  local branch_id="$1" raw status
  raw=$(neon_curl DELETE "/projects/${NEON_PROJECT_ID}/branches/${branch_id}") || return 1
  status="${raw##*$'\n'}"
  if [[ "$status" != "200" && "$status" != "202" ]]; then
    log "neon delete-branch '$branch_id' HTTP $status"
    return 1
  fi
  return 0
}

neon_connection_uri_for_branch() {
  # GET /projects/X/connection_uri returnt {uri: postgresql://...}
  local branch_id="$1" raw status body
  raw=$(neon_curl GET \
    "/projects/${NEON_PROJECT_ID}/connection_uri?branch_id=${branch_id}&database_name=${NEON_DATABASE}&role_name=${NEON_ROLE}" \
    ) || return 1
  status="${raw##*$'\n'}"
  body="${raw%$'\n'*}"
  if [[ "$status" != "200" ]]; then
    log "neon connection_uri branch=$branch_id HTTP $status: ${body:0:200}"
    return 1
  fi
  echo "$body" | jq -r '.uri'
}

# ─── conversie libpq → JDBC ──────────────────────────────────────────────
#
# Neon returnt: postgresql://user:pass@host/db?sslmode=require
# Backend wil:  jdbc:postgresql://host/db?user=X&password=Y&sslmode=require
#
# Spring-data-jdbc kan beide aan in theorie, maar de bestaande URL is in
# JDBC-vorm en de Properties zijn daarop geconfigureerd. Hou consistent.
libpq_to_jdbc() {
  local libpq="$1"
  python3 - "$libpq" <<'PY'
import sys, urllib.parse as up
u = up.urlparse(sys.argv[1])
user = up.unquote(u.username or "")
pwd  = up.unquote(u.password or "")
host = u.hostname or ""
port = f":{u.port}" if u.port else ""
db   = (u.path or "").lstrip("/")
# Bewaar bestaande query-params (sslmode etc.) + voeg user/password toe.
qs = dict(up.parse_qsl(u.query))
qs["user"] = user
qs["password"] = pwd
qs.setdefault("sslmode", "require")
out = f"jdbc:postgresql://{host}{port}/{db}?" + up.urlencode(qs)
print(out)
PY
}

# ─── kubectl helpers ─────────────────────────────────────────────────────

ns_exists() { kubectl get ns "$1" >/dev/null 2>&1; }

ensure_ns_with_label() {
  local ns="$1"
  if ns_exists "$ns"; then
    local current
    current=$(kubectl get ns "$ns" -o jsonpath='{.metadata.labels.argocd\.argoproj\.io/managed-by}' 2>/dev/null || true)
    if [[ "$current" != "argocd" ]]; then
      log "  $ns: label ontbreekt, toevoegen"
      kubectl label ns "$ns" argocd.argoproj.io/managed-by=argocd --overwrite >/dev/null
    fi
  else
    log "  $ns: bestaat niet, aanmaken met label"
    kubectl create ns "$ns" >/dev/null
    kubectl label ns "$ns" argocd.argoproj.io/managed-by=argocd --overwrite >/dev/null
  fi
}

secret_value() {
  # secret_value <namespace> <secret> <key>
  kubectl get secret -n "$1" "$2" -o jsonpath="{.data.$3}" 2>/dev/null | base64 -d 2>/dev/null
}

patch_secret_db_url() {
  # patch_secret_db_url <namespace> <jdbc-url>
  local ns="$1" url="$2" encoded
  encoded=$(printf '%s' "$url" | base64 | tr -d '\n')
  kubectl patch secret -n "$ns" "$SECRET_NAME" --type=json \
    -p "[{\"op\":\"replace\",\"path\":\"/data/${DB_KEY}\",\"value\":\"${encoded}\"}]" \
    >/dev/null
}

restart_backend_pods() {
  # Forceer pod-recreate zodat Spring de nieuwe URL ophaalt.
  # kubectl delete pod is voldoende — Deployment respawnt.
  kubectl delete pod -n "$1" -l app=backend --ignore-not-found=true \
    --wait=false >/dev/null 2>&1 || true
}

# ─── main loop ───────────────────────────────────────────────────────────

while true; do
  # 1. Vind alle Applications die op een preview-namespace richten.
  mapfile -t app_namespaces < <(
    kubectl get app -n "$ARGOCD_NS" -o jsonpath='{range .items[*]}{.spec.destination.namespace}{"\n"}{end}' 2>/dev/null \
      | grep "^${NS_PREFIX}" | sort -u || true
  )

  # 2. Cache default-branch-id (parent voor PR-branches). Skip helemaal
  # als Neon-mode uit staat — dan doet de loop alleen NS-labeling.
  default_branch_id=""
  if (( NEON_ENABLED )) && (( ${#app_namespaces[@]} > 0 )); then
    default_branch_id=$(neon_get_default_branch_id || true)
    if [[ -z "$default_branch_id" ]]; then
      log "kan default Neon-branch niet bepalen — sla Neon-stap over voor deze poll"
    fi
  fi

  # 3. Per preview-namespace: ns ensure + (optioneel) branch ensure + secret patch.
  for ns in "${app_namespaces[@]}"; do
    [[ -z "$ns" ]] && continue
    ensure_ns_with_label "$ns"

    # Vanaf hier alleen Neon-werk; bij disabled-mode skippen we.
    if ! (( NEON_ENABLED )); then
      continue
    fi
    if [[ -z "$default_branch_id" ]]; then
      continue
    fi

    # PR-num uit ns-naam halen.
    pr_num="${ns#${NS_PREFIX}}"
    if ! [[ "$pr_num" =~ ^[0-9]+$ ]]; then
      log "  $ns: kan PR-num niet bepalen (suffix=$pr_num), skip Neon-branch"
      continue
    fi
    branch_name="${NEON_BRANCH_PREFIX}${pr_num}"

    # Branch bestaat?
    branch_id=$(neon_find_branch_id_by_name "$branch_name" || true)
    if [[ -z "$branch_id" ]]; then
      log "  $ns: Neon-branch '$branch_name' bestaat niet, aanmaken (parent=$default_branch_id)"
      branch_id=$(neon_create_branch "$branch_name" "$default_branch_id" || true)
      if [[ -z "$branch_id" ]]; then
        log "  $ns: branch-creatie mislukte — skip deze ronde"
        continue
      fi
      log "  $ns: branch '$branch_name' aangemaakt id=$branch_id"
      # Endpoint is meestal direct querybaar bij creation; geen polling nodig.
    fi

    # Connection URI ophalen + naar JDBC converteren.
    libpq_url=$(neon_connection_uri_for_branch "$branch_id" || true)
    if [[ -z "$libpq_url" ]]; then
      log "  $ns: kan connection_uri voor branch $branch_id niet ophalen — skip"
      continue
    fi
    jdbc_url=$(libpq_to_jdbc "$libpq_url" || true)
    if [[ -z "$jdbc_url" ]]; then
      log "  $ns: JDBC-conversie faalde — skip"
      continue
    fi

    # Secret bestaat al door reflector? Anders wachten.
    if ! kubectl get secret -n "$ns" "$SECRET_NAME" >/dev/null 2>&1; then
      log "  $ns: secret '$SECRET_NAME' nog niet gereflecteerd — wacht tot volgende poll"
      continue
    fi

    # Huidige PNF_DATABASE_URL? Vergelijk en patch indien nodig.
    current_url=$(secret_value "$ns" "$SECRET_NAME" "$DB_KEY")
    if [[ "$current_url" == "$jdbc_url" ]]; then
      # Al goed gepatcht; doen niks.
      :
    else
      log "  $ns: ${DB_KEY} bijwerken naar branch-URL (ep=$(echo "$jdbc_url" | sed -n 's|.*//\([^:./]*\)\..*|\1|p'))"
      if patch_secret_db_url "$ns" "$jdbc_url"; then
        log "  $ns: secret gepatcht — backend-pod restart"
        restart_backend_pods "$ns"
      else
        log "  $ns: patch-secret faalde"
      fi
    fi
  done

  # 4. Cleanup: orphan Neon-branches verwijderen (geen matching ArgoCD-Application meer).
  # Skip als Neon-mode uit staat — dan zijn er sowieso geen branches om op te ruimen.
  if ! (( NEON_ENABLED )); then
    sleep "$INTERVAL"
    continue
  fi
  all_branches_json=$(neon_list_branches 2>/dev/null || echo '{"branches":[]}')
  # Verzamel set van actieve PR-nummers uit huidige Applications.
  declare -A active_prs=()
  for ns in "${app_namespaces[@]}"; do
    pr_num="${ns#${NS_PREFIX}}"
    [[ "$pr_num" =~ ^[0-9]+$ ]] && active_prs["$pr_num"]=1
  done
  # Loop door alle Neon-branches met prefix pr-*.
  while IFS=$'\t' read -r b_id b_name; do
    [[ -z "$b_id" ]] && continue
    pr_num="${b_name#${NEON_BRANCH_PREFIX}}"
    if [[ "$pr_num" =~ ^[0-9]+$ ]] && [[ -z "${active_prs[$pr_num]:-}" ]]; then
      log "  cleanup: branch '$b_name' (id=$b_id) heeft geen actieve Application meer → delete"
      neon_delete_branch "$b_id" || log "  cleanup: delete faalde voor $b_name"
    fi
  done < <(
    echo "$all_branches_json" \
      | jq -r --arg pre "$NEON_BRANCH_PREFIX" \
          '.branches[] | select((.name | startswith($pre)) and (.default|not)) | "\(.id)\t\(.name)"'
  )

  sleep "$INTERVAL"
done
