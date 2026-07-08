#!/usr/bin/env bash
#
# App-specifieke bootstrap voor personal-news-feed. Idempotent.
#
# Het generieke cluster-brede deel (ArgoCD-operator, ArgoCD CR, Sealed
# Secrets, local-path-provisioner, Reflector) is verhuisd naar de
# robberts-infrastructure-repo (2026-07-07) — dat is geen personal-news-feed
# ding, en dashboard/smb-timemachine leunen er net zo goed op. Dit script
# hier verwacht dat dat AL gedraaid is en checkt dat vooraf.
#
# Doet nog maar 2 dingen — de rest (cert-fetch, github-pr-token, preview-ns-
# labeller's Deployment, de ArgoCD Application, de ApplicationSet) is
# verhuisd naar pure GitOps (robberts-infrastructure/manifests/root-app/apps/)
# of vervallen als duplicaat. Deze 2 blijven bewust HIER en HANDMATIG:
#
#    1. Namespace personal-news-feed aanmaken met argocd managed-by label —
#       kan niet via ArgoCD zelf, ook niet met CreateNamespace=true: deze
#       ArgoCD-installatie mag een namespace pas beheren NADAT 'ie al bestaat
#       + gelabeld is (kip-en-ei, geverifieerd 2026-07-08 met een echte
#       test-PR — zie robberts-infrastructure/docs/architecture.md).
#    2. preview-ns-labeller's RBAC (ClusterRole + ClusterRoleBinding +
#       ServiceAccount) — ArgoCD's eigen ServiceAccount mag geen
#       ClusterRole/ClusterRoleBinding-objecten aanmaken (bewust niet
#       gefixt, dat zou ArgoCD praktisch rechten-op-alles kunnen geven).
#       De Deployment zelf staat wél in GitOps, zie
#       robberts-infrastructure/manifests/root-app/apps/.
#
# Aannames:
#   - `oc` is geïnstalleerd en ingelogd op het juiste cluster (`oc whoami`).
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

# ─── pre-flight ───────────────────────────────────────────────────────
if ! command -v oc >/dev/null 2>&1; then
  echo "Error: oc niet gevonden in PATH." >&2
  exit 1
fi

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

# ─── 1. Namespace (app) met argocd managed-by label ───────────────────
echo
echo "[1/2] Namespace met argocd-label"
oc create namespace "$NAMESPACE" --dry-run=client -o yaml | oc apply -f -
oc label namespace "$NAMESPACE" "argocd.argoproj.io/managed-by=$ARGOCD_NS" --overwrite

# ─── 2. Preview-ns-labeller RBAC ──────────────────────────────────────
# Watcht Application-objecten en labelt pnf-pr-* namespaces zodat de
# argocd-operator ze accepteert ("namespace not managed"-fout omzeilen —
# hetzelfde kip-en-ei-mechanisme als stap 1 hierboven, maar dan voor
# dynamisch aangemaakte PR-preview-namespaces waarvan de naam vooraf niet
# bekend is). Alleen de RBAC (ClusterRole/ClusterRoleBinding/ServiceAccount)
# hier — de Deployment zelf staat in GitOps
# (robberts-infrastructure/manifests/root-app/apps/preview-ns-labeller-deployment.yaml).
echo
echo "[2/2] Preview-ns-labeller RBAC"
oc apply -f "$DEPLOY_DIR/preview-ns-labeller/rbac.yaml"

echo
echo "[bootstrap] klaar."
echo "Volg de sync (na 'oc apply -f manifests/root-app/root-application.yaml' in robberts-infrastructure):"
echo "  oc get application personal-news-feed -n $ARGOCD_NS -w"
echo "  oc get pods -n $NAMESPACE -w"
echo
echo "ArgoCD UI: oc get route -n $ARGOCD_NS argocd-server -o jsonpath='{.spec.host}{\"\\n\"}'"
echo "App UI:    oc get route -n $NAMESPACE frontend -o jsonpath='{.spec.host}{\"\\n\"}'"
