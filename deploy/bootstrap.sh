#!/usr/bin/env bash
#
# App-specifieke bootstrap voor personal-news-feed. Idempotent.
#
# Het generieke cluster-brede deel (ArgoCD-operator, ArgoCD CR, Sealed
# Secrets, local-path-provisioner, Reflector) is verhuisd naar de
# robberts-infrastructure-repo (2026-07-07) — dat is geen personal-news-feed
# ding, en youtrack/dashboard/smb-timemachine leunen er net zo goed op. Dit
# script hier verwacht dat dat AL gedraaid is en checkt dat vooraf.
#
# Doet:
#    1. Cluster public-cert ophalen → deploy/cluster-cert.pem
#    2. Namespace personal-news-feed aanmaken met argocd managed-by label
#    3. github-pr-token secret in argocd-namespace (voor PullRequest-generator)
#    4. Preview-ns-labeller deployen (auto-label van pnf-pr-* namespaces)
#    5. ApplicationSet apply'en voor automatische preview-deploys per PR
#
# De ArgoCD Application zelf (prod) staat sinds 2026-07-08 niet meer hier —
# zie robberts-infrastructure/manifests/root-app/apps/.
#
# Aannames:
#   - `oc` is geïnstalleerd en ingelogd op het juiste cluster (`oc whoami`).
#   - `kubeseal` is geïnstalleerd (brew install kubeseal).
#   - ArgoCD/Sealed Secrets/storage/Reflector staan al (zie hieronder — dit
#     script checkt het en stopt met een duidelijke melding zo niet).
#
# Externe state die NIET door dit script wordt aangeraakt en die je apart
# moet onderhouden (typisch in 1Password):
#   - deploy/secrets-cluster.env  — bron voor seal-secrets.sh
#   - Cloudflare tunnel + DNS-records voor *.vdzonsoftware.nl
#
# Run vanuit de repo-root:
#   ./deploy/bootstrap.sh

set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

NAMESPACE="personal-news-feed"
ARGOCD_NS="argocd"
CERT_FILE="$DEPLOY_DIR/cluster-cert.pem"

# ─── pre-flight ───────────────────────────────────────────────────────
for cmd in oc kubeseal; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Error: $cmd niet gevonden in PATH." >&2
    exit 1
  fi
done

if ! oc whoami >/dev/null 2>&1; then
  echo "Error: 'oc whoami' faalt — ben je ingelogd? Check 'oc login' of KUBECONFIG." >&2
  exit 1
fi

echo "[bootstrap] cluster: $(oc whoami --show-server)"
echo "[bootstrap] user:    $(oc whoami)"

# Check dat de cluster-brede bootstrap al gedraaid is (ArgoCD, Sealed
# Secrets, storage, Reflector) — anders duidelijke melding i.p.v. halverwege
# vastlopen op een ontbrekende CRD/StorageClass.
echo
echo "[preflight] cluster-brede bootstrap al gedaan?"
missing=0
oc get crd argocds.argoproj.io >/dev/null 2>&1 || { echo "      ontbreekt: ArgoCD CRD"; missing=1; }
oc get deploy -n kube-system sealed-secrets-controller >/dev/null 2>&1 || { echo "      ontbreekt: sealed-secrets-controller"; missing=1; }
oc get storageclass local-path >/dev/null 2>&1 || { echo "      ontbreekt: local-path StorageClass"; missing=1; }
oc get deploy -n kube-system reflector >/dev/null 2>&1 || { echo "      ontbreekt: reflector"; missing=1; }
if [[ "$missing" -eq 1 ]]; then
  echo "Error: cluster-brede bootstrap ontbreekt nog. Draai eerst:" >&2
  echo "  ~/git/robberts-infrastructure/scripts/bootstrap/bootstrap-cluster.sh" >&2
  exit 1
fi
echo "      ok"

