-- !Ups

-- Result of the optional post-run check (workbench.check.command) in the run's worktree.
-- NULL means no check ran: none configured, or the attempt never succeeded.
ALTER TABLE runs ADD COLUMN check_status TEXT;
ALTER TABLE runs ADD COLUMN check_output TEXT;

-- !Downs

ALTER TABLE runs DROP COLUMN check_output;
ALTER TABLE runs DROP COLUMN check_status;
