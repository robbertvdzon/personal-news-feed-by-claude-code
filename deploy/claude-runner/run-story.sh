#!/usr/bin/env bash
#
# Helper om één Claude-runner Job te starten met een lokale task.md.
#
# Gebruik:
#   ./deploy/claude-runner/run-story.sh poc-001 path/to/task.md
#
# Vereist: oc ingelogd, namespace personal-news-feed bestaat, secret
# newsfeed-api-keys heeft de keys PNF_ANTHROPIC_API_KEY en GITHUB_TOKEN.

set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <story-id> <path/to/task.md>" >&2
  echo "  story-id moet uniek zijn — wordt branchnaam ai/<story-id>" >&2
  exit 1
fi

STORY_ID="$1"
TASK_FILE="$2"

if [[ ! -f "$TASK_FILE" ]]; then
  echo "Error: $TASK_FILE bestaat niet." >&2; exit 1
fi

STAMP="$(date -u +%Y%m%d-%H%M%S)"
JOB_NAME="claude-run-$(echo "$STORY_ID" | tr 'A-Z_/' 'a-z--' | tr -cd 'a-z0-9-')-$STAMP"
TASK_MD_B64="$(base64 -w0 "$TASK_FILE" 2>/dev/null || base64 "$TASK_FILE" | tr -d '\n')"

export JOB_NAME STORY_ID TASK_MD_B64
TEMPLATE="$(dirname "$0")/job-template.yaml"

# envsubst zorgt dat ${JOB_NAME} etc. ingevuld worden
RENDERED="$(envsubst < "$TEMPLATE")"

echo "[run-story] story:  $STORY_ID"
echo "[run-story] job:    $JOB_NAME"
echo "[run-story] task:   $TASK_FILE ($(wc -c < "$TASK_FILE") bytes)"
echo

echo "$RENDERED" | oc apply -f -
echo
echo "[run-story] job aangemaakt. Volg met:"
echo "  oc logs -n personal-news-feed -f job/$JOB_NAME"
echo
echo "[run-story] PR-status:"
echo "  gh pr list --search 'head:ai/$STORY_ID'"
