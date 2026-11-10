-- !Ups

CREATE TABLE tasks (
  id         UUID PRIMARY KEY,
  repo_path  TEXT        NOT NULL,
  base_ref   TEXT        NOT NULL,
  base_sha   TEXT        NOT NULL,
  prompt     TEXT        NOT NULL,
  created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE runs (
  id                UUID PRIMARY KEY,
  task_id           UUID        NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
  variant           INT         NOT NULL,
  status            TEXT        NOT NULL,
  branch            TEXT        NOT NULL,
  worktree_path     TEXT        NOT NULL,
  claude_session_id TEXT,
  cost_usd          NUMERIC(10, 4),
  num_turns         INT,
  result_summary    TEXT,
  error             TEXT,
  review            TEXT        NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL,
  started_at        TIMESTAMPTZ,
  finished_at       TIMESTAMPTZ,
  UNIQUE (task_id, variant)
);

CREATE INDEX runs_task_idx ON runs (task_id);
CREATE INDEX runs_status_idx ON runs (status);

CREATE TABLE run_events (
  run_id  UUID        NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
  seq     INT         NOT NULL,
  kind    TEXT        NOT NULL,
  payload JSONB       NOT NULL,
  at      TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (run_id, seq)
);

-- !Downs

DROP TABLE run_events;
DROP TABLE runs;
DROP TABLE tasks;
