#!/usr/bin/env bash
# LOYLY_BUILDER_CMD: <builder-room> <instruction-file> [<builder-image>]
# (../README.md, "The builder command"; ../../spec/cycle-runner/seam.md, "Handed in").
# The durable builder host (../builder-host/host.ts, @earendil-works/pi-durable) on the host, with pi's
# existing openai-codex login and the session's SQLite store; every tool call runs in a Gondolin VM
# that mounts only the builder room. The VM's guest is <builder-image>, the path
# LOYLY_BUILDER_IMAGES/BUILDER_IMAGE that the runner hands in when the configuration names one
# (spec round 9, D1, D4); without it, the TypeScript builder image, as in the shadow runs.
# ORC owns the workflow (attempts, retries, timeouts); the host owns only the session: the same
# LOYLY_BUILDER_SESSION_ID reopens the same store and request id, so a rerun resumes, never restarts.
# The wrapper only records the launch and the host's output under BUILDER_EVIDENCE_DIR; it changes
# nothing the builder sees.
#   BUILDER_EVIDENCE_DIR      where each launch's record (and session stores) go
#   LOYLY_BUILDER_SESSION_ID  optional: the durable session's key, [A-Za-z0-9._-]; store
#                             $BUILDER_EVIDENCE_DIR/sessions/<id>/session.sqlite, request id <id>.
#                             Without it: launch-N/session.sqlite, request id launch-N (a fresh session).
#   LOYLY_BUILDER_PI_SETTINGS a pi settings.json whose defaultTools selects the builder's tools
#                             (default ../config/pi.builder.settings.json)
#   PI_HARNESS_STATE_ROOT     the state root (default .pi/harness/.state beside this script)
# launch.txt records the pi settings file, the image's Gondolin manifest.json and the VM policy
# (../builder-host/policy.ts), each with its sha256: what the builder was given and told.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOM="${1:?builder-room}"; INSTR="${2:?instruction-file}"; IMAGE="${3:-}"
EV="${BUILDER_EVIDENCE_DIR:?BUILDER_EVIDENCE_DIR: evidence directory}"
STATE="${PI_HARNESS_STATE_ROOT:-$HERE/../.state}"
HOST="$(cd "$HERE/../builder-host" && pwd)"
PI_SETTINGS="${LOYLY_BUILDER_PI_SETTINGS:-$HERE/../config/pi.builder.settings.json}"
[[ -f "$PI_SETTINGS" ]] || { echo "pi settings not found: $PI_SETTINGS" >&2; exit 2; }
PI_SETTINGS="$(cd "$(dirname "$PI_SETTINGS")" && pwd)/$(basename "$PI_SETTINGS")"
SID="${LOYLY_BUILDER_SESSION_ID:-}"
[[ -z "$SID" || "$SID" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "bad LOYLY_BUILDER_SESSION_ID: $SID" >&2; exit 2; }
if [[ -n "$IMAGE" ]]; then
  export GONDOLIN_GUEST_DIR="$IMAGE"
else
  export GONDOLIN_GUEST_DIR="$STATE/assets/builder"
fi
[[ -d "$GONDOLIN_GUEST_DIR" ]] || { echo "builder image not found: $GONDOLIN_GUEST_DIR" >&2; exit 2; }
[[ -d "$HOST/node_modules" ]] || { echo "builder host not installed: npm ci in $HOST" >&2; exit 2; }
ROOM="$(cd "$ROOM" && pwd)" || exit 2
INSTR="$(cd "$(dirname "$INSTR")" && pwd)/$(basename "$INSTR")"
mkdir -p "$EV"
n=$(ls -d "$EV"/launch-* 2>/dev/null | wc -l | tr -d ' '); n=$((n+1)); L="$EV/launch-$n"; mkdir -p "$L"
if [[ -n "$SID" ]]; then
  STORE="$EV/sessions/$SID/session.sqlite"; RID="$SID"
else
  STORE="$L/session.sqlite"; RID="launch-$n"
fi
mkdir -p "$(dirname "$STORE")"
{ echo "room=$ROOM"; echo "instruction=$INSTR"
  echo "instruction_sha256=$(shasum -a 256 "$INSTR" | cut -d' ' -f1)"
  echo "builder_image=$GONDOLIN_GUEST_DIR"
  sha() { [[ -f "$1" ]] && shasum -a 256 "$1" | cut -d' ' -f1 || echo missing; }
  echo "image_manifest=$GONDOLIN_GUEST_DIR/manifest.json"; echo "image_manifest_sha256=$(sha "$GONDOLIN_GUEST_DIR/manifest.json")"
  echo "pi_settings=$PI_SETTINGS"; echo "pi_settings_sha256=$(sha "$PI_SETTINGS")"
  echo "vm_policy=$HOST/policy.ts"; echo "vm_policy_sha256=$(sha "$HOST/policy.ts")"
  echo "session_id=$SID"; echo "store=$STORE"; echo "request_id=$RID"
  echo "start=$(date -u +%FT%TZ)"; echo "cwd_before=$(pwd)"; } > "$L/launch.txt"
cp "$INSTR" "$L/instruction-as-handed.md"
cd "$ROOM" || exit 2
set -x
node "$HOST/host.ts" "$ROOM" "$INSTR" "$STORE" "$RID" "$PI_SETTINGS" \
  < /dev/null > "$L/pi.stdout" 2> "$L/pi.stderr" &
pid=$!
set +x
# SIGTERM to the wrapper reaches the host, which aborts the run and closes the store.
trap 'kill -TERM "$pid" 2>/dev/null' TERM INT
wait "$pid"; rc=$?
while kill -0 "$pid" 2>/dev/null; do wait "$pid"; rc=$?; done
# What the builder had to install, for the image's Dockerfile (builder-host/policy.ts, IMAGE_ADDITIONS):
# the room's .builder/, outside APP_DIR, so the candidate digest never covers it.
[[ -f "$ROOM/.builder/image-additions.txt" ]] && cp "$ROOM/.builder/image-additions.txt" "$L/image-additions.txt"
{ echo "end=$(date -u +%FT%TZ)"; echo "exit=$rc"; } >> "$L/launch.txt"
exit $rc
