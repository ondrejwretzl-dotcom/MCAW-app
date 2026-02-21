#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <summary_json_path> <baseline_id> [catalog]" >&2
  exit 1
fi

SUMMARY_PATH="$1"
BASELINE_ID="$2"
CATALOG="${3:-default}"

if [[ ! -f "$SUMMARY_PATH" ]]; then
  echo "Summary file not found: $SUMMARY_PATH" >&2
  exit 2
fi

DEST_DIR=".ci/baselines/${CATALOG}/${BASELINE_ID}"
mkdir -p "$DEST_DIR"
DEST_SUMMARY="$DEST_DIR/summary.json"
cp "$SUMMARY_PATH" "$DEST_SUMMARY"

echo "$DEST_SUMMARY" > .ci/baselines/approved_latest.txt

echo "Promoted baseline -> $DEST_SUMMARY"
echo "Updated pointer -> .ci/baselines/approved_latest.txt"
