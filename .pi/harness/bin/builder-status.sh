#!/usr/bin/env bash
# builder-status.sh <session.sqlite> [request-id]
# Read-only view of a builder host's durable session store (builder-host/host.ts): each submission's
# status and reason (only <request-id>'s when given), the count of non-terminal tasks, and next_seq.
# Opens the store with sqlite3 -readonly; it writes nothing, and is safe beside a running host.
set -euo pipefail
DB="${1:?session.sqlite}"; RID="${2:-}"
[[ -f "$DB" ]] || { echo "no store: $DB" >&2; exit 2; }
# A WAL store with no -wal file (no host has it open) cannot be opened read-only the usual way, since
# SQLite would have to create its -shm file; then it is read as immutable, which writes nothing either.
uri="file:$DB?mode=ro"; [[ -e "$DB-wal" ]] || uri="file:$DB?immutable=1"
q() { sqlite3 -readonly -separator ' ' "$uri" "$@"; }
sq="'"; where=""; [[ -n "$RID" ]] && where="WHERE json_extract(record,'\$.requestId') = '${RID//$sq/$sq$sq}'"
echo "submissions (id request_id status reason):"
q "SELECT id, ifnull(json_extract(record,'\$.requestId'),'-'), status, ifnull(json_extract(record,'\$.reason'),'-') FROM submissions $where ORDER BY id;"
echo "non_terminal_tasks=$(q "SELECT count(*) FROM tasks WHERE status <> 'terminal';")"
echo "next_seq=$(q "SELECT next_seq FROM durable_metadata;")"
