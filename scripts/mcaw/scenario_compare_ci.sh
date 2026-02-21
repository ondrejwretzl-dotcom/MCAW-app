#!/usr/bin/env bash
set -euo pipefail

BASELINE_POINTER="${BASELINE_POINTER:-.ci/baselines/approved_latest.txt}"
FAIL_ON_HARD="${FAIL_ON_HARD:-true}"
FAIL_ON_SCENARIO="${FAIL_ON_SCENARIO:-false}"

GRADLE_PROPS=(
  "-Dmcaw.failOnHardRegression=${FAIL_ON_HARD}"
  "-Dmcaw.failOnScenario=${FAIL_ON_SCENARIO}"
)

if [[ -f "$BASELINE_POINTER" ]]; then
  BASELINE_PATH="$(tr -d '\r' < "$BASELINE_POINTER" | head -n1 | xargs)"
  if [[ -n "$BASELINE_PATH" && -f "$BASELINE_PATH" ]]; then
    echo "Using baseline: $BASELINE_PATH"
    GRADLE_PROPS+=("-Dmcaw.baselineSummary=${BASELINE_PATH}")
  else
    echo "Baseline pointer exists but file missing: '$BASELINE_PATH'"
  fi
else
  echo "No baseline pointer file at $BASELINE_POINTER (first run mode)."
fi

./gradlew :app:testDebugUnitTest --no-daemon "${GRADLE_PROPS[@]}"

REPORT_ROOT="app/build/reports/mcaw_scenarios"
if [[ ! -d "$REPORT_ROOT" ]]; then
  REPORT_ROOT="build/reports/mcaw_scenarios"
fi

if [[ ! -d "$REPORT_ROOT" ]]; then
  echo "Scenario report root not found." >&2
  exit 2
fi

LATEST_DIR="$(find "$REPORT_ROOT" -mindepth 1 -maxdepth 1 -type d | sort | tail -n1)"
if [[ -z "$LATEST_DIR" ]]; then
  echo "No scenario report directory found in $REPORT_ROOT" >&2
  exit 3
fi

echo "Latest report dir: $LATEST_DIR"
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  echo "latest_report_dir=$LATEST_DIR" >> "$GITHUB_OUTPUT"
fi
