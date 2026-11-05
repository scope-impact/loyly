#!/usr/bin/env bash
# The carried calibration fixture: the real harness from this checkout, with whatever state
# root the grader supplies, so calibration never touches the operator's state.
cd "$(dirname "$0")/../../.."
exec env PI_HARNESS_PROJECT_ROOT="$PWD" .pi/harness/orc-worker/run.sh
