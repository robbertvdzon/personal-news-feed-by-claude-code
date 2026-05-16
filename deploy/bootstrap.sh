#!/usr/bin/env bash
#
# Bootstrap voor een vers OpenShift-cluster.
#
# Doet alles idempotent (mag je opnieuw runnen):
#    1. argocd-operator subscriben via OperatorHub
#    2. ArgoCD CR apply'en (applicationSet enabled, server-route)
#    3. Sealed Secrets controller installeren
#    4. Cluster public-cert ophalen → deploy/cluster-cert.pem
#    5. Local-path-provisioner installeren + configureren voor OpenShift
#       (privileged helper-pod, path naar /var/lib, default StorageClass)
#    6. Reflector (Secret-mirror naar pnf-* preview-namespaces)
#    7. Namespace personal-news-feed aanmaken met argocd managed-by label
#    8. ApplicationSet-controller verifiëren (idempotency-patch)
#    9. github-pr-token secret in argocd-namespace (voor PullRequest-generator)
#   10. Preview-ns-labeller deployen (auto-label van pnf-pr-* namespaces)
#   11. ArgoCD Application apply'en zodat sync start
#   12. ApplicationSet apply'en voor automatische preview-deploys per PR
#   13. JIRA-poller RBAC (Deployment komt via ArgoCD tooling-app)
#   14. Status-dashboard RBAC (Deployment komt via ArgoCD tooling-app)
#   15. Tooling-Application apply'en (poller + dashboard Deployments)
#
# Aannames:
#   - `oc` is geïnstalleerd en ingelogd op het juiste cluster (`oc whoami`).
#   - `kubeseal` is geïnstalleerd (brew install kubeseal).
#   - OperatorHub draait (standaard op OpenShift; op vanilla Kubernetes
#     moet je eerst OLM installeren).
#
# Externe state die NIET door dit script wordt aangeraakt en die je apart
# moet onderhouden (typisch in 1Password):
#   - deploy/secrets-cluster.env  — bron voor seal-secrets.sh
#   - Cloudflare tunnel + DNS-records voor *.vdzonsoftware.nl
#   - JIRA-project + custom statuses (AI Ready / AI IN PROGRESS / …)
#
# Run vanuit de repo-root:
#   ./deploy/bootstrap.sh

set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DEPLOY_DIR/.." && pwd)"

NAMESPACE="personal-news-feed"
FACTORY_NS="pnf-software-factory"
ARGOCD_NS="argocd"
LOCAL_PATH_NS="local-path-storage"
LOCAL_PATH_SA="local-path-provisioner-service-account"
SEALED_SECRETS_VERSION="v0.27.0"
LOCAL_PATH_VERSION="v0.0.30"
REFLECTOR_VERSION="v10.0.42"
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

# ─── 1. argocd-operator (OperatorHub subscription) ────────────────────
# Community-operator uit channel 'alpha'. installPlanApproval=Automatic
# laat 'm zichzelf upgraden binnen het channel. Op fresh clusters duurt
# de eerste install ~2 min (catalog-resolve + image-pull).
echo
echo "[1/15] argocd-operator subscription"
oc apply -f "$DEPLOY_DIR/argocd-operator-subscription.yaml"

echo "       wachten op argocd CRD (signal dat de operator klaar is)..."
elapsed=0
until oc get crd argocds.argoproj.io >/dev/null 2>&1; do
  sleep 5
  elapsed=$((elapsed + 5))
  if (( elapsed >= 300 )); then
    echo "Error: argocd CRD niet beschikbaar na 5 min." >&2
    echo "       Check: oc get csv -n openshift-operators | grep argocd" >&2
    exit 1
  fi
done
echo "       operator ready"

