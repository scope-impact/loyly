#!/usr/bin/env bash
# grader.sh <grading-room> <instruction-file> <image-dir>
# The black-box grader: the same durable pi host as builder.sh (../builder-host/host.ts), one
# pi-durable session whose tools run in a Gondolin VM that mounts only <grading-room> (built by
# grading-room.sh), booted from <image-dir> (for a Clojure candidate, assets/builder-clojure). The
# grader writes work/verdicts.json in the room; this copies it to launch-N/verdicts.json and
# $GRADER_EVIDENCE_DIR/verdicts.json, for score.sh. Nothing steers a grader: no steer inbox.
#   GRADER_EVIDENCE_DIR        where each launch's record and session store go (launch-N/)
#   LOYLY_GRADER_MODEL         provider/modelId (default openai-codex/gpt-6.1-terra: a Codex model
#                              other than the builder's gpt-6.1-sol)
#   LOYLY_BUILDER_EVIDENCE_DIR optional: the cycle's builder evidence; the grader refuses to start
#                              when its model equals the model= of the builder's last launch.txt
#                              (a launch.txt without model= ran host.ts's default, gpt-6.1-sol)
#   LOYLY_GRADER_PI_SETTINGS   pi settings selecting the tools (default ../config/pi.grader.settings.json)
#   LOYLY_VM_MEMORY, LOYLY_VM_CPUS  the VM's size (vm-daemon.ts); unset: Gondolin's 1 GB, 2 CPUs
#   LOYLY_GRADER_ROUNDS        rounds at most (default 6). A model may answer while items are still
#                              unreached; then the same session (same store) gets one more input,
#                              round-N, that names only the ids whose evidence in work/verdicts.json
#                              starts with "not reached" and says to continue as the instruction says.
#                              Mechanical and content-free: it carries nothing but the grader's own ids.
# Exit: host.ts's (0 answered, 1 unanswered, 2 setup error); 3 answered but no work/verdicts.json.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOM="${1:?grading-room}"; INSTR="${2:?instruction-file}"; IMAGE="${3:?image-dir}"
EV="${GRADER_EVIDENCE_DIR:?GRADER_EVIDENCE_DIR: evidence directory}"
HOST="$(cd "$HERE/../builder-host" && pwd)"
MODEL="${LOYLY_GRADER_MODEL:-openai-codex/gpt-6.1-terra}"
BUILDER_DEFAULT_MODEL="openai-codex/gpt-6.1-sol"
PI_SETTINGS="${LOYLY_GRADER_PI_SETTINGS:-$HERE/../config/pi.grader.settings.json}"
[[ -f "$PI_SETTINGS" ]] || { echo "pi settings not found: $PI_SETTINGS" >&2; exit 2; }
PI_SETTINGS="$(cd "$(dirname "$PI_SETTINGS")" && pwd)/$(basename "$PI_SETTINGS")"
[[ -d "$IMAGE" ]] || { echo "grader image not found: $IMAGE" >&2; exit 2; }
[[ -d "$HOST/node_modules" ]] || { echo "host not installed: npm ci in $HOST" >&2; exit 2; }
[[ -d "$ROOM/work" ]] || { echo "not a grading room (no work/): $ROOM (grading-room.sh builds one)" >&2; exit 2; }
if [[ -n "${LOYLY_BUILDER_EVIDENCE_DIR:-}" ]]; then
  last="$(ls -t "$LOYLY_BUILDER_EVIDENCE_DIR"/launch-*/launch.txt 2>/dev/null | head -1)"
  [[ -n "$last" ]] || { echo "no builder launch.txt in $LOYLY_BUILDER_EVIDENCE_DIR" >&2; exit 2; }
  bmodel="$(sed -n 's/^model=//p' "$last" | tail -1)"; bmodel="${bmodel:-$BUILDER_DEFAULT_MODEL}"
  [[ "$bmodel" != "$MODEL" ]] || {
    echo "refused: the grader's model $MODEL is the builder's ($last); set LOYLY_GRADER_MODEL" >&2; exit 2; }