# ─── 1. Cluster cert ophalen ──────────────────────────────────────────
# Op een vers cluster heeft de sealed-secrets controller een NIEUWE
# keypair. De bestaande deploy/base/sealed-secret-api-keys.yaml in git
# is versleuteld met de OUDE keypair en kan niet ontsleuteld worden door
# dit nieuwe cluster.
#
# DR-opties als het cert wijzigt:
#   (a) Restore de oude master-key uit je backup:
#       oc apply -f <backup>/sealed-secrets-key-*.yaml
#       oc delete pod -n kube-system -l name=sealed-secrets-controller
#   (b) Re-encrypt (vereist deploy/secrets-cluster.env uit 1Password):
#       ./deploy/seal-secrets.sh
#       git add deploy/base/sealed-secret-api-keys.yaml && git commit && git push
echo
echo "[1/6] Cluster public-cert ophalen → $CERT_FILE"
if [[ -f "$CERT_FILE" ]]; then
  tmp="$(mktemp)"
  trap 'rm -f "$tmp"' EXIT
  kubeseal --fetch-cert > "$tmp"
  if cmp -s "$tmp" "$CERT_FILE"; then
    echo "      cert is ongewijzigd."
  else
    mv "$tmp" "$CERT_FILE"
    echo "      ⚠️  cert is GEWIJZIGD — bestaande sealed secrets werken niet meer."
    echo "      Restore een oude master-key OF run ./deploy/seal-secrets.sh + commit."
  fi
else
  kubeseal --fetch-cert > "$CERT_FILE"
  echo "      cert opgehaald — commit deploy/cluster-cert.pem!"
fi

# ─── 2. Namespace (app) met argocd managed-by label ───────────────────
echo
echo "[2/6] Namespace met argocd-label"
oc create namespace "$NAMESPACE" --dry-run=client -o yaml | oc apply -f -
oc label namespace "$NAMESPACE" "argocd.argoproj.io/managed-by=$ARGOCD_NS" --overwrite

# ─── 3. GitHub PR-token in argocd-namespace ──────────────────────────
# De ApplicationSet's PullRequest-generator heeft een GitHub-token nodig
# om open PR's te lezen. We hergebruiken de GITHUB_TOKEN uit de sealed
# secret in personal-news-feed.
echo
echo "[3/6] github-pr-token secret in $ARGOCD_NS"
if oc get secret -n "$NAMESPACE" newsfeed-api-keys >/dev/null 2>&1; then
  GH_TOKEN="$(oc get secret -n "$NAMESPACE" newsfeed-api-keys -o jsonpath='{.data.GITHUB_TOKEN}' | base64 -d)"
  if [[ -n "$GH_TOKEN" ]]; then
    oc create secret generic github-pr-token \
      --from-literal=token="$GH_TOKEN" \
      -n "$ARGOCD_NS" \
      --dry-run=client -o yaml | oc apply -f -
    echo "      github-pr-token gekopieerd uit newsfeed-api-keys"
  else
    echo "      (GITHUB_TOKEN ontbreekt in newsfeed-api-keys — preview-PR-deploys werken niet)"
  fi
else
  echo "      (newsfeed-api-keys nog niet aanwezig — voer ./deploy/seal-secrets.sh"
  echo "       en ArgoCD-sync uit, run dan bootstrap opnieuw)"
fi

# ─── 4. Preview-ns-labeller ──────────────────────────────────────────
# Watcht Application-objecten en labelt pnf-pr-* namespaces zodat de
# argocd-operator ze accepteert ("namespace not managed"-fout omzeilen).
echo
echo "[4/6] Preview-ns-labeller (RBAC + deployment)"
oc apply -f "$DEPLOY_DIR/preview-ns-labeller/rbac.yaml"
oc apply -f "$DEPLOY_DIR/preview-ns-labeller/deployment.yaml"
oc rollout status -n "$ARGOCD_NS" deploy/preview-ns-labeller --timeout=60s 2>/dev/null || true

# ─── 5. ApplicationSet (preview-deploys per PR) ──────────────────────
# De ArgoCD Application zelf (prod) wordt niet meer hier apply't — die
# pointer staat sinds de app-of-apps-consolidatie (2026-07-08) in
# robberts-infrastructure/manifests/root-app/apps/, en wordt van daaruit
# aangemaakt/beheerd. Zie robberts-infrastructure/docs/disaster-recovery-playbook.md.
echo
echo "[5/5] ApplicationSet voor preview-deploys"
oc apply -n "$ARGOCD_NS" -f "$DEPLOY_DIR/applicationset.yaml"

echo
echo "[bootstrap] klaar."
echo "Volg de sync:"
echo "  oc get application personal-news-feed -n $ARGOCD_NS -w"
echo "  oc get pods -n $NAMESPACE -w"
echo
echo "ArgoCD UI: oc get route -n $ARGOCD_NS argocd-server -o jsonpath='{.spec.host}{\"\\n\"}'"
echo "App UI:    oc get route -n $NAMESPACE frontend -o jsonpath='{.spec.host}{\"\\n\"}'"
