# 0005 — A post-run check per attempt, run by the workbench, not the agent

**Status:** accepted

## Context

An agent reporting success only means it finished its turns. Comparing attempts by reading diffs does not tell you whether the code builds or the tests pass. The first dogfooding run (which wrote most of this feature) is the example: it called its own work "complete" and admitted it never compiled it.

## Decision

- **One global command**, `workbench.check.command` (argv), or `WORKBENCH_CHECK_COMMAND` (a command line split on whitespace). It is empty by default, which turns the feature off. Per-task commands would need a form field and per-repo knowledge; global is enough while there is one repo.
- **Run by the supervisor, after the agent**, in the run's worktree, only when the run `Succeeded`. The agent cannot influence the result; it is our command and our exit code.
- **Inside the run's parallelism slot**, on the run's own thread. `sbt test` is as heavy as the agent session that preceded it, so checks never outnumber `max-parallel-runs`, and the single-writer rule holds.
- **Stored on the run row** (`check_status`, `check_output`; evolution 2). The output is the last 16k characters: build noise is at the top, failures are at the bottom. The run row is already terminal while the check runs; the UI shows the check as its own badge.
- **Bounded and killable**: `workbench.check.timeout` (10 minutes) via `Proc.run`, which kills the whole process tree when the timeout passes. Stopping the server kills live checks too, and records them as interrupted.
- **No review while a check runs**: the check is using the worktree that Accept or Discard would remove. The server refuses (`check is still running`) and the UI hides the buttons.

## Consequences

- A slot stays busy for agent time plus check time, so throughput drops when checks are slow.
- A check cannot be cancelled from the UI. The run is already terminal, so Cancel does not apply; the timeout is the bound.
- A check left `Running` by a crash is failed on the next boot, like orphaned runs.
- Worktrees start without dependencies (`node_modules`), so a frontend check needs its own install step in the command for now.