fi
export GONDOLIN_GUEST_DIR="$(cd "$IMAGE" && pwd)"
ROOM="$(cd "$ROOM" && pwd)"
INSTR="$(cd "$(dirname "$INSTR")" && pwd)/$(basename "$INSTR")"
mkdir -p "$EV"
n=$(ls -d "$EV"/launch-* 2>/dev/null | wc -l | tr -d ' '); n=$((n+1)); L="$EV/launch-$n"; mkdir -p "$L"
STORE="$L/session.sqlite"; RID="grade-$n"
sha() { [[ -f "$1" ]] && shasum -a 256 "$1" | cut -d' ' -f1 || echo missing; }
{ echo "kind=grader"; echo "room=$ROOM"; echo "instruction=$INSTR"; echo "instruction_sha256=$(sha "$INSTR")"
  echo "grader_image=$GONDOLIN_GUEST_DIR"; echo "image_manifest_sha256=$(sha "$GONDOLIN_GUEST_DIR/manifest.json")"
  echo "pi_settings=$PI_SETTINGS"; echo "pi_settings_sha256=$(sha "$PI_SETTINGS")"
  echo "vm_policy=$HOST/policy.ts"; echo "vm_policy_sha256=$(sha "$HOST/policy.ts")"
  echo "vm_memory=${LOYLY_VM_MEMORY:-}"; echo "vm_cpus=${LOYLY_VM_CPUS:-}"
  echo "model=$MODEL"; echo "builder_model=${bmodel:-}"
  [[ -f "$ROOM/.grading-room.txt" ]] && sed 's/^/grading_room./' "$ROOM/.grading-room.txt"
  echo "store=$STORE"; echo "request_id=$RID"; echo "wrapper_pid=$$"
  echo "start=$(date -u +%FT%TZ)"; } > "$L/launch.txt"
cp "$INSTR" "$L/instruction-as-handed.md"
cd "$ROOM" || exit 2
unset LOYLY_STEER_DIR
ROUNDS="${LOYLY_GRADER_ROUNDS:-6}"
unreached() {  # the ids whose evidence starts with "not reached", one per line
  [[ -f "$ROOM/work/verdicts.json" ]] || return 0
  node -e 'try{for(const x of JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")))if(/^\s*not reached/i.test(String(x.evidence??"")))console.log(x.id)}catch{}' "$ROOM/work/verdicts.json"
}
stopped=0
trap 'stopped=1; kill -TERM "$pid" 2>/dev/null' TERM INT
round=1; in="$INSTR"; rid="$RID"
while :; do
  LOYLY_PI_MODEL="$MODEL" LOYLY_PI_PROMPT="Grade it now, as the attached instruction says." \
  LOYLY_ROOM_LABEL="the grading room" \
    node "$HOST/host.ts" "$ROOM" "$in" "$STORE" "$rid" "$PI_SETTINGS" \
    < /dev/null >> "$L/pi.stdout" 2>> "$L/pi.stderr" &
  pid=$!
  echo "host_pid=$pid" >> "$L/launch.txt"
  wait "$pid"; rc=$?
  while kill -0 "$pid" 2>/dev/null; do wait "$pid"; rc=$?; done
  left="$(unreached)"; n=$(printf '%s' "$left" | grep -c . || true)
  echo "round=$round request_id=$rid exit=$rc unreached=$n at=$(date -u +%FT%TZ)" >> "$L/launch.txt"
  (( rc == 0 && n > 0 && round < ROUNDS && stopped == 0 )) || break
  round=$((round+1)); rid="$RID-round-$round"; in="$L/round-$round.md"
  { echo "$n items in work/verdicts.json are still marked not reached:"; echo
    echo "$left" | tr '\n' ' '; echo; echo
    echo "Continue grading them, as the attached grading instruction says, and update work/verdicts.json."
    echo "Answer only when every item has a verdict you reached by trying it."; } > "$in"
done
if [[ -f "$ROOM/work/verdicts.json" ]]; then
  cp "$ROOM/work/verdicts.json" "$L/verdicts.json"; cp "$ROOM/work/verdicts.json" "$EV/verdicts.json"
  [[ -f "$ROOM/work/surprises.json" ]] && cp "$ROOM/work/surprises.json" "$L/surprises.json"
  node -e 'const v=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));const c={};for(const x of v)c[x.verdict]=(c[x.verdict]||0)+1;console.log(`verdicts=${v.length} ${JSON.stringify(c)}`)' "$L/verdicts.json" >> "$L/launch.txt" 2>&1
elif [[ $rc -eq 0 ]]; then
  rc=3
fi
{ echo "end=$(date -u +%FT%TZ)"; echo "exit=$rc"; } >> "$L/launch.txt"
exit $rc
