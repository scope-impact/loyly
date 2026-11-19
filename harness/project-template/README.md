# A run of the cycle runner, for any project

`make` targets that drive the accepted ORC cycle runner (`harness-impl/06-cycle-runner/`)
on a project repository: fence, regenerate with pi in Gondolin, gate, black-box grade in
Gondolin, and score. Needs `bb`, `git`, a Löyly checkout, the builder and grader images,
and a live OpenAI-compatible model endpoint for ORC's render and grade nodes.

1. Copy this directory, e.g. into your repository as `run/`.
2. Edit `project.env`: set the project paths, sealed grader rubric and approved digest,
   model endpoint and model names. The project repository needs, at the commit,
   `harness/config.sh`, `harness/check.sh`, `harness/verify.sh` with its probe, and a plan
   of stamped Gherkin. The reference is `archive/legacy-harness/` in the Löyly checkout.
3. Fill in the placeholders of `builder-instruction.md`.
4. Run:

```bash
make start                    # runs the full ORC cycle (minutes to hours)
make progress                 # status: running, then passed / failed / halted
make kill                     # abandon a run, even mid-call
```

`make help` lists the rest (`stop`, `NO_WAIT=1`, `FULL=1`, `RUN_ID=`). The runner lives in a
detached daemon while a run is active, so closing the terminal does not stop it. Run state:
`$STATE_ROOT/<run-id>/` (`driver/driver.log`, `logs/rpc.jsonl`, `runs/`).

The grader digest is `sha256:` followed by SHA-256 of the UTF-8 bytes
`cycle-runner/grade.v1`, a newline, then the exact rubric file. The launcher
checks that the supplied digest matches before it
starts a run. Approval and calibration of the rubric remain operator responsibilities.

Advanced: `make send METHOD=… PARAMS='…'` is raw JSON-RPC to the runner; the methods are in
`.pi/spec/cycle-runner/seam.md` of the Löyly checkout.
