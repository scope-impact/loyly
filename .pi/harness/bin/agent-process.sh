#!/usr/bin/env bash
# Provider-neutral process entrypoint. ORC supplies the role's room and bounded lifetime;
# the selected host owns only one agent session and its tools.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
role="${1:?builder or grader}"; shift
case "$role" in
  builder)
    (( $# == 2 || $# == 3 )) || { echo "builder expects room, instruction and optional image" >&2; exit 2; }
    provider="${LOYLY_BUILDER_ADAPTER:-pi}"
    host="${LOYLY_BUILDER_PROCESS_HOST:-}"
    pi_host="$HERE/builder.sh"
    ;;
  grader)
    (( $# == 3 )) || { echo "grader expects room, instruction and image" >&2; exit 2; }
    provider="${LOYLY_GRADER_ADAPTER:-pi}"
    host="${LOYLY_GRADER_PROCESS_HOST:-}"
    pi_host="$HERE/grader.sh"
    ;;
  *) echo "unknown agent role: $role" >&2; exit 2 ;;
esac
case "$provider" in
  pi)
    if [[ "$role" == builder && -n "${LOYLY_BUILDER_SESSION_ID:-}" &&
          ( ! "${LOYLY_BUILDER_SESSION_ID}" =~ ^[A-Za-z0-9._-]+$ ||
            "${LOYLY_BUILDER_SESSION_ID}" == . || "${LOYLY_BUILDER_SESSION_ID}" == .. ) ]]; then
      # ORC names sessions run/tick/attempt. Keep the Pi host's safe session-key
      # contract unchanged while giving each ORC identity a stable durable key.
      original_session="$LOYLY_BUILDER_SESSION_ID"
      safe_session="orc-$(printf '%s' "$original_session" | shasum -a 256 | cut -d' ' -f1)"
      export LOYLY_BUILDER_SESSION_ID="$safe_session"
      if [[ -n "${BUILDER_EVIDENCE_DIR:-}" ]]; then
        mkdir -p "$BUILDER_EVIDENCE_DIR/sessions/$safe_session"
        printf '%s\n' "$original_session" > "$BUILDER_EVIDENCE_DIR/sessions/$safe_session/orc-session-id"
      fi
    fi
    exec "$pi_host" "$@"
    ;;
  process)
    [[ "$host" = /* && -f "$host" && -x "$host" ]] || {
      echo "$role process host must be an absolute executable path" >&2; exit 2; }
    exec "$host" "$@"
    ;;
  *) echo "unknown $role adapter: $provider" >&2; exit 2 ;;
esac
