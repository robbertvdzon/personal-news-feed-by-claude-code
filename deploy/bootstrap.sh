#!/usr/bin/env bash
#
# ⚠️ VEROUDERD sinds 2026-07-08 — dit script hoeft NIET meer gedraaid te
# worden en doet bewust niets meer.
#
# Alles wat het deed is overbodig geworden of verhuisd naar GitOps:
#
#   1. Namespace personal-news-feed aanmaken + labelen — overbodig: de
#      ArgoCD-instance draait nu cluster-scoped
#      (ARGOCD_CLUSTER_CONFIG_NAMESPACES op de operator-Subscription, zie
#      robberts-infrastructure/manifests/cluster-bootstrap/
#      argocd-operator-subscription.yaml) waardoor CreateNamespace=true de
#      namespace zelf aanmaakt. Het oude allow-list-kip-en-ei van
#      "namespaced mode" bestaat niet meer — zie
#      robberts-infrastructure/docs/architecture.md ("Historie").
#   2. preview-ns-labeller RBAC — verhuisd naar GitOps:
#      robberts-infrastructure/manifests/root-app/apps/preview-ns-labeller-rbac.yaml
#      (sync't mee via de root-Application; kon pas toen ArgoCD
#      cluster-scoped ClusterRoles mocht beheren).
#
# Een vers cluster opbouwen? Zie robberts-infrastructure:
#   ./scripts/bootstrap/bootstrap-cluster.sh
#   ./scripts/backup/restore-sealed-secrets-key.sh <backup>/sealed-secrets-keys.yaml
#   ./scripts/bootstrap/bootstrap-apps.sh
# (volledige volgorde: docs/disaster-recovery-playbook.md aldaar)

echo "[bootstrap] Dit script is verouderd (2026-07-08) en doet niets meer."
echo "[bootstrap] Alles gaat via GitOps; zie robberts-infrastructure/docs/disaster-recovery-playbook.md"
echo "[bootstrap] en de comments bovenin dit bestand voor wat waarheen verhuisd is."
exit 0
