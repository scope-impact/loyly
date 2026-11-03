# Pi harness runtime bootstrap only. Run from the repository root with `make -C harness bootstrap`.
PI_HARNESS := ../.pi/harness
CONFIG := $(PI_HARNESS)/config
BUILD_CONFIG := $(CONFIG)/build
ASSETS := $(PI_HARNESS)/.state/assets
GONDOLIN := $(PI_HARNESS)/node_modules/.bin/gondolin

.PHONY: bootstrap
bootstrap:
	cd $(PI_HARNESS) && npm ci
	docker build -f loyly/Dockerfile.typescript -t loyly-builder:oci loyly
	docker build -f loyly/Dockerfile.rust -t loyly-builder-rust:oci loyly
	docker build -f loyly/Dockerfile.java -t loyly-builder-java:oci loyly
	docker build -f loyly/Dockerfile.rails -t loyly-builder-rails:oci loyly
	docker build -f $(BUILD_CONFIG)/Dockerfile.cut -t loyly-cut:oci $(BUILD_CONFIG)
	docker build -f $(BUILD_CONFIG)/Dockerfile.instrument -t loyly-instrument:oci $(BUILD_CONFIG)
	docker build -f loyly/Dockerfile -t loyly-grader:oci loyly
	$(GONDOLIN) build --config $(CONFIG)/gondolin.json --output $(ASSETS)/bootstrap
	$(GONDOLIN) build --config $(CONFIG)/gondolin.builder.json --output $(ASSETS)/builder
	$(GONDOLIN) build --config $(BUILD_CONFIG)/builder-rust.json --output $(ASSETS)/builder-rust
	$(GONDOLIN) build --config $(BUILD_CONFIG)/builder-java.json --output $(ASSETS)/builder-java
	$(GONDOLIN) build --config $(BUILD_CONFIG)/builder-rails.json --output $(ASSETS)/builder-rails
	$(GONDOLIN) build --config $(CONFIG)/gondolin.cut.json --output $(ASSETS)/cut
	$(GONDOLIN) build --config $(CONFIG)/gondolin.instrument.json --output $(ASSETS)/instrument
	$(GONDOLIN) build --config loyly/gondolin.json --output $(ASSETS)/grader
	@set -eu; for asset in bootstrap builder builder-rust builder-java builder-rails cut instrument grader; do \
		$(GONDOLIN) build --verify $(ASSETS)/$$asset; \
	done
