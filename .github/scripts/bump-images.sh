#!/usr/bin/env bash
#
# Bump image-tags in een kustomization.yaml en push naar main, met
# retry-loop voor wanneer een ander image-build-workflow vlak ervoor
# z'n eigen bump committed.
#
# Probleem dat dit oplost: bij een merge die meerdere images triggert
# (bv. PR raakt zowel deploy/jira-poller/ als deploy/status-dashboard/),
# starten de bijbehorende workflows parallel. Beide checken main uit,
# beide muteren deploy/tooling/kustomization.yaml, beide proberen te
# pushen. De eerste push slaagt; de tweede crasht op een non-fast-
# forward met "Updates were rejected because the remote contains work".
#
# Aanpak: vóór elke push-poging een verse `fetch + reset --hard
# origin/main` doen, de kustomize-edit opnieuw toepassen (idempotent),
# en pas dan committen + pushen. Bij push-rejection: opnieuw.
#
# Tot 5 pogingen. Bij meer hebben we een ander probleem (bv. een
# workflow die ELKE 10s pushed) en is failen logischer dan eindeloos
# proberen.
#
# Gebruik:
#   .github/scripts/bump-images.sh <kustomization-dir> <commit-msg> <image-arg>...
#
# image-arg's worden 1-op-1 doorgegeven aan `kustomize edit set image`.
# Format: <name>=<image>:<tag>   waarbij <name> overeenkomt met de
# `name:`-entry in de bestaande `images:`-sectie van de kustomization.
#
# Voorbeeld:
#   .github/scripts/bump-images.sh \
#     deploy/tooling \
#     "ci: bump jira-poller to sha-abc123" \
#     "ghcr.io/robbertvdzon/jira-poller=ghcr.io/robbertvdzon/jira-poller:sha-abc123"

set -euo pipefail

if [[ $# -lt 3 ]]; then
  echo "usage: $0 <kustomization-dir> <commit-message> <image-arg> [<image-arg> ...]" >&2
  exit 2
fi

KUST_DIR="$1"
COMMIT_MSG="$2"
shift 2
IMAGE_ARGS=("$@")

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

# kustomize-install — idempotent. Slaat over als 'm al in PATH zit.
if ! command -v kustomize >/dev/null 2>&1; then
  echo "[bump] installing kustomize..."
  curl -fsSL https://raw.githubusercontent.com/kubernetes-sigs/kustomize/master/hack/install_kustomize.sh | bash
  sudo mv kustomize /usr/local/bin/
fi

# Bot-identity voor de commit.
git config user.name  "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"

MAX_ATTEMPTS=5
for attempt in $(seq 1 $MAX_ATTEMPTS); do
  echo "[bump] attempt $attempt/$MAX_ATTEMPTS"

  # Verse main; gooi lokale bump-commits weg die misschien al uit een
  # eerdere mislukte iteratie van deze loop staan.
  git fetch origin main --quiet
  git reset --hard origin/main

  # kustomize edit is idempotent: zet de exacte tag, ongeacht wat er nu staat.
  (
    cd "$KUST_DIR"
    # shellcheck disable=SC2068  # we willen splitting op spaties
    kustomize edit set image ${IMAGE_ARGS[@]}
  )

  if git diff --quiet; then
    echo "[bump] no manifest change — tag was al actueel."
    exit 0
  fi

  git add "$KUST_DIR/kustomization.yaml"
  git commit -m "$COMMIT_MSG"

  if git push origin HEAD:main; then
    echo "[bump] success on attempt $attempt."
    exit 0
  fi

  echo "[bump] push rejected — andere workflow was vóór ons. Retry..."
  # Korte backoff, neemt toe met attempt-nr om sequentiële races te dempen.
  sleep "$((attempt * 2))"
done

echo "[bump] failed after $MAX_ATTEMPTS attempts." >&2
exit 1
