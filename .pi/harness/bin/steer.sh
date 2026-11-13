#!/usr/bin/env bash
# steer.sh <evidence-dir> <message-file>
# Adds a message to a running builder session: the file goes, verbatim, into the launch's steer
# inbox (launch.txt steer_dir=), which the builder host (../builder-host/host.ts, LOYLY_STEER_DIR)
# submits to its own pi-durable session as a steer: `root.submit({ type: "input", whenBusy:
# "steer", whenIdle: "reject", requestId: "steer-<name>" })`, placed after the current tool round
# (pi-durable README, "Busy Conversations"). The host is the store's only writer; nothing here
# touches the SQLite store. Refused for a grader (steering the instrument would bias the score),
# for an ended session, and for a launch whose host has no steer inbox (launched before it existed).
# Appends the steer, verbatim, with its time, the session cursor and the host's answer, to
# <evidence-dir>/steers.jsonl. Guardrails (../supervisor/README.md): a steer draws only on the
# builder's own session, the room's spec and the gate's public contract; never on grading
# evidence, verdicts, scores or other candidates, and never states a scenario's answer.
# Exit 0 steered, 1 refused by the host or not answered in 60 s, 2 refused here.
set -uo pipefail
EVD="${1:?evidence-dir}"; MSG="${2:?message-file}"
[[ -s "$MSG" ]] || { echo "empty or missing message: $MSG" >&2; exit 2; }
if [[ -f "$EVD/launch.txt" ]]; then L="$EVD"; else
  L="$(ls -d "$EVD"/launch-* 2>/dev/null | sort -t- -k2 -n | tail -1)"; fi
[[ -n "$L" && -f "$L/launch.txt" ]] || { echo "no launch.txt in $EVD" >&2; exit 2; }
L="$(cd "$L" && pwd)"
get() { sed -n "s/^$1=//p" "$L/launch.txt" | tail -1; }
[[ "$(get kind)" == "grader" ]] && { echo "refused: $L is a grader; a grader is reported and stopped, never steered" >&2; exit 2; }
[[ -n "$(get end)" ]] && { echo "refused: $L ended at $(get end)" >&2; exit 2; }
SD="$(get steer_dir)"
[[ -n "$SD" && -d "$SD" ]] || { echo "refused: $L has no steer inbox (its host predates steer_dir=)" >&2; exit 2; }
STORE="$(get store)"
cursor="$(node -e 'const {DatabaseSync}=require("node:sqlite");const d=new DatabaseSync(process.argv[1],{readOnly:true});console.log(d.prepare("select max(id) m from entries").get().m??0)' "$STORE" 2>/dev/null || echo "?")"
name="s$(date -u +%Y%m%dT%H%M%SZ)-$$"
cp "$MSG" "$SD/$name.tmp" && mv "$SD/$name.tmp" "$SD/$name.md"
outcome="pending"
for _ in $(seq 60); do
  if [[ -f "$SD/$name.steered" ]]; then outcome="steered: $(cat "$SD/$name.steered")"; break; fi
  if [[ -f "$SD/$name.refused" ]]; then outcome="refused: $(cat "$SD/$name.refused")"; break; fi
  sleep 1
done
node -e '
const fs=require("fs");const [log,launch,name,cursor,msg,outcome]=process.argv.slice(1);
const message=fs.readFileSync(msg,"utf8");
const sha=require("crypto").createHash("sha256").update(message).digest("hex");
fs.appendFileSync(log, JSON.stringify({time:new Date().toISOString(),launch,name,request_id:`steer-${name}`,cursor:Number(cursor)||cursor,message,message_sha256:sha,outcome:outcome.trim()})+"\n");
' "$EVD/steers.jsonl" "$L" "$name" "$cursor" "$MSG" "$outcome"
echo "$name: $outcome (cursor $cursor; logged to $EVD/steers.jsonl)"
[[ "$outcome" == steered* ]]
