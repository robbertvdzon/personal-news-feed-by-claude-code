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
# Vereist GEEN kubectl/oc/kubeconfig — we bouwen de Secret-YAML direct
# in bash en pipen 'm door kubeseal. Alleen het public cert
# (deploy/cluster-cert.pem) heeft kubeseal nodig.

set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="${DEPLOY_DIR}/secrets-cluster.env"
CERT="${DEPLOY_DIR}/cluster-cert.pem"
OUT="${DEPLOY_DIR}/base/sealed-secret-api-keys.yaml"
NAMESPACE="personal-news-feed"
SECRET_NAME="newsfeed-api-keys"

if ! command -v kubeseal >/dev/null 2>&1; then
  echo "Error: kubeseal niet gevonden in PATH." >&2
  echo "Installeer: brew install kubeseal  (of https://github.com/bitnami-labs/sealed-secrets/releases)" >&2
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

# Eerst de plain Secret-YAML opbouwen via stringData (geen base64
# encoding hoeven doen). kubeseal accepteert dit en encrypt 't naar
# een SealedSecret.
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

{
  cat <<EOF
apiVersion: v1
kind: Secret
metadata:
  name: ${SECRET_NAME}
  namespace: ${NAMESPACE}
  annotations:
    # Reflector mirror't deze Secret naar elke preview-namespace pnf-*
    # zodra die wordt aangemaakt. Zonder deze annotaties hebben preview-
    # backends geen credentials en falen ze met "secret not found".
    reflector.v1.k8s.emberstack.com/reflection-allowed: "true"
    reflector.v1.k8s.emberstack.com/reflection-allowed-namespaces: "pnf-.*"
    reflector.v1.k8s.emberstack.com/reflection-auto-enabled: "true"
    reflector.v1.k8s.emberstack.com/reflection-auto-namespaces: "pnf-.*"
type: Opaque
stringData:
EOF

  count=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    # Strip leading/trailing whitespace
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -z "$line" || "$line" =~ ^# ]] && continue
    [[ "$line" != *=* ]] && continue

    key="${line%%=*}"
    val="${line#*=}"
    # Verwijder omhullende quotes (mochten ze er voor shell-escapes in zaan)
    val="${val%\"}"; val="${val#\"}"
    val="${val%\'}"; val="${val#\'}"

    # YAML block-scalar (|-) houdt de waarde letterlijk en accepteert
    # &, %, etc. zonder verdere escaping. We indenteren met 4 spaces.
    printf '  %s: |-\n' "$key"
    printf '%s\n' "$val" | sed 's/^/    /'

    count=$((count + 1))
  done < "$SRC"

  if (( count == 0 )); then
    echo "Error: geen key=value regels in $SRC." >&2
    exit 1
  fi
  echo "[seal] $count entries → $OUT" >&2
} > "$tmp"

kubeseal --cert "$CERT" -o yaml < "$tmp" > "$OUT"

echo "[seal] klaar." >&2
echo "[seal] zorg dat deploy/base/kustomization.yaml deze regel onder 'resources:' heeft staan:" >&2
echo "  - sealed-secret-api-keys.yaml" >&2
echo "[seal] daarna: git add $OUT && git commit" >&2
