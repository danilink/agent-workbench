# Plan

Two weeks, shaped as a small "cycle": fixed time, variable scope. Each milestone ends with something demoable.

## M1 — Core loop (days 1–4)

- [x] Play 3 / Scala 3 backend: tasks, runs, events in Postgres
- [x] Worktree + branch per run, diff against the pinned base commit
- [x] `ClaudeCliRunner` (headless stream-json) and `FakeRunner` behind `AgentRunner`
- [x] Bounded parallelism, cancellation (SIGINT then kill), orphan recovery on boot
- [x] SSE stream; React UI with live timeline, diff, accept/discard (XState)
- [x] `CLAUDE.md`, skills, hook, reviewer subagent, ADRs
- [x] First green `sbt compile test` on a real machine
- [x] Dogfood: use the workbench to build M2 (first real run wrote most of the checks feature)

## Learned from the first real runs (2026-11-11)

The first runs with the real CLI exposed bugs the fake runner never could. These are fixed:

- [x] Output was only read after `claude` exited, so the end of every session was lost, including the result event (#2)
- [x] Runs could live forever and outlive the server; cancel did not kill the agent's children (#2, ADR 0004)
- [x] Accept removed the worktree with the agent's uncommitted work still in it (#3)
- [x] `Proc.run` ignored its timeout when a process kept its output open (#3)

Still open, in priority order:

- [ ] **Say why a run failed.** Keep the result subtype (e.g. budget exhausted) and show it. Today a run that hits its budget cap shows `Failed` with no reason.
- [ ] **Don't make a capped run look worthless.** The first run stopped at its budget cap with the feature ~90% written. Give it a distinct label, such as "stopped: budget", and make follow-up (`--resume`, below) the obvious next action.
- [ ] **Prepare worktrees per repo** (e.g. `npm ci`). They start without `node_modules`, so the typecheck hook only reported `tsc: command not found` and the agent never checked its TypeScript.
- [ ] **Budget per task.** $2 was not enough for a mid-size full-stack task on Opus: 37 turns, about $2.11. Goes with the M3 cost guardrails.
- [ ] **Queued runs hold a dispatcher thread** while they wait, so enough of them can starve running runs' readers. Needs a real queue, with readers on dedicated threads (ADR 0004).
- [ ] **Checks cannot be cancelled from the UI**; only their timeout bounds them (ADR 0005).
- [ ] **Discard has no undo.** It deletes the branch, the only copy of the work. Consider keeping discarded branches for a while.

## M2 — Comparing attempts (days 5–8)

- [x] Run checks per attempt in its worktree (configurable command, e.g. `sbt test`) and show pass/fail
- [ ] Side-by-side comparison: files touched, lines changed, cost, turns, checks
- [ ] "Judge" step: one more session reads all diffs and recommends one, with reasons
- [ ] Follow-up prompt on a run (`--resume <session_id>`) instead of starting over
- [ ] Storybook for `Timeline`, `DiffView`, `ReviewBar`

## M3 — Production shape (days 9–12)

- [ ] Redis pub/sub behind `EventBus` so several Play nodes can serve SSE (ADR 0003)
- [ ] Integration tests with Testcontainers Postgres
- [x] CI (GitHub Actions): format, compile, test, typecheck, build, Playwright smoke test on the fake runner
- [x] React error boundary, so a render bug shows an error instead of a blank page
- [ ] Dockerfile, `docker compose` for the full stack
- [ ] Cost guardrails: per-task budget, daily cap

## M4 — Show it (days 13–14)

- [ ] Loom walkthrough: one task, three attempts, judge, accept
- [ ] Write-up: what I learned about structuring a repo for agents
