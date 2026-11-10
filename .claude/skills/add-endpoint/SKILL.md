---
name: add-endpoint
description: Add or change an HTTP endpoint in the Play backend and its client in the frontend, keeping the JSON contract in sync. Use for any new route under /api.
---

# Add an endpoint

1. **Route.** Add it to `backend/conf/routes`. Parameterless actions have no parens (Scala 3). Ids are `String` in the route and parsed with `TaskId.parse` / `RunId.parse` in the controller.
2. **Behaviour.** Put logic in a service (`RunSupervisor`, `GitWorktrees`, or a new class under `app/workbench/`), not in the controller. Return `Either[String, A]` for errors the caller can act on.
3. **Controller.** Add the action to `ApiController`. Blocking work runs in `Future { … }` on the injected `DatabaseExecutionContext`. Reuse `withId` / `runCommand` helpers. Error bodies use `apiError(msg)`.
   - Bad input → 400, missing → 404, wrong state → 409.
4. **JSON.** New types get a `given Writes`/`Reads` in `app/workbench/api/JsonFormats.scala`.
5. **Frontend contract.** Mirror the type in `frontend/src/api/types.ts`, add the call to `api` and a key to `keys` in `frontend/src/api/client.ts`.
6. **State that changes runs** must go through `RunSupervisor` so the SSE stream sees it.
7. **Verify.** `cd backend && sbt -client compile test` and `cd frontend && npm run typecheck`. Then `curl` the endpoint against `WORKBENCH_RUNNER=fake sbt run` and paste the response in your summary.
