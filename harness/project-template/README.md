# A run of the cycle runner, for any project

`make` targets that start and drive one regeneration run of the Löyly cycle runner
(`harness-impl/06-cycle-runner/`) on a project repository: fence, regenerate, gate, then you
grade the gated candidate and hand in the verdicts. Needs `bb` (Babashka), `git`, and a Löyly
checkout; the builder is pi on Gondolin (`harness/Makefile`, `make bootstrap`).

1. Copy this directory, e.g. into your repository as `run/`.
2. Edit `project.env`, the only file to change: `LOYLY_HOME`, `PROJECT`, `PROJECT_REPO`, and the
   labels. The project repository needs, at the commit, `harness/config.sh`, `harness/check.sh`,
   `harness/verify.sh` with its probe, and a plan of stamped Gherkin (the reference set is
   `archive/legacy-harness/` in the Löyly checkout).
3. Fill in the placeholders of `builder-instruction.md`.
4. Run:

```bash
make start                    # blocks until the run awaits verdicts (minutes to hours)
make progress                 # status: awaiting-verdicts, then passed / failed / halted
make verdicts VERDICTS=verdicts.json   # the run passes or fails; the runner exits by itself
make kill                     # abandon a run, even mid-call
```

`make help` lists the rest (`stop`, `NO_WAIT=1`, `FULL=1`, `RUN_ID=`). A run cannot resume: the
runner lives in a detached daemon until the run concludes, so a closed terminal does not lose
it. Run state: `$STATE_ROOT/<run-id>/` (`driver/driver.log`, `logs/rpc.jsonl`, `runs/`).

Advanced: `make send METHOD=… PARAMS='…'` is raw JSON-RPC to the runner; the methods are in
`.pi/spec/cycle-runner/seam.md` of the Löyly checkout.
