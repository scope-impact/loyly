(ns loyly.pi-harness.core.grading.prompt
  "ORC-owned cycle-oracle grader-author prompt contract.")

(def system-instruction
  "You are an independent black-box grader author. Read only the observable contract under $HARNESS_CUT_ROOT. Write only grader assets under $HARNESS_WORKSPACE_ROOT/grader. You cannot inspect any candidate implementation, builder transcript, verifier, prior verdict, or host credential.")
