# harness/config.sh: everything this repository knows about itself, read by fence.sh,
# verify.sh, probe.sh, check.sh, and modules.sh. Here the product being regenerated is the
# Pi/ORC harness itself, and its spec is spec v2: .pi/plan/NN-context/spec.feature, where each
# .pi/plan/NN-context is a relative symlink to ../spec/<context>, added when the product owner
# accepts that context (.pi/spec/decisions.md, "First light").

# Where the regenerated harness is built inside the clean room. It must provide
# APP_DIR/test.sh (the whole suite) and APP_DIR/start.sh (speaks JSON-RPC on stdio). The
# harness has no port: it is not a web product. A cycle of one context (SLICE, below) builds
# harness-impl/NN-context instead, and its seam is .pi/spec/<context>/seam.md.
APP_DIR=harness-impl

# The stack the regenerated harness must use. The builder is told this; the gate does not
# care, which is the point of the two-script contract.
APP_STACK="Clojure (deps.edn, Clojure CLI) on the ORC workflow engine with Grain for the event store, both fetched by git coordinate"

# The spec root. harness/hjson.clj reads this line, so the tools and modules.sh agree.
PLAN_DIR=.pi/plan

# What the identity probe expects the cycle contract to name.
APP_MARKER=pi-harness
APP_PROBE=harness/probe.sh

# FENCED_PATHS: what the clean room must not contain, space separated, relative to the
# repository root. Each entry is removed from the working tree and the history, and fence.sh
# proves it is unreachable in the object graph. Every entry must exist: a fenced path that
# matches nothing stops the fence. This is a clean generation, so no earlier implementation
# of the harness exists to fence. The builder adapter and its Gondolin runtime under
# .pi/harness are provided components, like ORC and Grain, and stay in the room.
#   archive/legacy-harness/cycles  recorded legacy measurements
#   docs/plan impl raw     the Löyly product: its spec, implementation, and sources. A room
#                          holds one product; with another product's spec in it, a builder
#                          rebuilt that product instead (harness-regen-2).
# Once this harness's own cycle records are committed under harness/cycles, add that path.
FENCED_PATHS="archive/legacy-harness/cycles docs/plan impl raw"

# SIBLING_REPOS is not declared: no other repository carries this harness, so modules.sh
# names no sibling and compares nothing, and says so.

