# Pi harness runtime bootstrap only. Select conventional project inputs with
# `make -C harness bootstrap PROJECT=<project-id>` (defaults to loyly).
PI_HARNESS := ../.pi/harness
CONFIG := $(PI_HARNESS)/config
BUILD_CONFIG := $(CONFIG)/build
ASSETS := $(PI_HARNESS)/.state/assets
GONDOLIN := $(PI_HARNESS)/node_modules/.bin/gondolin
RUNTIME_IMAGES := $(PI_HARNESS)/.state/runtime-images
ARCH := linux/arm64
PROJECT ?= loyly
PROJECT_ROOT := $(CURDIR)/$(PROJECT)

.PHONY: bootstrap check-project runtime-images verify-platforms
bootstrap: check-project runtime-images
	cd $(PI_HARNESS) && npm ci
	docker build -f $(PROJECT_ROOT)/Dockerfile.typescript -t pi-harness/builder-typescript:oci $(PROJECT_ROOT)
	docker build -f $(PROJECT_ROOT)/Dockerfile.rust -t pi-harness/builder-rust:oci $(PROJECT_ROOT)
	docker build -f $(PROJECT_ROOT)/Dockerfile.java -t pi-harness/builder-java:oci $(PROJECT_ROOT)
	docker build -f $(PROJECT_ROOT)/Dockerfile.rails -t pi-harness/builder-rails:oci $(PROJECT_ROOT)
	docker build -f $(BUILD_CONFIG)/Dockerfile.cut -t pi-harness/cut:oci $(BUILD_CONFIG)
	docker build -f $(BUILD_CONFIG)/Dockerfile.instrument -t pi-harness/instrument:oci $(BUILD_CONFIG)
	docker build -f $(PROJECT_ROOT)/Dockerfile -t pi-harness/grader:oci $(PROJECT_ROOT)
	$(GONDOLIN) build --config $(CONFIG)/gondolin.json --output $(ASSETS)/bootstrap
	$(GONDOLIN) build --config $(CONFIG)/gondolin.builder.json --output $(ASSETS)/builder
	$(GONDOLIN) build --config $(BUILD_CONFIG)/builder-rust.json --output $(ASSETS)/builder-rust
	$(GONDOLIN) build --config $(BUILD_CONFIG)/builder-java.json --output $(ASSETS)/builder-java
	$(GONDOLIN) build --config $(BUILD_CONFIG)/builder-rails.json --output $(ASSETS)/builder-rails
	$(GONDOLIN) build --config $(CONFIG)/gondolin.cut.json --output $(ASSETS)/cut
	$(GONDOLIN) build --config $(CONFIG)/gondolin.instrument.json --output $(ASSETS)/instrument
	$(GONDOLIN) build --config $(PROJECT_ROOT)/gondolin.json --output $(ASSETS)/grader
	@set -eu; for asset in bootstrap builder builder-rust builder-java builder-rails cut instrument grader; do \
		$(GONDOLIN) build --verify $(ASSETS)/$$asset; \
	done
	$(MAKE) verify-platforms

check-project:
	@case '$(PROJECT)' in ''|.|..|*[!A-Za-z0-9._-]*) echo "invalid PROJECT: $(PROJECT)" >&2; exit 2;; esac
	@set -eu; for file in Dockerfile Dockerfile.typescript Dockerfile.rust Dockerfile.java Dockerfile.rails \
		Dockerfile.rails-offline Dockerfile.rust-offline Dockerfile.java-offline \
		Dockerfile.typescript-postgres gondolin.json; do \
		test -f "$(PROJECT_ROOT)/$$file" || { echo "missing harness/$(PROJECT)/$$file" >&2; exit 2; }; \
	done

# The VM side must be proven before any cycle starts: every runtime archive loads and starts
# offline inside a deny-egress grader VM, and every platform probe passes there end to end.
verify-platforms:
	cd $(PI_HARNESS) && node scripts/verify-platforms.mjs --all

# Deny-egress grading rooms cannot pull, so every image a grading room starts is saved here as
# one archive per target cell and loaded with `docker load` inside the VM. Archive names are
# the ones the production prompt and grading adapters request.
runtime-images: check-project
	mkdir -p $(RUNTIME_IMAGES)
	rm -f $(RUNTIME_IMAGES)/*.tar
	docker pull -q --platform $(ARCH) node:22-alpine
	docker pull -q --platform $(ARCH) public.ecr.aws/lambda/nodejs:22
	docker pull -q --platform $(ARCH) amazon/dynamodb-local:2.6.1
	docker pull -q --platform $(ARCH) postgres:16-alpine
	docker build --platform $(ARCH) -f $(PROJECT_ROOT)/Dockerfile.rust-offline -t pi-harness/rust:1.91-web $(PROJECT_ROOT)
	docker build --platform $(ARCH) -f $(PROJECT_ROOT)/Dockerfile.java-offline -t pi-harness/java:21-web $(PROJECT_ROOT)
	docker build --platform $(ARCH) -f $(PROJECT_ROOT)/Dockerfile.rails-offline -t pi-harness/rails:8-offline $(PROJECT_ROOT)
	docker build --platform $(ARCH) -t pi-harness/sam-gateway:platform $(BUILD_CONFIG)/sam-gateway
	docker build --platform $(ARCH) -f $(PROJECT_ROOT)/Dockerfile.typescript-postgres -t pi-harness/node22-postgres:platform $(PROJECT_ROOT)
	docker save -o $(RUNTIME_IMAGES)/node-22-alpine.tar node:22-alpine
	docker save -o $(RUNTIME_IMAGES)/rust-1.91-web.tar pi-harness/rust:1.91-web
	docker save -o $(RUNTIME_IMAGES)/java-21-web.tar pi-harness/java:21-web
	docker save -o $(RUNTIME_IMAGES)/rails-8-offline.tar pi-harness/rails:8-offline
	docker save -o $(RUNTIME_IMAGES)/aws-serverless-typescript.tar \
		public.ecr.aws/lambda/nodejs:22 amazon/dynamodb-local:2.6.1 pi-harness/sam-gateway:platform
	docker save -o $(RUNTIME_IMAGES)/container-postgres-typescript.tar \
		pi-harness/node22-postgres:platform postgres:16-alpine
	cd $(RUNTIME_IMAGES) && shasum -a 256 *.tar
