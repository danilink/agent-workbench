# 0001 — One git worktree and branch per run

**Status:** accepted

## Context

Several agent sessions must edit the same repository at the same time, and each result has to be reviewable and reversible.

## Decision

Every run gets `git worktree add -b wb/<task>-v<n> <root>/<runId> <baseSha>`. The task pins `baseSha` when it is created, so all attempts start from the same commit even if the branch moves. The diff shown in the UI is `git diff <baseSha>` inside the worktree after `git add --intent-to-add --all`, which covers commits, staged, unstaged and new files.

Accept commits anything left uncommitted in the worktree onto the run's branch (agents rarely commit), then removes the worktree and keeps the branch. Discard removes both.

## Consequences

- Parallel sessions are fully isolated on disk; no locking needed.
- Results are plain branches: merge, PR or cherry-pick with normal tools.
- Worktrees cost disk and need dependency installs per checkout (e.g. `node_modules`). Acceptable for now; a shared cache is an M3+ concern.
- `--intent-to-add` touches the worktree's index. Harmless because the worktree belongs to the run.
