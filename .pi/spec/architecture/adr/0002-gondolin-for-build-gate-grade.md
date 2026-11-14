# 0002 Gondolin for build, gate and grade

## Status

Proposed (2026-11-13).

## Context

The builder runs in a Gondolin VM (Linux, the `builder-clojure` image), while the gate runs on
the macOS host and the black-box graders of contexts 01 to 05 ran on the host too. Two failures
came from that split: runner cycle 10 failed `@item-06-060` in all three gate attempts, green in
the VM and red on the host; the batch's slice 03 attempt 1 failed because a CDS archive built in
the VM printed a JVM warning on stdout on the host. Grading on the host also shared the machine
with builders and other jobs (load average 53), and nothing but the instruction kept a host
grader away from files outside its room.

## Decision

Build, gate and grade run their shell and files in a Gondolin VM made from one image:

- Folders are mounted at their host paths, so a path names the same file inside and outside.
  Caches are mounted read-only where the tools look for them (`~/.m2` at `/root/.m2`,
  `~/.gitlibs` at `/root/.gitlibs`).
- The network is closed unless hosts are allowed. Gondolin's CLI leaves egress open when no host
  is allowed, so a host that cannot exist is always allowed (`egress-blocked.invalid`).
- A Claude grader or operator reaches the VM through the gondolin Claude mod: Bash runs in the VM
  (`gondolin exec --sock`), file tools are refused outside the mounts, and a subagent type
  `gondolin:vm` keeps the operator on the host. Inside the VM anything goes (product owner,
  2026-11-13): a call that is exactly the mod's wrap for the live VM needs no approval.
- In `cycle.v1` (ADR 0001) the build and repair leaves run pi-durable sessions in the VM, and the
  grading researcher's `vm_bash` tool reaches it through `:tool-caller-fn`. The researcher's VM is
  one per item, from the image `LOYLY_GRADER_IMAGE` names (`../../decisions.md`, "Spec round 20";
  `../../cycle-runner/seam.md`, "The researcher's VM").

## Consequences

- One world for build, gate and grade: no host-only failures.
- The image needs the tools graders reach for (`jq`, `sqlite3`); they are missing today.
- `gondolin exec --sock` passes no stdin: requests travel inside the command.
- Halla Health's gate already runs this way.

## Evidence

- Cycle 10 gate outputs `gate-c10-slice06`, `-a2-`, `-a3-` (`answered-on-poll` 5, expected 4).
- Batch at `ecac349` (commit `3448def`): slice 03 green in the VM, gate attempt 1 red on the
  host; repaired in attempt 2.
- VM probe (2026-11-13): the host's home folders absent in the VM; `curl https://github.com` 403; a read outside
  the mount refused; exit codes passed through; slice 05's candidate answered JSON-RPC from
  sources in a fresh copy (76 KB without build output).
- Slice 05 graded in the VM by a `gondolin:vm` Claude grader: 4/4, the same verdicts as the
  host graders of cycle 05/02.
- Prototype `proto/orc-harness`: every build, gate and grading call ran in one long-lived VM with
  egress blocked; killing the `gondolin` CLI alone orphans its qemu, so a stop kills its children
  first.
