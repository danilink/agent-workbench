# 0004 — Every run ends, and takes its processes with it

**Status:** accepted

## Context

A run is a `claude -p` process plus whatever it starts (sbt, npm, shells). Before this decision:

- The supervisor waited for the outcome with `Duration.Inf`. The CLI enforces `--max-turns` and `--max-budget-usd` itself, but a hung CLI spends neither, so a stuck run held its slot forever. Three stuck runs blocked every new run.
- Nothing stopped agent processes when Play stopped. A dev-mode reload, which happens on every backend code change, left them running unsupervised: still editing worktrees and spending quota.
- Cancel sent SIGINT to `claude` only. Its children survived, and a child holding the stdout pipe kept the reader blocked even after `claude` was gone.
- A queued run could only be cancelled once it got a slot.

## Decision

- **Wall-clock deadline per run**: `workbench.max-run-duration` (default 15 minutes; it was 30 when this ADR was written). When it passes, the run is cancelled like a user cancel and ends `Failed: timed out after …`. The wait for its outcome is bounded too (`cancel-grace` + 5s), so the slot is released even if the process cannot be reaped.
- **Cancel = SIGINT, then kill the whole tree**: after `workbench.cancel-grace` (default 10s), `claude` and every descendant are force-killed. Descendants are snapshotted when the cancel starts, because once `claude` exits its children are reparented and unreachable from its handle.
- **Stop hook**: on application stop (including dev-mode reloads) the supervisor stops admitting runs, cancels all live ones and waits for them, within Pekko's `service-stop` phase, raised to 30s. Interrupted runs end `Failed: interrupted by server shutdown`. Runs still queued stay `Queued`; the next boot fails them, as before.
- **Queued runs poll for a slot** (`tryAcquire` every second) instead of blocking, so cancel and shutdown reach them immediately.

## Consequences

- The longest a run can live is `max-run-duration + cancel-grace + 5s`. Long legitimate tasks need a larger `max-run-duration`.
- Stopping Play takes up to `cancel-grace` plus a few seconds when runs are live.
- Processes that fully detach from `claude` (daemonise into a new session) are still out of reach. We don't start any ourselves; an agent could.
- Still open: queued runs hold a dispatcher thread while they wait. Enough queued runs can starve the readers of running ones. A real queue, with readers on dedicated threads, is the follow-up.
- `WORKBENCH_CLAUDE_BINARY` lets the real runner be pointed at a script, which is how hanging, SIGINT-ignoring and child-spawning agents were tested end to end without spending tokens.