# ---- one context per cycle -------------------------------------------------------------
# SLICE=NN in the environment (SLICE=01 harness/fence.sh ROOM) makes this a cycle of the one
# context that .pi/plan/NN-* links to (.pi/spec/decisions.md, "First light"):
#   APP_DIR       harness-impl/NN-context, the context's own candidate
#   APP_PROBE     harness/v2/probe-NN.sh, the identity probe over that context's seam
#   FENCED_PATHS  everything a builder of that context must not read, on top of the above:
#     spec v1      .pi/model (all but GLOSSARY.md, which spec v2 uses), .pi/architecture.md,
#                  .pi/affordances.schema.json, and its tools (tools/spec, all but v2)
#     other spec   every other .pi/spec/<context> and .pi/plan link, the R0 board and the
#                  legacy trace (event-storm.*, legacy-trace.md). A producer whose handoff
#                  events the context consumes (a :<producer>/<event> its event-model names
#                  that the producer's event-model/handoffs.edn defines) is fenced file by
#                  file, all but that handoffs.edn: the contract the stubbed neighbour keeps
#                  (decisions.md, "Slice 02"). Slices 01 and 03 consume none; slice 02 keeps
#                  the handoffs.edn of specification, cycle-preparation and regeneration-cycle.
#     prior work   the legacy driver in harness/ (it implements the stamper, the checklist,
#                  the score, the fence and the gate: every context's earlier answer), its
#                  archive, tools/spec/v2/stamp.clj, the instrument in harness/v2, and every
#                  other context's harness-impl/NN-*. archive/legacy-harness covers its cycles.
#     kept         harness/config.sh, harness/Makefile and harness/loyly (the provided
#                  Gondolin runtime), .pi/harness, docs (but docs/plan), .pi/spec/README.md,
#                  .pi/spec/decisions.md, AGENTS.md, README.md
# The gate then runs from this checkout against the candidate in the room
# (harness/v2/gate.sh), because the room holds no instrument: CANDIDATE_ROOM=ROOM makes
# APP_DIR point at ROOM's harness-impl/NN-context. Only this repository has .pi/plan, so the
# oracle's fixture repositories, which copy this file, are never switched by it. Every
# assignment below is indented, so modules.sh never reads one as a configuration variable.
# The lists come from HEAD (git ls-tree), because HEAD is what fence.sh clones.
if [[ -n "${SLICE:-}" ]]; then
  _cfg_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  _cfg_link="$(cd "$_cfg_root" && ls -d "$PLAN_DIR/$SLICE"-* 2>/dev/null | head -1)"
  if [[ -n "$_cfg_link" && -L "$_cfg_root/$_cfg_link" ]]; then
    _cfg_ctx="$(basename "$(readlink "$_cfg_root/$_cfg_link")")"
    APP_DIR="harness-impl/$(basename "$_cfg_link")"
    APP_PROBE="harness/v2/probe-$SLICE.sh"
    _cfg_fence="docs/plan impl raw archive/legacy-harness tools/spec/v2/stamp.clj"
    _cfg_fence+=" .pi/architecture.md .pi/affordances.schema.json"
    _cfg_fence+=" .pi/spec/event-storm.md .pi/spec/event-storm.edn .pi/spec/legacy-trace.md"
    _cfg_producers=" "
    for _cfg_k in $(git -C "$_cfg_root" grep -h -o -E ':[a-z][a-z0-9-]*/[a-z][a-z0-9-]*' HEAD \
                      -- ".pi/spec/$_cfg_ctx/event-model/" 2>/dev/null | sort -u); do
      _cfg_ns="${_cfg_k#:}"; _cfg_ns="${_cfg_ns%%/*}"
      [[ "$_cfg_ns" != "$_cfg_ctx" && "$_cfg_producers" != *" $_cfg_ns "* ]] || continue
      git -C "$_cfg_root" show "HEAD:.pi/spec/$_cfg_ns/event-model/handoffs.edn" 2>/dev/null \
        | grep -qE "(^|[{[:space:]])$_cfg_k([[:space:]]|\$)" && _cfg_producers+="$_cfg_ns "
    done
    for _cfg_p in $(git -C "$_cfg_root" ls-tree --name-only HEAD \
                      .pi/model/ tools/spec/ harness/ .pi/spec/ .pi/plan/ harness-impl/ 2>/dev/null); do
      case "$_cfg_p" in
        .pi/model/GLOSSARY.md|tools/spec/v2|harness/config.sh|harness/Makefile|harness/loyly) ;;
        .pi/spec/"$_cfg_ctx"|"$_cfg_link"|"$APP_DIR") ;;
        .pi/spec/*.md|.pi/spec/*.edn) ;;
        *)
          _cfg_ns="${_cfg_p#.pi/spec/}"
          if [[ "$_cfg_p" == .pi/spec/* && "$_cfg_producers" == *" $_cfg_ns "* ]]; then
            for _cfg_f in $(git -C "$_cfg_root" ls-tree -r --name-only HEAD "$_cfg_p/"); do
              [[ "$_cfg_f" == "$_cfg_p/event-model/handoffs.edn" ]] || _cfg_fence+=" $_cfg_f"
            done
          else
            _cfg_fence+=" $_cfg_p"
          fi ;;
      esac
    done
    FENCED_PATHS="$_cfg_fence"
    if [[ -n "${CANDIDATE_ROOM:-}" ]]; then
      APP_DIR="$(python3 -c 'import os,sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))' \
                 "$(cd "$CANDIDATE_ROOM" && pwd -P)/$APP_DIR" "$(cd "$_cfg_root" && pwd -P)")"
    fi
  fi
fi
