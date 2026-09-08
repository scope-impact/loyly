# This repository's harness configuration: everything the shared module files must not
# know by heart.
#
# WHY THIS FILE EXISTS. harness/fence.sh, harness/verify.sh, harness/modules.sh and the
# five Babashka tools are one text, byte for byte, in every repository that carries this
# harness. Three hand-kept copies drift into three instruments, and a drifted instrument
# reports a difference that was never in the thing measured. So the files are identical
# and the differences live here, where they are declared, documented and few.
# harness/modules.sh proves both halves of that sentence.
#
# FORMAT. Every line is KEY=value, one line each, no arrays, no command substitution and
# no reference to another variable. Shell reads it by sourcing it; a Babashka tool can
# read it with a regular expression and no dependency. Keep it that way.
#
# ADDING A VARIABLE. Add it in all three repositories on the same day, with its own
# comment saying what reads it and what a wrong value does. A variable declared here and
# absent in a sibling is a failure of harness/modules.sh, deliberately: a configuration
# surface that is complete in one repository only is not a configuration surface.

# APP_DIR: where the application under test lives, relative to the repository root.
# Read by harness/verify.sh, which requires APP_DIR/test.sh and APP_DIR/start.sh.
# Wrong value: the gate refuses at once with "build first", because the contract's two
# entry points are not where it looked.
APP_DIR=impl

# PORT: the port the gate boots the application on and probes over HTTP.
# Read by harness/verify.sh, and passed to APP_DIR/start.sh as its only argument.
# Wrong value: if something else holds the port the gate refuses rather than measure the
# wrong process; if the application ignores the argument, the gate waits ten seconds and
# fails with "a boot log is not a served page".
PORT=8901

# PLAN_DIR: where the product spec lives, relative to the repository root.
# Read by nobody at runtime today. The Babashka tools resolve it themselves
# (harness/hjson.clj, plan-dir), so this line is a declaration that has to agree with
# them, and harness/modules.sh checks that it does.
# Wrong value: harness/modules.sh fails, which is the point. Moving the plan tree means
# changing both, and the check makes sure nobody changes only one.
PLAN_DIR=docs/plan

# APP_MARKER: a string the served page must carry, the cheap first identity check.
# Read by harness/verify.sh. Empty is allowed and means the check is skipped, which the
# gate says out loud; the real identity proof is APP_PROBE below.
# Wrong value: the gate reports "answers but does not serve the app" over an application
# that is running perfectly well.
APP_MARKER=sauna

# APP_PROBE: this repository's identity probe, relative to the repository root.
# Read by harness/verify.sh, which runs it as "APP_PROBE URL" after the application
# answers, and fails with whatever the probe printed. It is a per-repository file on
# purpose: what only the real product can produce is domain behaviour, and domain
# behaviour is code, not a value.
# Wrong or missing value: the gate refuses before it runs the suite, because a gate with
# no identity proof is the failure mode this whole file is downstream of.
APP_PROBE=harness/probe.sh

# FENCED_PATHS: what the clean room must not contain, space separated, relative to the
# repository root.
# Read by harness/fence.sh, which deletes each entry and then proves it is unreachable in
# the clone's object graph. An entry matching nothing is a hard failure, because the probe
# can only confirm an absence.
# A missing entry is the dangerous case and nothing can catch it, so each entry earns its
# place in writing:
#   impl            the answer. A regeneration that can read last week's implementation is
#                   copying, not rebuilding.
#   harness/cycles  every mark the answer has ever been given. An agent that can read last
#                   cycle's scores can aim at the checklist instead of at the spec, which
#                   is the same cheating by a slower route.
# The scoring tools (harness/score.clj, harness/scoreboard.clj) are NOT fenced here, and
# saunavuoro does fence them. That difference is open and known: see harness/README.md,
# "The three modules, and the check that they have not forked".
FENCED_PATHS="impl harness/cycles"

# SIBLING_REPOS: the other repositories that carry this harness, space separated, relative
# to this repository root.
# Read by harness/check.sh, which hands them to harness/modules.sh so the sameness check
# runs without anybody typing three paths. It is a per-repository value because each
# repository's siblings are the other two, and because a checkout somewhere else may have
# none of them.
# Wrong value: a path that is not on this machine is a loud SKIP and never a pass, so the
# damage is a comparison silently not made rather than a wrong verdict. A path to a
# directory that has a harness/ and is not one of ours would be compared and reported as a
# fork, which is noisy and harmless.
SIBLING_REPOS="../saunavuoro ../kalibra"
