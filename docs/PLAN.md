# Plan

Two weeks, shaped as a small "cycle": fixed time, variable scope. Each milestone ends with something demoable.

## M1 — Core loop (days 1–4)

- [x] Play 3 / Scala 3 backend: tasks, runs, events in Postgres
- [x] Worktree + branch per run, diff against the pinned base commit
- [x] `ClaudeCliRunner` (headless stream-json) and `FakeRunner` behind `AgentRunner`
- [x] Bounded parallelism, cancellation (SIGINT then kill), orphan recovery on boot
- [x] SSE stream; React UI with live timeline, diff, accept/discard (XState)
- [x] `CLAUDE.md`, skills, hook, reviewer subagent, ADRs
- [ ] First green `sbt compile test` on a real machine
- [ ] Dogfood: use the workbench to build M2

## M2 — Comparing attempts (days 5–8)

- [ ] Run checks per attempt in its worktree (configurable command, e.g. `sbt test`) and show pass/fail
- [ ] Side-by-side comparison: files touched, lines changed, cost, turns, checks
- [ ] "Judge" step: one more session reads all diffs and recommends one, with reasons
- [ ] Follow-up prompt on a run (`--resume <session_id>`) instead of starting over
- [ ] Storybook for `Timeline`, `DiffView`, `ReviewBar`

## M3 — Production shape (days 9–12)

- [ ] Redis pub/sub behind `EventBus` so several Play nodes can serve SSE (ADR 0003)
- [ ] Integration tests with Testcontainers Postgres
- [ ] Dockerfile, `docker compose` for the full stack, CircleCI pipeline
- [ ] Cost guardrails: per-task budget, daily cap

## M4 — Show it (days 13–14)

- [ ] Loom walkthrough: one task, three attempts, judge, accept
- [ ] Write-up: what I learned about structuring a repo for agents
