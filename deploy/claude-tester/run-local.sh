#!/usr/bin/env bash
#
# Spawn een claude-tester pod onder de claude-tester SA — zonder Jira-
# ticket of poller. Voor handmatige tests van KAN-43 (RBAC) en KAN-44
# (image-tools oc/kubectl/psql).
#
# Drie modes:
#
#   ./run-local.sh smoke
#     Eenmalige smoke-test: print versions van oc/kubectl/psql/curl/jq,
#     verifieert RBAC (kan get-pods cluster-wide; mag NIET patchen in
#     personal-news-feed), en laat zien welke pnf-pr-* namespaces er
#     zijn. Pod wordt na de output opgeruimd.
#
#   ./run-local.sh shell
#     Interactive bash in de tester-pod. Handig om handmatig oc-/psql-
#     commando's te draaien. Exit → pod wordt opgeruimd.
#
#   ./run-local.sh exec -- <cmd>...
#     Voer een eenmalig commando uit en exit. Stdout/stderr komt op je
#     terminal.
#
# Werkt alleen als de cluster-admin de rbac-yaml al heeft toegepast:
#   oc apply -f deploy/claude-tester/rbac.yaml
#
# De pod gebruikt deze omgeving:
#   * Image:  ghcr.io/robbertvdzon/claude-tester:main
#   * SA:     claude-tester
#   * NS:     pnf-software-factory

set -euo pipefail

NAMESPACE="${TESTER_NS:-pnf-software-factory}"
IMAGE="${TESTER_IMAGE:-ghcr.io/robbertvdzon/claude-tester:main}"
SA="${TESTER_SA:-claude-tester}"
PODNAME="tester-local-$(date +%Y%m%d-%H%M%S)-$$"

usage() {
  cat >&2 <<EOF
Usage:
  $0 smoke              — smoke-test van tools + RBAC
  $0 shell              — interactieve bash in de tester-pod
  $0 exec -- <cmd>...   — voer commando uit en exit

Env-overrides: TESTER_NS, TESTER_IMAGE, TESTER_SA
EOF
  exit 1
}

# Cleanup-trap: zorg dat de pod altijd verdwijnt, ook bij Ctrl-C.
cleanup() {
  if oc get pod/"$PODNAME" -n "$NAMESPACE" >/dev/null 2>&1; then
    echo "[run-local] cleanup pod $PODNAME"
    oc delete pod/"$PODNAME" -n "$NAMESPACE" --wait=false >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT INT TERM

# JSON-snippet voor `oc run --overrides`: zet de juiste SA op de spec.
overrides='{"apiVersion":"v1","spec":{"serviceAccountName":"'"$SA"'"}}'

case "${1:-}" in
  smoke)
    echo "[run-local] smoke-test in $NAMESPACE met SA=$SA"
    SCRIPT='
set +e
echo "── versies ──"
oc version --client 2>/dev/null || echo "oc: MISSING"
kubectl version --client 2>/dev/null || echo "kubectl: MISSING"
psql --version 2>/dev/null || echo "psql: MISSING"
curl --version 2>/dev/null | head -1 || echo "curl: MISSING"
jq --version 2>/dev/null || echo "jq: MISSING"
which python3 || true

echo
echo "── identity ──"
oc whoami 2>/dev/null || echo "(geen API-toegang)"

echo
echo "── RBAC checks ──"
echo -n "kan get pods cluster-wide?       "
oc auth can-i get pods --all-namespaces && echo "(verwacht ja)"
echo -n "kan get pods/log cluster-wide?   "
oc auth can-i get pods/log --all-namespaces && echo "(verwacht ja)"
echo -n "kan get deployments?             "
oc auth can-i get deployments --all-namespaces && echo "(verwacht ja)"
echo -n "kan patch deploy in prod?        "
oc auth can-i patch deployment -n personal-news-feed
echo "  ↳ verwacht: no (gerond KAN-43 acceptatie #2)"

echo
echo "── pnf-pr-* namespaces ──"
oc get ns | awk "/^pnf-pr-/ {print}" | head -10 || echo "(geen pnf-pr-* namespaces)"

echo
echo "── PREVIEW_DB_URL ──"
if [ -n "${PREVIEW_DB_URL:-}" ]; then
  echo "ENV gezet: ${PREVIEW_DB_URL:0:30}…"
else
  echo "(PREVIEW_DB_URL niet gezet — alleen tester-Jobs krijgen deze)"
fi
echo
echo "[smoke-done]"
'
    oc run "$PODNAME" -n "$NAMESPACE" \
      --image="$IMAGE" \
      --restart=Never \
      --overrides="$overrides" \
      --command -- bash -c "$SCRIPT"
    # Wachten + logs streamen tot exit.
    oc wait pod/"$PODNAME" -n "$NAMESPACE" \
      --for=condition=Ready --timeout=120s >/dev/null 2>&1 || true
    oc logs -f pod/"$PODNAME" -n "$NAMESPACE" 2>&1 || true
    ;;

  shell)
    echo "[run-local] interactieve shell in $NAMESPACE met SA=$SA"
    echo "[run-local] tip: 'oc auth can-i list pods --all-namespaces'"
    oc run "$PODNAME" -n "$NAMESPACE" \
      --image="$IMAGE" \
      --restart=Never -it --rm \
      --overrides="$overrides" \
      --command -- bash
    ;;

  exec)
    shift
    if [ "${1:-}" != "--" ]; then usage; fi
    shift
    if [ $# -eq 0 ]; then usage; fi
    echo "[run-local] eenmalig commando in pod $PODNAME"
    oc run "$PODNAME" -n "$NAMESPACE" \
      --image="$IMAGE" \
      --restart=Never \
      --overrides="$overrides" \
      --command -- "$@"
    oc wait pod/"$PODNAME" -n "$NAMESPACE" \
      --for=condition=Ready --timeout=120s >/dev/null 2>&1 || true
    oc logs -f pod/"$PODNAME" -n "$NAMESPACE" 2>&1 || true
    ;;

  *)
    usage
    ;;
esac
