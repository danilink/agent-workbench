package workbench.store

import java.sql.{Connection, PreparedStatement, ResultSet, Timestamp, Types}
import java.time.Instant
import java.util.UUID
import javax.inject.{Inject, Singleton}

import scala.collection.mutable.ListBuffer
import scala.util.Using

import play.api.db.Database
import play.api.libs.json.Json

import workbench.domain.*

/** Plain JDBC on purpose: four tables don't justify an ORM, and the SQL stays greppable for agents and
  * humans.
  *
  * Every method BLOCKS. Call it from [[workbench.config.DatabaseExecutionContext]] or from an agent-run
  * thread, never from Play's default dispatcher.
  */
@Singleton
final class WorkbenchRepo @Inject() (db: Database):

  // --- tasks ------------------------------------------------------------------

  def insertTask(t: Task): Unit = db.withConnection { c =>
    update(
      c,
      "INSERT INTO tasks (id, repo_path, base_ref, base_sha, prompt, created_at) VALUES (?,?,?,?,?,?)"
    ) { ps =>
      ps.setObject(1, t.id.value)
      ps.setString(2, t.repoPath)
      ps.setString(3, t.baseRef)
      ps.setString(4, t.baseSha)
      ps.setString(5, t.prompt)
      ps.setTimestamp(6, Timestamp.from(t.createdAt))
    }
  }

  def getTask(id: TaskId): Option[Task] = db.withConnection { c =>
    query(c, "SELECT * FROM tasks WHERE id = ?")(_.setObject(1, id.value))(task).headOption
  }

  def listTasks(limit: Int): Seq[Task] = db.withConnection { c =>
    query(c, "SELECT * FROM tasks ORDER BY created_at DESC LIMIT ?")(_.setInt(1, limit))(task)
  }

  // --- runs -------------------------------------------------------------------

  def insertRun(r: Run): Unit = db.withConnection { c =>
    update(
      c,
      """INSERT INTO runs (id, task_id, variant, status, branch, worktree_path, review, created_at)
        |VALUES (?,?,?,?,?,?,?,?)""".stripMargin
    ) { ps =>
      ps.setObject(1, r.id.value)
      ps.setObject(2, r.taskId.value)
      ps.setInt(3, r.variant)
      ps.setString(4, r.status.toString)
      ps.setString(5, r.branch)
      ps.setString(6, r.worktreePath)
      ps.setString(7, r.review.toString)
      ps.setTimestamp(8, Timestamp.from(r.createdAt))
    }
  }

  /** Writes every mutable column. Runs have a single writer (their supervisor thread), so last-write-wins is
    * fine.
    */
  def updateRun(r: Run): Unit = db.withConnection { c =>
    update(
      c,
      """UPDATE runs SET status = ?, claude_session_id = ?, cost_usd = ?, num_turns = ?, result_summary = ?,
        |error = ?, review = ?, started_at = ?, finished_at = ? WHERE id = ?""".stripMargin
    ) { ps =>
      ps.setString(1, r.status.toString)
      setOpt(ps, 2, r.claudeSessionId, Types.VARCHAR)(ps.setString)
      setOpt(ps, 3, r.costUsd.map(_.bigDecimal), Types.NUMERIC)(ps.setBigDecimal)
      setOpt(ps, 4, r.numTurns, Types.INTEGER)(ps.setInt)
      setOpt(ps, 5, r.resultSummary, Types.VARCHAR)(ps.setString)
      setOpt(ps, 6, r.error, Types.VARCHAR)(ps.setString)
      ps.setString(7, r.review.toString)
      setOpt(ps, 8, r.startedAt.map(Timestamp.from), Types.TIMESTAMP_WITH_TIMEZONE)(ps.setTimestamp)
      setOpt(ps, 9, r.finishedAt.map(Timestamp.from), Types.TIMESTAMP_WITH_TIMEZONE)(ps.setTimestamp)
      ps.setObject(10, r.id.value)
    }
  }

  def getRun(id: RunId): Option[Run] = db.withConnection { c =>
    query(c, "SELECT * FROM runs WHERE id = ?")(_.setObject(1, id.value))(run).headOption
  }

  def runsForTask(id: TaskId): Seq[Run] = db.withConnection { c =>
    query(c, "SELECT * FROM runs WHERE task_id = ? ORDER BY variant")(_.setObject(1, id.value))(run)
  }

  /** Runs left non-terminal by a previous process (crash, redeploy) can never finish: fail them on boot. */
  def failOrphanedRuns(now: Instant): Int = db.withConnection { c =>
    update(
      c,
      """UPDATE runs SET status = 'Failed', error = 'interrupted by server restart', finished_at = ?
        |WHERE status IN ('Queued', 'Preparing', 'Running')""".stripMargin
    )(_.setTimestamp(1, Timestamp.from(now)))
  }

  // --- events -----------------------------------------------------------------

  def appendEvent(e: RunEvent): Unit = db.withConnection { c =>
    update(c, "INSERT INTO run_events (run_id, seq, kind, payload, at) VALUES (?,?,?,?::jsonb,?)") { ps =>
      ps.setObject(1, e.runId.value)
      ps.setInt(2, e.seq)
      ps.setString(3, e.kind.toString)
      ps.setString(4, Json.stringify(e.payload))
      ps.setTimestamp(5, Timestamp.from(e.at))
    }
  }

  def events(id: RunId, afterSeq: Int): Seq[RunEvent] = db.withConnection { c =>
    query(c, "SELECT * FROM run_events WHERE run_id = ? AND seq > ? ORDER BY seq") { ps =>
      ps.setObject(1, id.value)
      ps.setInt(2, afterSeq)
    }(event)
  }

  // --- row mappers ------------------------------------------------------------

  private def task(rs: ResultSet): Task = Task(
    id = TaskId(rs.getObject("id", classOf[UUID])),
    repoPath = rs.getString("repo_path"),
    baseRef = rs.getString("base_ref"),
    baseSha = rs.getString("base_sha"),
    prompt = rs.getString("prompt"),
    createdAt = rs.getTimestamp("created_at").toInstant
  )

  private def run(rs: ResultSet): Run = Run(
    id = RunId(rs.getObject("id", classOf[UUID])),
    taskId = TaskId(rs.getObject("task_id", classOf[UUID])),
    variant = rs.getInt("variant"),
    status = RunStatus.valueOf(rs.getString("status")),
    branch = rs.getString("branch"),
    worktreePath = rs.getString("worktree_path"),
    claudeSessionId = Option(rs.getString("claude_session_id")),
    costUsd = Option(rs.getBigDecimal("cost_usd")).map(BigDecimal(_)),
    numTurns = optInt(rs, "num_turns"),
    resultSummary = Option(rs.getString("result_summary")),
    error = Option(rs.getString("error")),
    review = ReviewState.valueOf(rs.getString("review")),
    createdAt = rs.getTimestamp("created_at").toInstant,
    startedAt = Option(rs.getTimestamp("started_at")).map(_.toInstant),
    finishedAt = Option(rs.getTimestamp("finished_at")).map(_.toInstant)
  )

  private def event(rs: ResultSet): RunEvent = RunEvent(
    runId = RunId(rs.getObject("run_id", classOf[UUID])),
    seq = rs.getInt("seq"),
    kind = EventKind.valueOf(rs.getString("kind")),
    payload = Json.parse(rs.getString("payload")),
    at = rs.getTimestamp("at").toInstant
  )

  // --- JDBC helpers -----------------------------------------------------------

  private def update(c: Connection, sql: String)(bind: PreparedStatement => Unit): Int =
    Using.resource(c.prepareStatement(sql)) { ps =>
      bind(ps)
      ps.executeUpdate()
    }

  private def query[A](c: Connection, sql: String)(bind: PreparedStatement => Unit)(
      row: ResultSet => A
  ): Seq[A] =
    Using.resource(c.prepareStatement(sql)) { ps =>
      bind(ps)
      Using.resource(ps.executeQuery()) { rs =>
        val out = ListBuffer.empty[A]
        while rs.next() do out += row(rs)
        out.toList
      }
    }

  private def setOpt[A](ps: PreparedStatement, i: Int, v: Option[A], sqlType: Int)(
      set: (Int, A) => Unit
  ): Unit =
    v match
      case Some(a) => set(i, a)
      case None    => ps.setNull(i, sqlType)

  private def optInt(rs: ResultSet, col: String): Option[Int] =
    val v = rs.getInt(col)
    if rs.wasNull() then None else Some(v)
