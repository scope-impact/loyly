#!/usr/bin/env bash
# LOYLY_BUILDER_CMD: <builder-room> <instruction-file> [<builder-image>]
# (../README.md, "The builder command"; ../../spec/cycle-runner/seam.md, "Handed in").
# pi on the host (the existing openai-codex login), its built-in tools routed into a Gondolin VM by
# pi's own gondolin example extension. The VM's guest is <builder-image>, the path
# LOYLY_BUILDER_IMAGES/BUILDER_IMAGE that the runner hands in when the configuration names one
# (spec round 9, D1, D4); without it, the TypeScript builder image, as in the shadow runs.
# The wrapper only records the launch and pi's output under BUILDER_EVIDENCE_DIR; it changes
# nothing pi sees.
#   PI_GONDOLIN_EXTENSION  pi's examples/extensions/gondolin, copied unmodified, its node_modules
#                          resolving @earendil-works/gondolin from .pi/harness/node_modules
#   BUILDER_EVIDENCE_DIR   where each launch's record and pi's sessions go
#   PI_HARNESS_STATE_ROOT  the state root (default .pi/harness/.state beside this script)
set -uo pipefail
ROOM="${1:?builder-room}"; INSTR="${2:?instruction-file}"; IMAGE="${3:-}"
EXT="${PI_GONDOLIN_EXTENSION:?PI_GONDOLIN_EXTENSION: pi gondolin extension directory}"
EV="${BUILDER_EVIDENCE_DIR:?BUILDER_EVIDENCE_DIR: evidence directory}"
STATE="${PI_HARNESS_STATE_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/.state}"
if [[ -n "$IMAGE" ]]; then
  export GONDOLIN_GUEST_DIR="$IMAGE"
else
  export GONDOLIN_GUEST_DIR="$STATE/assets/builder"
fi
[[ -d "$GONDOLIN_GUEST_DIR" ]] || { echo "builder image not found: $GONDOLIN_GUEST_DIR" >&2; exit 2; }
mkdir -p "$EV"
n=$(ls -d "$EV"/launch-* 2>/dev/null | wc -l | tr -d ' '); n=$((n+1)); L="$EV/launch-$n"; mkdir -p "$L"
{ echo "room=$ROOM"; echo "instruction=$INSTR"
  echo "instruction_sha256=$(shasum -a 256 "$INSTR" | cut -d' ' -f1)"
  echo "builder_image=$GONDOLIN_GUEST_DIR"
  echo "start=$(date -u +%FT%TZ)"; echo "cwd_before=$(pwd)"; } > "$L/launch.txt"
cp "$INSTR" "$L/instruction-as-handed.md"
cd "$ROOM" || exit 2
set -x
pi -e "$EXT" --model openai-codex/gpt-6.1-sol --thinking high --no-skills --no-prompt-templates \
  --session-dir "$EV/sessions" -p @"$INSTR" "Build it now, as the attached instruction says." \
  > "$L/pi.stdout" 2> "$L/pi.stderr"
rc=$?
set +x
{ echo "end=$(date -u +%FT%TZ)"; echo "exit=$rc"; } >> "$L/launch.txt"
exit $rc
