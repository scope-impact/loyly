# Cycle 27 stopped before scoring

The approved self-host attempt 27 halted at stamp before fencing or launching a builder. It has no candidate, gate, grader, or score. Its evidence is in [the self-host attempt](../../../selfhost/06/27/attempt-1.md) and [preflight](../../../preflight/06/27.md).

This marker reserves the attempted cycle's number. The next approved legacy bootstrap is cycle 28; `harness/v2/score.sh` allocates a number from existing cycle directories, including unscored ones. Cycle 28 compares its shared items with the last scored cycle, 26.
