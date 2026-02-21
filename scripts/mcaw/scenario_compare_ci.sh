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

set +e
./gradlew :app:testDebugUnitTest --no-daemon "${GRADLE_PROPS[@]}"
GRADLE_EXIT=$?
set -e

REPORT_ROOT="app/build/reports/mcaw_scenarios"
if [[ ! -d "$REPORT_ROOT" ]]; then
  REPORT_ROOT="build/reports/mcaw_scenarios"
fi

LATEST_DIR=""
if [[ -d "$REPORT_ROOT" ]]; then
  LATEST_DIR="$(find "$REPORT_ROOT" -mindepth 1 -maxdepth 1 -type d | sort | tail -n1)"
fi

if [[ -z "$LATEST_DIR" ]]; then
  echo "No scenario report directory found. Root checked: $REPORT_ROOT"
else
  echo "Latest report dir: $LATEST_DIR"
fi

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  echo "latest_report_dir=$LATEST_DIR" >> "$GITHUB_OUTPUT"
  echo "gradle_exit_code=$GRADLE_EXIT" >> "$GITHUB_OUTPUT"
fi

exit "$GRADLE_EXIT"
