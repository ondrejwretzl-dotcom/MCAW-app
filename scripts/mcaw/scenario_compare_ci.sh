#!/usr/bin/env bash
set -euo pipefail

BASELINE_POINTER_ENGINE="${BASELINE_POINTER_ENGINE:-.ci/baselines/approved_engine_only_latest.txt}"
BASELINE_POINTER_E2E="${BASELINE_POINTER_E2E:-.ci/baselines/approved_e2e_latest.txt}"
FAIL_ON_HARD_REGRESSION="${FAIL_ON_HARD_REGRESSION:-true}"
FAIL_ON_SCENARIO="${FAIL_ON_SCENARIO:-false}"

GRADLE_PROPS=(
  "-Dmcaw.failOnHardRegression=${FAIL_ON_HARD_REGRESSION}"
  "-Dmcaw.failOnScenario=${FAIL_ON_SCENARIO}"
)

for suite in engine_only e2e; do
  if [[ "$suite" == "engine_only" ]]; then
    POINTER="$BASELINE_POINTER_ENGINE"
    PROP="mcaw.baselineSummaryEngineOnly"
  else
    POINTER="$BASELINE_POINTER_E2E"
    PROP="mcaw.baselineSummaryE2E"
  fi

  if [[ -f "$POINTER" ]]; then
    BASELINE_PATH="$(tr -d '\r' < "$POINTER" | head -n1 | xargs)"
    if [[ -n "$BASELINE_PATH" && -f "$BASELINE_PATH" ]]; then
      echo "Using $suite baseline: $BASELINE_PATH"
      GRADLE_PROPS+=("-D${PROP}=${BASELINE_PATH}")
    else
      echo "Baseline pointer exists but file missing: '$BASELINE_PATH'"
    fi
  else
    echo "No baseline pointer file at $POINTER (first run mode)."
  fi
done

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
