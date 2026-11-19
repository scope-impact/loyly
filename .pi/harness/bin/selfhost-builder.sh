#!/usr/bin/env bash
# Transitional slice-06 self-host host: continue the same Pi session on its own uncarried IDs
# before returning the candidate to ORC's independent gate. No grade evidence enters the room.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOM="${1:?builder room}"; INSTR="${2:?instruction file}"; IMAGE="${3:?builder image}"
EV="${BUILDER_EVIDENCE_DIR:?BUILDER_EVIDENCE_DIR required}"
APP="$ROOM/harness-impl/06-cycle-runner"
mkdir -p "$EV/continuations"
sha_tree() {
  ruby -r digest -e 'root=ARGV[0]; files=Dir.glob(root+"/**/*", File::FNM_DOTMATCH).select{|f| File.file?(f)}.sort; puts Digest::SHA256.hexdigest(files.map{|f| f.delete_prefix(root+"/")+"\t"+Digest::SHA256.file(f).hexdigest+"\n"}.join)' "$APP"
}
check_scope() {
  local bad
  bad="$(git -C "$ROOM" status --porcelain --untracked-files=all | sed -n '/^?? harness-impl\/06-cycle-runner\//d; /./p')"
  if [[ -n "$bad" ]]; then printf 'builder wrote outside slice-06 scope:\n%s\n' "$bad" >&2; return 1; fi
}
run_turn() {
  local turn="$1" instruction="$2"
  LOYLY_BUILDER_TURN="$turn" "$HERE/builder-process.sh" "$ROOM" "$instruction" "$IMAGE"
}
run_turn 1 "$INSTR"; rc=$?
check_scope || exit 2
for turn in 2 3 4; do
  [[ -f "$APP/UNCARRIED.md" ]] || break
  ids="$(sed -nE 's/^- (@item-06-[0-9]{3}):.*/\1/p' "$APP/UNCARRIED.md")"
  [[ -n "$ids" ]] || break
  before="$(sha_tree)"
  continuation="$EV/continuations/turn-$turn.md"
  {
    printf 'Your prior turn left these slice-06 items in UNCARRIED.md:\n%s\n\n' "$ids"
    printf '%s\n' 'Continue the same builder session in the same clean room. For each ID, write a failing public-seam test naming it, make it pass, remove its UNCARRIED.md line, then rerun test.sh and a fresh-store identity check. Leave a line only for an item you truly cannot carry, with a reason. Keep the whole suite green. Read no grading evidence and write only harness-impl/06-cycle-runner/.'
  } > "$continuation"
  run_turn "$turn" "$continuation"; rc=$?
  check_scope || exit 2
  after="$(sha_tree)"
  printf 'turn=%s before=%s after=%s exit=%s remaining=%s\n' "$turn" "$before" "$after" "$rc" "$(printf '%s\n' "$ids" | wc -l | tr -d ' ')" >> "$EV/continuations/audit.txt"
  [[ "$after" != "$before" ]] || break
done
exit "$rc"
