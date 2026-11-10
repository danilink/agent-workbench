# 0003 — In-process event bus now, Redis later

**Status:** accepted (to be revisited in M3)

## Context

The UI needs live updates for runs and agent events. Runs execute on the node that started them.

## Decision

A Pekko Streams `BroadcastHub` fed by a bounded queue (`dropHead`) fans out `RunUpdated` / `EventAppended` to every SSE connection on `/api/stream`.

Clients load state over REST and treat the stream as a freshness hint: on (re)connect they invalidate their caches, and when a run finishes they refetch its events and diff. So a dropped message costs freshness, never correctness.

## Consequences

- Zero infrastructure beyond Postgres.
- Only correct with a single Play node. With several nodes, a client connected to node A won't see runs executing on node B. M3 replaces the hub's source with Redis pub/sub; the SSE endpoint and the client don't change.