# ─── 2. ArgoCD CR ─────────────────────────────────────────────────────
# Minimale CR met ApplicationSet enabled en route. De operator creëert
# vervolgens argocd-server, repo-server, redis, application-controller en
# applicationset-controller.
echo
echo "[2/15] ArgoCD instance ($ARGOCD_NS)"
oc create namespace "$ARGOCD_NS" --dry-run=client -o yaml | oc apply -f -
oc apply -f "$DEPLOY_DIR/argocd-cr.yaml"
echo "       wachten op argocd-server..."
oc rollout status -n "$ARGOCD_NS" deploy/argocd-server --timeout=300s 2>/dev/null || \
  echo "       (warning: argocd-server niet ready binnen 5 min)"
oc rollout status -n "$ARGOCD_NS" deploy/argocd-applicationset-controller --timeout=180s 2>/dev/null || true

# ─── 3. Sealed Secrets controller ─────────────────────────────────────
echo
echo "[3/15] Sealed Secrets controller ($SEALED_SECRETS_VERSION)"
oc apply -f "https://github.com/bitnami-labs/sealed-secrets/releases/download/${SEALED_SECRETS_VERSION}/controller.yaml"
oc rollout status -n kube-system deploy/sealed-secrets-controller --timeout=180s

# ─── 4. Cluster cert ophalen ──────────────────────────────────────────
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
echo "[4/15] Cluster public-cert ophalen → $CERT_FILE"
if [[ -f "$CERT_FILE" ]]; then
  tmp="$(mktemp)"
  trap 'rm -f "$tmp"' EXIT
  kubeseal --fetch-cert > "$tmp"
  if cmp -s "$tmp" "$CERT_FILE"; then
    echo "       cert is ongewijzigd."
  else
    mv "$tmp" "$CERT_FILE"
    echo "       ⚠️  cert is GEWIJZIGD — bestaande sealed secrets werken niet meer."
    echo "       Restore een oude master-key OF run ./deploy/seal-secrets.sh + commit."
  fi
else
  kubeseal --fetch-cert > "$CERT_FILE"
  echo "       cert opgehaald — commit deploy/cluster-cert.pem!"
fi

# ─── 5. Local-path-provisioner (storage) ──────────────────────────────
# OpenShift's restricted SCC blokkeert hostPath, en RHCOS heeft SELinux
# enforcing — daarom moet de helper-pod privileged draaien. Daarnaast
# is /opt read-only op RHCOS, dus we routeren naar /var/lib.
echo
echo "[5/15] Local-path-provisioner ($LOCAL_PATH_VERSION)"

# Install
oc apply -f "https://raw.githubusercontent.com/rancher/local-path-provisioner/${LOCAL_PATH_VERSION}/deploy/local-path-storage.yaml"
oc rollout status -n "$LOCAL_PATH_NS" deploy/local-path-provisioner --timeout=120s

# Grant privileged SCC aan de provisioner-SA — nodig om de helper-pods
# als root + spc_t te kunnen draaien (anders SELinux denial op hostPath).
echo "       grant SCC privileged → $LOCAL_PATH_SA in $LOCAL_PATH_NS"
oc adm policy add-scc-to-user privileged -z "$LOCAL_PATH_SA" -n "$LOCAL_PATH_NS" >/dev/null

# ConfigMap patchen:
#   * config.json: path naar /var/lib/local-path-provisioner (RHCOS-veilig)
#   * helperPod.yaml: securityContext.privileged=true (SELinux + hostPath)
CONFIG_JSON='{
        "nodePathMap":[
        {
                "node":"DEFAULT_PATH_FOR_NON_LISTED_NODES",
                "paths":["/var/lib/local-path-provisioner"]
        }
        ]
}'

HELPER_POD_YAML='apiVersion: v1
kind: Pod
metadata:
  name: helper-pod
spec:
  priorityClassName: system-node-critical
  tolerations:
    - key: node.kubernetes.io/disk-pressure
      operator: Exists
      effect: NoSchedule
  containers:
  - name: helper-pod
    image: busybox
    imagePullPolicy: IfNotPresent
    securityContext:
      privileged: true
'

