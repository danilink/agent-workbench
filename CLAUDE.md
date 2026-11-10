# Agent Workbench — notes for agents

Monorepo: `backend/` (Scala 3.3 LTS, Play 3.0, plain JDBC, Postgres) and `frontend/` (React 18, Chakra UI v2, React Query v5, XState v5, Vite). Read `README.md` for the big picture and `docs/adr/` before changing anything architectural.

## Commands

| What | Command (from repo root) |
| --- | --- |
| Start Postgres | `docker compose up -d` |
| Backend compile | `cd backend && sbt -client compile` |
| Backend tests | `cd backend && sbt -client test` |
| Backend format | `cd backend && sbt -client scalafmtAll` |
| Run backend (no tokens) | `cd backend && WORKBENCH_RUNNER=fake WORKBENCH_REPOS=$PWD/.. sbt run` |
| Frontend typecheck | `cd frontend && npm run typecheck` |
| Frontend build | `cd frontend && npm run build` |
| E2E smoke test (fake runner; reuses running servers or starts them) | `cd frontend && npm run e2e` |

Prefer `sbt -client` (reuses a running sbt server) over plain `sbt`; cold starts are slow.

## Invariants — do not break

- **Every repo method blocks.** Call `WorkbenchRepo` only from `DatabaseExecutionContext` or from a run's own thread, never from Play's default dispatcher.
- **A run has exactly one writer**: the `RunSupervisor` thread driving it. Don't update a run row from controllers; go through `RunSupervisor`.
- **`AgentRunner.emit` is single-threaded and ordered.** Event `seq` relies on it.
- **The stream-json parser is total.** Unknown CLI output becomes `AgentEvent.Unknown`; it must never throw.
- **Only allow-listed repos** (`WorkbenchConfig.allowedRepo`) may reach `GitWorktrees` or an agent.
- **REST is the source of truth, SSE is a freshness hint.** The UI must render correctly if it misses stream messages.
- Domain enums are persisted by `toString`. Renaming a case is a migration.

## Where things go

- New HTTP endpoint → use the `add-endpoint` skill.
- Schema change → use the `add-evolution` skill. Never edit an evolution that has shipped.
- New agent backend (e.g. Agent SDK sidecar) → implement `AgentRunner`, wire it in `app/Module.scala`.
- Frontend server state → React Query only. Multi-step UI flows with irreversible actions → an XState machine in `src/machines/`.
- JSON shape changes → update `backend/app/workbench/api/JsonFormats.scala` **and** `frontend/src/api/types.ts` together.

## Style

- Scala 3 syntax (significant indentation, `enum`, opaque types, `given`). Run `scalafmtAll` before finishing.
- Errors that a caller can act on are `Either[String, A]`; exceptions are for bugs.
- TypeScript is `strict` with `noUncheckedIndexedAccess`; no `any`.
- Keep comments to the *why*.

## Before you say you're done

1. Backend touched → `sbt -client compile test` passes.
2. Frontend touched → `npm run typecheck` passes (the hook runs it after each edit too). UI flow touched → `npm run e2e` passes.
3. Contract touched → both sides updated.
4. Architectural decision made → add an ADR in `docs/adr/`.
