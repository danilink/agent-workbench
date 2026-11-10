---
name: add-evolution
description: Change the Postgres schema through a new Play evolution and update the JDBC repository and domain model to match. Use for any table or column change.
---

# Add an evolution

1. **Never edit a shipped evolution.** Create `backend/conf/evolutions/default/<next number>.sql` with `-- !Ups` and `-- !Downs` sections. Downs must fully undo Ups.
2. New `NOT NULL` columns on existing tables need a `DEFAULT` or a backfill in the same Ups.
3. Enum-backed columns store the Scala case name (`RunStatus.toString`). Renaming a case needs an `UPDATE` in the evolution.
4. **Update the model** in `backend/app/workbench/domain/Model.scala`.
5. **Update `WorkbenchRepo`**: the SQL string, the binding (`setOpt` for nullable values), and the row mapper. Keep column order in `INSERT`s explicit.
6. **Update JSON** (`JsonFormats.scala`) and `frontend/src/api/types.ts` if the field is exposed.
7. **Verify.** `sbt -client compile test`, then start the app against a fresh database (`docker compose down -v && docker compose up -d`) so Ups apply from scratch, and against the existing one so the new evolution applies on top.
