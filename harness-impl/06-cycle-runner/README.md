# Cycle runner — slice 06

Clojure CLI application using the pinned ORC and Grain git dependencies in `deps.edn`.

```sh
./test.sh       # builds the AOT classes and runs every test
./start.sh      # JSON-RPC 2.0, one request/response per line
```

Both entrypoints select their own working directory. `test.sh` prepares the local JVM classpath for fast AOT startup; without it, `start.sh` uses Clojure CLI directly. Configuration and context launch entrypoints are handed in through the `LOYLY_*` variables specified by the seam. Diagnostics use stderr.

The event model is read directly from `../../.pi/spec/cycle-runner/event-model/`. Grain's boot guard reconciles its commands, events, projections, queries and processor before the store starts. Every command runs in its named ORC workflow on the runner's SQLite store and tenant. ORC uses its legacy routing mode so terminal ticks and traces occur once; the synchronous boundary waits for the trace to become durable. Queries project Grain read models and start no workflow.

Repository, subprocess, fence, candidate-copy, gate and verdict-file adapters perform the effects. Context replies are recorded unchanged; handoffs are read from producer stores and transported to consumers. No five-context domain rule or grading implementation is included in the product.

The suite names all 57 stamped items. Its fixture programs expose the contracted stdio seams, backed by a separate Clojure peer worker that appends actual Grain handoffs with their contracted schemas. Private file mailboxes transport fixture bytes between those test-only components; the runner sees only stdio and the producer Grain stores. The suite's instrument, repository, builders, candidates and gates are fixtures, not product code. SQLite peer access is isolated from the test driver's JVM to keep startup resource use bounded.

For the gate identity sequence and a direct store audit after building:

```sh
bb test/manual-identity.bb
clojure -M:test test/audit_manual.clj
```

The manual check uses a new repository and store, changes `LOYLY_CONTEXTS` between starts, and verifies the invalid cut, five exited peers, and Specification exiting at stamp. It checks that neither the self-check nor builder ran. The audit reads that store directly and checks exact model fields, successful enclosing workflows, one terminal event and trace per tick, and Grain checkpoints.
