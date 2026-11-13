#!/usr/bin/env bash
# stop.sh <evidence-dir> <reason>
# Stops a running builder or grader session by its own recorded pids (launch.txt wrapper_pid= and
# host_pid=, written by builder.sh and grader.sh); never by name, never kill-all, never SIGKILL.
# SIGINT to the wrapper (builder.sh or grader.sh), which forwards SIGTERM to the host; after 60 s,
# or at once when no wrapper runs, SIGTERM to the host itself.
# The host aborts the run, the submission settles unanswered (reason aborted) and the store closes;
# the same session id resumes it later. Before signalling, the host pid's command line must name
# host.ts and this launch's store. Appends {time, launch, reason, pids, signals, outcome} to
# <evidence-dir>/stops.jsonl. Exit 0 stopped, 1 still running after both signals, 2 refused.
set -uo pipefail
EVD="${1:?evidence-dir}"; REASON="${2:?reason}"
if [[ -f "$EVD/launch.txt" ]]; then L="$EVD"; else
  L="$(ls -d "$EVD"/launch-* 2>/dev/null | sort -t- -k2 -n | tail -1)"; fi
[[ -n "$L" && -f "$L/launch.txt" ]] || { echo "no launch.txt in $EVD" >&2; exit 2; }
L="$(cd "$L" && pwd)"
get() { sed -n "s/^$1=//p" "$L/launch.txt" | tail -1; }
[[ -n "$(get end)" ]] && { echo "refused: $L already ended at $(get end)" >&2; exit 2; }
WP="$(get wrapper_pid)"; HP="$(get host_pid)"; STORE="$(get store)"
[[ "$HP" =~ ^[0-9]+$ ]] || { echo "refused: $L records no host_pid= (launched before pids were recorded)" >&2; exit 2; }
args="$(ps -o args= -p "$HP" 2>/dev/null)"
[[ "$args" == *host.ts* && "$args" == *"$STORE"* ]] || {
  echo "refused: pid $HP is not this launch's host (${args:-not running})" >&2; exit 2; }
alive() { kill -0 "$1" 2>/dev/null; }
signals=()
wargs="$( [[ "$WP" =~ ^[0-9]+$ ]] && ps -o args= -p "$WP" 2>/dev/null)"
if [[ "$wargs" == *builder.sh* || "$wargs" == *grader.sh* ]]; then
  kill -INT "$WP" && signals+=("SIGINT wrapper $WP")
  for _ in $(seq 60); do alive "$HP" || break; sleep 1; done
fi
if alive "$HP"; then
  kill -TERM "$HP" && signals+=("SIGTERM host $HP")
  for _ in $(seq 60); do alive "$HP" || break; sleep 1; done
fi
alive "$HP" && outcome="still running" || outcome="stopped"
node -e '
const fs=require("fs");const [log,launch,reason,wp,hp,signals,outcome]=process.argv.slice(1);
fs.appendFileSync(log, JSON.stringify({time:new Date().toISOString(),launch,reason,wrapper_pid:Number(wp)||null,host_pid:Number(hp),signals:signals?signals.split("|"):[],outcome})+"\n");
' "$EVD/stops.jsonl" "$L" "$REASON" "$WP" "$HP" "$(IFS='|'; echo "${signals[*]:-}")" "$outcome"
echo "$L: $outcome (${signals[*]:-no signal sent}); logged to $EVD/stops.jsonl"
[[ "$outcome" == stopped ]]
