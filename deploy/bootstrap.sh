#!/usr/bin/env bash
#
# Bootstrap voor een vers OpenShift-cluster met ArgoCD.
#
# Doet alles idempotent (mag je opnieuw runnen):
#   1. Sealed Secrets controller installeren
#   2. Cluster public-cert ophalen → deploy/cluster-cert.pem (commit naar git)
#   3. Local-path-provisioner installeren + configureren voor OpenShift
#      (privileged helper-pod, path naar /var/lib, default StorageClass)
#   3b. Reflector (Secret-mirror naar pnf-* preview-namespaces)
#   4. Namespace personal-news-feed aanmaken met argocd managed-by label
#   5. ArgoCD ApplicationSet-controller enablen (voor preview-deploys)
#   6. github-pr-token secret in argocd-namespace (voor PullRequest-generator)
#   7. Preview-ns-labeller deployen (auto-label van pnf-pr-* namespaces)
#   8. ArgoCD Application apply'en zodat sync start
#   9. ApplicationSet apply'en voor automatische preview-deploys per PR
#  10. JIRA-poller deployen (S-03/S-04: AI Ready → claude-runner)
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

# ─── 1. Sealed Secrets controller ─────────────────────────────────────
echo
echo "[1/5] Sealed Secrets controller ($SEALED_SECRETS_VERSION)"
oc apply -f "https://github.com/bitnami-labs/sealed-secrets/releases/download/${SEALED_SECRETS_VERSION}/controller.yaml"
oc rollout status -n kube-system deploy/sealed-secrets-controller --timeout=180s

# ─── 2. Cluster cert ophalen ──────────────────────────────────────────
echo
echo "[2/5] Cluster public-cert ophalen → $CERT_FILE"
if [[ -f "$CERT_FILE" ]]; then
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

# ─── 3. Local-path-provisioner (storage) ──────────────────────────────
# OpenShift's restricted SCC blokkeert hostPath, en RHCOS heeft SELinux
# enforcing — daarom moet de helper-pod privileged draaien. Daarnaast
# is /opt read-only op RHCOS, dus we routeren naar /var/lib.
echo
echo "[3/5] Local-path-provisioner ($LOCAL_PATH_VERSION)"

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

# ─── 3b. Reflector (Secret-mirror voor preview-namespaces) ────────────
# Mirror't `newsfeed-api-keys`-Secret naar elke nieuwe `pnf-*`-namespace
# (gestuurd via annotations op de Secret). Zonder reflector zou elke
# preview-namespace een eigen SealedSecret nodig hebben.
echo
echo "[3b/5] Reflector ($REFLECTOR_VERSION)"
oc apply -f "https://github.com/emberstack/kubernetes-reflector/releases/download/${REFLECTOR_VERSION}/reflector.yaml"
oc rollout status -n kube-system deploy/reflector --timeout=120s

# ─── 4. Namespace met argocd managed-by label ─────────────────────────
echo
echo "[4/9] Namespace $NAMESPACE met argocd-label"
oc create namespace "$NAMESPACE" --dry-run=client -o yaml | oc apply -f -
oc label namespace "$NAMESPACE" "argocd.argoproj.io/managed-by=$ARGOCD_NS" --overwrite

# ─── 5. ApplicationSet-controller enable (operator-managed ArgoCD) ────
# De argocd-operator (Red Hat GitOps) deploy't standaard alleen
# server + redis + repo-server + application-controller — de
# ApplicationSet-controller komt pas zodra `spec.applicationSet` in de
# ArgoCD CR staat (lege object is genoeg om 'm te enablen).
echo
echo "[5/9] Enable ApplicationSet-controller"
if oc get argocd -n "$ARGOCD_NS" >/dev/null 2>&1; then
  oc patch argocd argocd -n "$ARGOCD_NS" --type merge -p '{"spec":{"applicationSet":{}}}'
  oc rollout status -n "$ARGOCD_NS" deploy/argocd-applicationset-controller --timeout=120s 2>/dev/null || true
else
  echo "       (geen ArgoCD CR gevonden — wordt ervan uitgegaan dat ApplicationSet al draait)"
fi

# ─── 6. GitHub PR-token in argocd-namespace ──────────────────────────
# De ApplicationSet's PullRequest-generator heeft een GitHub-token nodig
# om open PR's te lezen. We hergebruiken de GITHUB_TOKEN uit de sealed
# secret in personal-news-feed (zelfde token als de claude-runner).
echo
echo "[6/9] github-pr-token secret in $ARGOCD_NS"
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

# ─── 7. Preview-ns-labeller ───────────────────────────────────────────
# Watcht Application-objecten en labelt pnf-pr-* namespaces zodat de
# argocd-operator ze accepteert ("namespace not managed"-fout omzeilen).
echo
echo "[7/9] Preview-ns-labeller (RBAC + deployment)"
oc apply -f "$DEPLOY_DIR/preview-ns-labeller/rbac.yaml"
oc apply -f "$DEPLOY_DIR/preview-ns-labeller/deployment.yaml"
oc rollout status -n "$ARGOCD_NS" deploy/preview-ns-labeller --timeout=60s 2>/dev/null || true

# ─── 8. ArgoCD Application (prod) ─────────────────────────────────────
echo
echo "[8/9] ArgoCD Application apply"
oc apply -n "$ARGOCD_NS" -f "$DEPLOY_DIR/argocd-application.yaml"

# ─── 9. ApplicationSet (preview-deploys per PR) ───────────────────────
echo
echo "[9/10] ApplicationSet voor preview-deploys"
oc apply -n "$ARGOCD_NS" -f "$DEPLOY_DIR/applicationset.yaml"

# ─── 10. JIRA-poller (S-03/S-04) ──────────────────────────────────────
# Pollt JIRA op "AI Ready"-issues en spawnt claude-runner Jobs.
echo
echo "[10/10] JIRA-poller (RBAC + deployment)"
oc apply -f "$DEPLOY_DIR/jira-poller/rbac.yaml"
oc apply -f "$DEPLOY_DIR/jira-poller/deployment.yaml"
oc rollout status -n "$NAMESPACE" deploy/jira-poller --timeout=60s 2>/dev/null || true

echo
echo "[bootstrap] klaar."
echo "Volg de sync:"
echo "  oc get application personal-news-feed -n $ARGOCD_NS -w"
echo "  oc get pods -n $NAMESPACE -w"
echo
echo "ArgoCD UI: oc get route -n $ARGOCD_NS argocd-server -o jsonpath='{.spec.host}{\"\\n\"}'"
echo "App UI:    oc get route -n $NAMESPACE frontend -o jsonpath='{.spec.host}{\"\\n\"}'"
