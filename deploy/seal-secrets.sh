#!/usr/bin/env bash
#
# Genereert deploy/base/sealed-secret-api-keys.yaml uit een lokale
# bron-file. De bron is gitignored (alleen op jouw laptop); het
# resulterende SealedSecret-YAML is encrypted en komt wél in git.
#
# Gebruik:
#   1. Kopieer secrets-cluster.env.example naar secrets-cluster.env in
#      deze directory en vul de echte waarden in.
#   2. Run dit script. Vereist `kubeseal` (https://github.com/bitnami-labs/sealed-secrets).
#   3. Commit de regenereerde deploy/base/sealed-secret-api-keys.yaml.
#
# Het ontsleutel-cert (cluster-cert.pem) wordt mee-ingezet vanaf disk —
# zorg dat 't up-to-date is met `kubeseal --fetch-cert > deploy/cluster-cert.pem`.

set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="${DEPLOY_DIR}/secrets-cluster.env"
CERT="${DEPLOY_DIR}/cluster-cert.pem"
OUT="${DEPLOY_DIR}/base/sealed-secret-api-keys.yaml"
NAMESPACE="personal-news-feed"
SECRET_NAME="newsfeed-api-keys"

if ! command -v kubeseal >/dev/null 2>&1; then
  echo "Error: kubeseal niet gevonden in PATH." >&2
  echo "Installeer: https://github.com/bitnami-labs/sealed-secrets/releases" >&2
  exit 1
fi

if [[ ! -f "$SRC" ]]; then
  echo "Error: $SRC bestaat niet." >&2
  echo "Kopieer secrets-cluster.env.example en vul je cluster-secrets in." >&2
  exit 1
fi

if [[ ! -f "$CERT" ]]; then
  echo "Error: $CERT bestaat niet." >&2
  echo "Haal het public cert op met:" >&2
  echo "  kubeseal --fetch-cert > $CERT" >&2
  exit 1
fi

# Bouw de --from-literal args uit de env-file. Negeer commentaar/lege regels.
ARGS=()
while IFS= read -r line; do
  # strip leading/trailing whitespace
  line="${line#"${line%%[![:space:]]*}"}"
  line="${line%"${line##*[![:space:]]}"}"
  [[ -z "$line" || "$line" =~ ^# ]] && continue
  # quotes om de waarde verwijderen (mocht je ze in env hebben gezet voor &)
  key="${line%%=*}"
  val="${line#*=}"
  val="${val%\"}"; val="${val#\"}"
  ARGS+=(--from-literal="${key}=${val}")
done < "$SRC"

if (( ${#ARGS[@]} == 0 )); then
  echo "Error: geen key=value regels in $SRC." >&2
  exit 1
fi

echo "[seal] $((${#ARGS[@]})) entries → $OUT"

# Plain Secret YAML → kubeseal pipe → encrypted SealedSecret
oc create secret generic "$SECRET_NAME" \
  --namespace "$NAMESPACE" \
  --dry-run=client -o yaml \
  "${ARGS[@]}" \
| kubeseal --cert "$CERT" -o yaml \
> "$OUT"

echo "[seal] klaar. Voeg deze regel toe aan deploy/base/kustomization.yaml als 'ie er nog niet staat:"
echo "  - sealed-secret-api-keys.yaml"
echo "[seal] daarna: git add $OUT && git commit"