PATCH=$(python3 -c "
import sys, json
print(json.dumps({
  'data': {
    'config.json': '''$CONFIG_JSON''',
    'helperPod.yaml': '''$HELPER_POD_YAML'''
  }
}))
")
oc patch configmap -n "$LOCAL_PATH_NS" local-path-config --type merge -p "$PATCH" >/dev/null
echo "       ConfigMap gepatcht (path + privileged helper)"

# Maak local-path de default StorageClass
oc patch storageclass local-path -p '{"metadata":{"annotations":{"storageclass.kubernetes.io/is-default-class":"true"}}}' >/dev/null
echo "       local-path StorageClass is nu default"

# Restart provisioner om de ConfigMap-patches op te pakken
oc rollout restart -n "$LOCAL_PATH_NS" deploy/local-path-provisioner >/dev/null
oc rollout status  -n "$LOCAL_PATH_NS" deploy/local-path-provisioner --timeout=60s

# ─── 6. Reflector (Secret-mirror voor preview-namespaces) ─────────────
# Mirror't `newsfeed-api-keys`-Secret naar elke nieuwe `pnf-*`-namespace
# (gestuurd via annotations op de Secret). Zonder reflector zou elke
# preview-namespace een eigen SealedSecret nodig hebben.
echo
echo "[6/15] Reflector ($REFLECTOR_VERSION)"
oc apply -f "https://github.com/emberstack/kubernetes-reflector/releases/download/${REFLECTOR_VERSION}/reflector.yaml"
oc rollout status -n kube-system deploy/reflector --timeout=120s

# ─── 7. Namespaces (app + factory) met argocd managed-by label ────────
# personal-news-feed = de applicatie zelf
# pnf-software-factory = poller, dashboard en alle runner-Jobs
echo
echo "[7/15] Namespaces met argocd-label"
for ns in "$NAMESPACE" "$FACTORY_NS"; do
  echo "       $ns"
  oc create namespace "$ns" --dry-run=client -o yaml | oc apply -f -
  oc label namespace "$ns" "argocd.argoproj.io/managed-by=$ARGOCD_NS" --overwrite
done

# ─── 8. ApplicationSet-controller idempotency-check ───────────────────
# De ArgoCD CR (stap 2) zet `applicationSet: {}` al; deze patch is een
# safety net voor het geval iemand de CR handmatig gewijzigd heeft.
echo
echo "[8/15] Verify ApplicationSet-controller"
oc patch argocd argocd -n "$ARGOCD_NS" --type merge -p '{"spec":{"applicationSet":{}}}' >/dev/null
oc rollout status -n "$ARGOCD_NS" deploy/argocd-applicationset-controller --timeout=120s 2>/dev/null || true

# ─── 9. GitHub PR-token in argocd-namespace ──────────────────────────
# De ApplicationSet's PullRequest-generator heeft een GitHub-token nodig
# om open PR's te lezen. We hergebruiken de GITHUB_TOKEN uit de sealed
# secret in personal-news-feed (zelfde token als de claude-runner).
echo
echo "[9/15] github-pr-token secret in $ARGOCD_NS"
if oc get secret -n "$NAMESPACE" newsfeed-api-keys >/dev/null 2>&1; then
  GH_TOKEN="$(oc get secret -n "$NAMESPACE" newsfeed-api-keys -o jsonpath='{.data.GITHUB_TOKEN}' | base64 -d)"
  if [[ -n "$GH_TOKEN" ]]; then
    oc create secret generic github-pr-token \
      --from-literal=token="$GH_TOKEN" \
      -n "$ARGOCD_NS" \
      --dry-run=client -o yaml | oc apply -f -
    echo "       github-pr-token gekopieerd uit newsfeed-api-keys"
  else
    echo "       (GITHUB_TOKEN ontbreekt in newsfeed-api-keys — preview-PR-deploys werken niet)"
  fi
else
  echo "       (newsfeed-api-keys nog niet aanwezig — voer ./deploy/seal-secrets.sh"
  echo "        en ArgoCD-sync uit, run dan bootstrap opnieuw)"
fi

# ─── 10. Preview-ns-labeller ──────────────────────────────────────────
# Watcht Application-objecten en labelt pnf-pr-* namespaces zodat de
# argocd-operator ze accepteert ("namespace not managed"-fout omzeilen).
echo
echo "[10/15] Preview-ns-labeller (RBAC + deployment)"
oc apply -f "$DEPLOY_DIR/preview-ns-labeller/rbac.yaml"
oc apply -f "$DEPLOY_DIR/preview-ns-labeller/deployment.yaml"
oc rollout status -n "$ARGOCD_NS" deploy/preview-ns-labeller --timeout=60s 2>/dev/null || true

# ─── 11. ArgoCD Application (prod) ────────────────────────────────────
echo
echo "[11/15] ArgoCD Application apply"
oc apply -n "$ARGOCD_NS" -f "$DEPLOY_DIR/argocd-application.yaml"

# ─── 12. ApplicationSet (preview-deploys per PR) ──────────────────────
echo
echo "[12/15] ApplicationSet voor preview-deploys"
oc apply -n "$ARGOCD_NS" -f "$DEPLOY_DIR/applicationset.yaml"

# ─── 13. JIRA-poller RBAC (Deployment komt via ArgoCD) ──────────────
# Pollt JIRA op "AI Ready"-issues en spawnt claude-runner Jobs.
# RBAC is cluster-scoped en blijft hier; de Deployment wordt door
# ArgoCD beheerd via deploy/tooling/.
echo
echo "[13/15] JIRA-poller (RBAC only)"
oc apply -f "$DEPLOY_DIR/jira-poller/rbac.yaml"

# ─── 13b. Claude-tester RBAC (KAN-43) ──────────────────────────────
# ServiceAccount + ClusterRole + ClusterRoleBinding voor de tester-Jobs
# die de poller spawnt met serviceAccountName=claude-tester. Cluster-
# scoped → niet via ArgoCD.
echo
echo "[13b/15] Claude-tester (RBAC only)"
oc apply -f "$DEPLOY_DIR/claude-tester/rbac.yaml"

# ─── 14. Status-dashboard RBAC (Deployment komt via ArgoCD) ─────────
# Read-only dashboard met PR + deploy-status. Voor publieke toegang
# moet je in Cloudflare Zero Trust een public hostname toevoegen die
# naar http://status-dashboard.personal-news-feed.svc.cluster.local:80
# wijst.
# RBAC blijft hier (ClusterRole); Deployment via ArgoCD.
echo
echo "[14/15] Status-dashboard (RBAC only)"
oc apply -f "$DEPLOY_DIR/status-dashboard/rbac.yaml"

# ─── 15. Tooling-Application (poller + dashboard Deployments) ────────
# ArgoCD synct deploy/tooling/ — bevat de Deployments voor poller en
# dashboard, beide in pnf-software-factory. Image-tags worden door
# GitHub Actions auto-gebumpt (zie .github/workflows/{jira-poller,
# status-dashboard}-image.yml). Geen handmatige rollout-restart meer.
echo
echo "[15/15] Tooling-Application (poller + dashboard via ArgoCD)"
oc apply -n "$ARGOCD_NS" -f "$DEPLOY_DIR/tooling-application.yaml"
oc rollout status -n "$FACTORY_NS" deploy/jira-poller --timeout=120s 2>/dev/null || true
oc rollout status -n "$FACTORY_NS" deploy/status-dashboard --timeout=120s 2>/dev/null || true

echo
echo "[bootstrap] klaar."
echo "Volg de sync:"
echo "  oc get application personal-news-feed -n $ARGOCD_NS -w"
echo "  oc get pods -n $NAMESPACE -w"
echo
echo "ArgoCD UI: oc get route -n $ARGOCD_NS argocd-server -o jsonpath='{.spec.host}{\"\\n\"}'"
echo "App UI:    oc get route -n $NAMESPACE frontend -o jsonpath='{.spec.host}{\"\\n\"}'"
