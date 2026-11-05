# Recorded cycles

One directory per scored regeneration cycle, `NN/`, holding the checklist that cycle was
scored against (`checklist.json`) and its filed verdicts (`scores.json`), written by
`harness/gen_checklist.clj` and `harness/score.clj`. `harness/scoreboard.clj NN` compares a
cycle with the one before it. These are measurements: `harness/fence.sh` removes this
directory from every clean room.
