# Start and drive one regeneration run of the Löyly cycle runner for a project.
# Copy this directory, edit project.env (the only file to change), then `make help`.
# The work is done by $(LOYLY_HOME)/harness/loyly-run (Babashka); see its --help.

SHELL := /bin/bash
.DEFAULT_GOAL := help

# The directory of the Makefile make was started with (a two-line Makefile that includes this
# one keeps its own project.env), and the config, whose relative paths resolve against its dir.
HERE := $(dir $(abspath $(firstword $(MAKEFILE_LIST))))
CONFIG ?= $(HERE)project.env
include $(CONFIG)
CONFIG_DIR := $(dir $(abspath $(CONFIG)))
path = $(if $(strip $(1)),$(if $(filter /%,$(1)),$(1),$(abspath $(CONFIG_DIR)$(strip $(1)))))

HOME_DIR := $(call path,$(LOYLY_HOME))
RUN := $(HOME_DIR)/harness/loyly-run
STATE := $(or $(call path,$(STATE_ROOT)),$(HOME_DIR)/.pi/harness/.state/$(PROJECT))
export LOYLY_RUN_STATE_ROOT := $(STATE)

START_ARGS = --commit '$(COMMIT)' \
  $(if $(strip $(PROJECT_REPO)),--project-repo '$(call path,$(PROJECT_REPO))') \
  $(if $(strip $(INSTRUCTION)),--instruction '$(call path,$(INSTRUCTION))') \
  $(if $(strip $(HARNESS_CONFIG)),--harness-config '$(call path,$(HARNESS_CONFIG))') \
  $(if $(strip $(BUDGET_FILE)),--budget-file '$(call path,$(BUDGET_FILE))') \
  $(if $(strip $(GRADER_DIGEST)),--grader-digest '$(GRADER_DIGEST)') \
  $(if $(strip $(CUT_ID)),--cut-id '$(CUT_ID)') \
  $(if $(strip $(SELECTION_ID)),--selection-id '$(SELECTION_ID)') \
  $(foreach e,$(RUN_ENV),--env '$(e)') \
  $(if $(strip $(NO_WAIT)),--no-wait) $(START_FLAGS)

# The run the other targets act on: RUN_ID, else the last run started under STATE.
CURRENT = id='$(RUN_ID)'; if [ -z "$$id" ]; then f='$(STATE)/current'; \
  [ -f "$$f" ] || { echo "no run started under $(STATE)" >&2; exit 2; }; id=$$(basename "$$(cat "$$f")"); fi

# run-progress, shortened to the fields a person reads (FULL=1 prints all of it).
SUMMARY = bb -e '(let [in (slurp *in*) run (get-in (cheshire.core/parse-string in) ["result" "run"])] \
  (if run (println (cheshire.core/generate-string (merge (select-keys run ["run-id" "status" "attempts" "candidate-digest" "verdicts-digest" "halt"]) \
  (when-let [f (get-in run ["cycle" "failure"])] {"failure" f})) {:pretty true})) (print in)))'

.PHONY: help start progress verdicts kill stop send check-config
help:
	@echo 'make start                    start a run ($(PROJECT)-<timestamp>); waits until it awaits'
	@echo '                              verdicts or concludes (NO_WAIT=1: returns at once)'
	@echo 'make progress                 the run'"'"'s status (FULL=1: the whole run-progress)'
	@echo 'make verdicts VERDICTS=file   hand in the grading verdicts: the run passes or fails'
	@echo 'make kill                     kill the runner, even mid-call (the run is lost)'
	@echo 'make stop                     stop the runner while idle; it stops by itself once the run concludes'
	@echo
	@echo 'Advanced:'
	@echo "make send METHOD=m PARAMS='{..}'   raw JSON-RPC to the runner; the methods are in .pi/spec/cycle-runner/seam.md"
	@echo
	@echo 'Config: $(CONFIG)'
	@echo 'Runs:   $(STATE)/<run-id>   (RUN_ID=id acts on a run other than the last one started)'

check-config:
	@[ -x '$(RUN)' ] || { echo 'LOYLY_HOME ($(LOYLY_HOME)) has no harness/loyly-run: set it in $(CONFIG)' >&2; exit 2; }
	@[ -n '$(strip $(PROJECT))' ] || { echo 'PROJECT is empty: set it in $(CONFIG)' >&2; exit 2; }

start: check-config
	@id='$(RUN_ID)'; id=$${id:-$(PROJECT)-$$(date +%Y%m%d-%H%M%S)}; \
	  '$(RUN)' start --run-id "$$id" $(START_ARGS)

progress: check-config
	@set -o pipefail; $(CURRENT); '$(RUN)' send cycle-runner/run-progress "{\"run-id\":\"$$id\"}" --run-id "$$id" $(if $(FULL),,| $(SUMMARY))

verdicts: check-config
	@[ -n '$(VERDICTS)' ] || { echo 'usage: make verdicts VERDICTS=verdicts.json' >&2; exit 2; }
	@[ -f '$(abspath $(VERDICTS))' ] || { echo 'no such file: $(abspath $(VERDICTS))' >&2; exit 2; }
	@$(CURRENT); d='$(GRADER_DIGEST)'; d=$${d:-$$id-grading-subagent}; \
	  '$(RUN)' send cycle-runner/hand-in-verdicts \
	  "{\"run-id\":\"$$id\",\"verdicts-path\":\"$(abspath $(VERDICTS))\",\"grader-digest\":\"$$d\"}" --run-id "$$id"

kill: check-config
	@$(CURRENT); '$(RUN)' stop --force --run-id "$$id"

stop: check-config
	@$(CURRENT); '$(RUN)' stop --run-id "$$id"

send: check-config
	@[ -n '$(METHOD)' ] || { echo "usage: make send METHOD=cycle-runner/run-progress PARAMS='{\"run-id\":\"..\"}'" >&2; exit 2; }
	@$(CURRENT); '$(RUN)' send '$(METHOD)' $(if $(PARAMS),'$(PARAMS)') --run-id "$$id"
