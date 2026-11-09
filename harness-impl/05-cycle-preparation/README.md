# Cycle preparation — slice 05

Clojure JSON-RPC 2.0 over stdio, backed by the pinned Grain SQLite event store and ORC workflow engine.

```sh
./test.sh   # resolve pinned dependencies, compile/package, run the entire suite
./start.sh  # launch the built product; no dependency resolution or compilation
```

Both entrypoints resolve their own directory. `LOYLY_GRAIN_STORE` selects the SQLite file; without it the store is in memory. `LOYLY_PROJECT_COMMITS` selects the repository-facts JSON file and is read on each command. Diagnostics go to stderr.

## Design

- The event model is loaded and merged from `../../.pi/spec/cycle-preparation/event-model/*.edn` on every boot. No model fragment is copied into the implementation.
- Grain registers the command, query, event and read model with the model's schemas, then its strict boot guard checks reconciliation before opening the store.
- Each valid command executes a code step inside `cycle-preparation/cut-to-fence`, on the product's store and tenant. A sequence root avoids competing leaf-root completion paths in ORC's default routing. Queries run no workflow.
- Every ORC todo processor is started with its registered `:processor-name`. Responses wait for the durable execution trace. Tests inspect the SQLite store directly for event schemas, successful workflow containment, one terminal and trace per tick, and Grain checkpoints.
- The read model is projected by Grain. Its L2 cache implements Grain's KV protocol in memory; caches are disposable and are reconstructed from the event store after restart.

## Tests

The four accepted scenarios have individual seam tests named `item-05-001` through `item-05-004`, with their stamped identities in test descriptions. Additional tests cover JSON errors, schema refusals, refusal precedence, repository-fact refresh/disappearance, newest-first ordering, persistence, and boot-guard rejection of runtime drift.

## Portable toolchain

`env.sh` prefers the native JDK (`JAVA_HOME`, then `java` on PATH), Git and Clojure CLI. The bundled `.tools/clojure` is a portable Bash script. Only on Linux/aarch64, when native tools are absent, does the builder's Alpine `.runtime/` supply Java 21 and Git. Its Bash and shell never enter PATH. No Linux loader, Git helpers or trust store are selected on macOS. A shell regression simulates the native gate environment and checks these boundaries.

Caches stay inside this candidate. `test.sh` forces fresh Clojure classpath resolution so copying the candidate cannot retain builder-room absolute paths. `deps.edn` resolves ORC and each Grain project by the required git coordinates; the accompanying dependency caches permit offline builds. `product.jar` is generated from those dependencies by `cycle-preparation.build`; `start.sh` performs no dependency resolution or compilation.
