# 0002 — Drive Claude Code through the CLI, behind `AgentRunner`

**Status:** accepted

## Context

Claude Code can be driven from the CLI (`claude -p`) or from the Agent SDK (TypeScript / Python). The backend is Scala.

## Decision

Start with the CLI: `claude -p <prompt> --output-format stream-json --verbose --permission-mode acceptEdits --allowedTools … --max-turns … --max-budget-usd …`, one process per run, stdin closed immediately. A pure `StreamJsonParser` turns each stdout line into domain `AgentEvent`s; unknown shapes are kept as `Unknown`, never fatal.

Everything else depends on the `AgentRunner` trait, not on the CLI. `FakeRunner` implements it for demos and tests.

Cancellation sends SIGINT first (Claude Code ends the turn cleanly), then force-kills after 10 s. SIGTERM would drop the turn.

## Consequences

- No extra runtime (Node/Python sidecar) for M1.
- We depend on the stream-json format; the parser is the single place that knows it and is unit-tested against real shapes.
- If we need tool-approval callbacks or structured outputs, an Agent SDK sidecar can implement `AgentRunner` without touching the rest.

## Note on CSRF

The SPA calls a JSON API through the Vite proxy. Play's CSRF filter stays enabled; the client sends `X-Requested-With`, which Play accepts as a bypass header. If auth with cookies is ever added, revisit this.
