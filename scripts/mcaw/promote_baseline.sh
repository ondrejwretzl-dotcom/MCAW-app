#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 5 ]]; then
  echo "Usage: $0 <summary_engine> <summary_e2e> <baseline_id> <catalog> <suite>" >&2
  exit 1
fi
SUMMARY_ENGINE="$1"
SUMMARY_E2E="$2"
BASELINE_ID="$3"
CATALOG="${4:-default}"
SUITE="${5:-both}"

promote_one() {
  local summary="$1"; local suite="$2"
  if [[ ! -f "$summary" ]]; then
    echo "Summary file not found for $suite: $summary" >&2
    exit 2
  fi
  local dest_dir=".ci/baselines/${CATALOG}/${suite}/${BASELINE_ID}"
  mkdir -p "$dest_dir"
  local dest_summary="$dest_dir/summary_${suite}.json"
  cp "$summary" "$dest_summary"
  echo "$dest_summary" > ".ci/baselines/approved_${suite}_latest.txt"
  echo "Promoted baseline -> $dest_summary"
}

case "$SUITE" in
  engine_only) promote_one "$SUMMARY_ENGINE" "engine_only" ;;
  e2e) promote_one "$SUMMARY_E2E" "e2e" ;;
  both) promote_one "$SUMMARY_ENGINE" "engine_only"; promote_one "$SUMMARY_E2E" "e2e" ;;
  *) echo "Unknown suite: $SUITE" >&2; exit 3 ;;
esac
