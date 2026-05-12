#!/usr/bin/env bash
#
# Bootstrap voor een vers OpenShift-cluster met ArgoCD.
#
# Doet 4 dingen, allemaal idempotent (mag je opnieuw runnen):
#   1. Sealed Secrets controller installeren
#   2. Cluster public-cert ophalen → deploy/cluster-cert.pem (commit naar git)
#   3. Namespace personal-news-feed aanmaken met argocd managed-by label
#   4. ArgoCD Application apply'en zodat sync start
#
# Aannames:
#   - `oc` is geïnstalleerd en ingelogd op het juiste cluster (`oc whoami`).
#   - `kubeseal` is geïnstalleerd (brew install kubeseal).
#   - ArgoCD draait in namespace `argocd` (pas ARGOCD_NS aan als anders).
#
# Run vanuit de repo-root:
#   ./deploy/bootstrap.sh

set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DEPLOY_DIR/.." && pwd)"

NAMESPACE="personal-news-feed"
ARGOCD_NS="argocd"
SEALED_SECRETS_VERSION="v0.27.0"
CERT_FILE="$DEPLOY_DIR/cluster-cert.pem"

# ─── pre-flight ───────────────────────────────────────────────────────
for cmd in oc kubeseal; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Error: $cmd niet gevonden in PATH." >&2
    exit 1
  fi
done

if ! oc whoami >/dev/null 2>&1; then
  echo "Error: `oc whoami` faalt — ben je ingelogd? Check `oc login` of KUBECONFIG." >&2
  exit 1
fi

echo "[bootstrap] cluster: $(oc whoami --show-server)"
echo "[bootstrap] user:    $(oc whoami)"

# ─── 1. Sealed Secrets controller ─────────────────────────────────────
echo
echo "[1/4] Sealed Secrets controller ($SEALED_SECRETS_VERSION)"
oc apply -f "https://github.com/bitnami-labs/sealed-secrets/releases/download/${SEALED_SECRETS_VERSION}/controller.yaml"
oc rollout status -n kube-system deploy/sealed-secrets-controller --timeout=180s

# ─── 2. Cluster cert ophalen ──────────────────────────────────────────
echo
echo "[2/4] Cluster public-cert ophalen → $CERT_FILE"
if [[ -f "$CERT_FILE" ]]; then
  # Backup en vergelijken — als het cert niet wijzigt hoeven we niets te
  # committen. Belangrijk bij re-runs op een cluster waar de controller
  # al een tijdje draait.
  tmp="$(mktemp)"
  trap 'rm -f "$tmp"' EXIT
  kubeseal --fetch-cert > "$tmp"
  if cmp -s "$tmp" "$CERT_FILE"; then
    echo "       cert is ongewijzigd."
  else
    mv "$tmp" "$CERT_FILE"
    echo "       cert is bijgewerkt — commit deploy/cluster-cert.pem!"
  fi
else
  kubeseal --fetch-cert > "$CERT_FILE"
  echo "       cert opgehaald — commit deploy/cluster-cert.pem!"
fi

# ─── 3. Namespace met argocd managed-by label ─────────────────────────
echo
echo "[3/4] Namespace $NAMESPACE met argocd-label"
oc create namespace "$NAMESPACE" --dry-run=client -o yaml | oc apply -f -
oc label namespace "$NAMESPACE" "argocd.argoproj.io/managed-by=$ARGOCD_NS" --overwrite

# ─── 4. ArgoCD Application ────────────────────────────────────────────
echo
echo "[4/4] ArgoCD Application apply"
oc apply -n "$ARGOCD_NS" -f "$DEPLOY_DIR/argocd-application.yaml"

echo
echo "[bootstrap] klaar."
echo "Volg de sync:"
echo "  oc get application personal-news-feed -n $ARGOCD_NS -w"
echo "  oc get pods -n $NAMESPACE -w"
echo
echo "ArgoCD UI: oc get route -n $ARGOCD_NS argocd-server -o jsonpath='{.spec.host}{\"\\n\"}'"
