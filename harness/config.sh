# harness/config.sh: everything this repository knows about itself, read by fence.sh,
# verify.sh, probe.sh, check.sh, and modules.sh. Here the product being regenerated is the
# Pi/ORC harness itself, and its spec is .pi/model/plan/NN-slug/spec.feature.

# Where the regenerated harness is built inside the clean room. It must provide
# APP_DIR/test.sh (the whole suite) and APP_DIR/start.sh PORT (serves HTTP on PORT).
APP_DIR=harness-impl
PORT=8902

# The spec root. harness/hjson.clj reads this line, so the tools and modules.sh agree.
PLAN_DIR=.pi/model/plan

# What the identity probe expects the served surface to carry.
APP_MARKER=pi-harness
APP_PROBE=harness/probe.sh

# FENCED_PATHS: what the clean room must not contain, space separated, relative to the
# repository root. Each entry is removed from the working tree and the history, and fence.sh
# proves it is unreachable in the object graph.
#   .pi/harness            the current harness implementation: the answer being regenerated
#   .pi/architecture.md    a description of that implementation's internals
#   reports                recorded measurements and repair notes
#   archive/legacy-harness/cycles  recorded legacy measurements
FENCED_PATHS=".pi/harness .pi/architecture.md reports archive/legacy-harness/cycles"

# SIBLING_REPOS is not declared: no other repository carries this harness, so modules.sh
# names no sibling and compares nothing, and says so.
