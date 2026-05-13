#!/usr/bin/env bash
#
# Watch Application-objecten in de argocd-namespace. Voor elke Application
# met destination-namespace `pnf-pr-*`: zorg dat die namespace bestaat
# met label `argocd.argoproj.io/managed-by=argocd`.
#
# Zonder dit weigert de argocd-operator de namespace te managen
# (ComparisonError: "namespace ... is not managed"). De namespace moet
# de label HEBBEN voordat de eerste sync probeert resources te schrijven.
#
# Niet event-driven (geen watch) — gewoon elke 30s polling. Idempotent.

set -euo pipefail

ARGOCD_NS="${ARGOCD_NS:-argocd}"
NS_PREFIX="${NS_PREFIX:-pnf-pr-}"
INTERVAL="${INTERVAL:-30}"

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) [labeller] $*"; }

log "start — argocd-ns=$ARGOCD_NS, prefix=$NS_PREFIX, interval=${INTERVAL}s"

while true; do
  # Vind alle Applications die op een preview-namespace richten
  mapfile -t namespaces < <(
    kubectl get app -n "$ARGOCD_NS" -o jsonpath='{range .items[*]}{.spec.destination.namespace}{"\n"}{end}' 2>/dev/null \
      | grep "^${NS_PREFIX}" | sort -u || true
  )

  for ns in "${namespaces[@]}"; do
    [[ -z "$ns" ]] && continue

    # Bestaat de namespace al?
    if kubectl get ns "$ns" >/dev/null 2>&1; then
      # Heeft 'ie de label?
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
  done

  sleep "$INTERVAL"
done
