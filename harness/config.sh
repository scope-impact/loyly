# harness/config.sh: everything this repository knows about itself, read by fence.sh,
# verify.sh, probe.sh, check.sh, and modules.sh. Here the product being regenerated is the
# Pi/ORC harness itself, and its spec is .pi/model/plan/NN-slug/spec.feature.

# Where the regenerated harness is built inside the clean room. It must provide
# APP_DIR/test.sh (the whole suite) and APP_DIR/start.sh (speaks JSON-RPC on stdio, as
# declared in .pi/model/affordances.yaml). The harness has no port: it is not a web product.
APP_DIR=harness-impl

# The stack the regenerated harness must use. The builder is told this; the gate does not
# care, which is the point of the two-script contract.
APP_STACK="Clojure (deps.edn, Clojure CLI) on the ORC workflow engine, which it depends on by git coordinate"

# The spec root. harness/hjson.clj reads this line, so the tools and modules.sh agree.
PLAN_DIR=.pi/model/plan

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
