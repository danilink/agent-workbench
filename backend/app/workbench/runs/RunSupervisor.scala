package workbench.runs

import java.nio.file.{Path, Paths}
import java.time.Instant
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.{Inject, Singleton}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.control.NonFatal

import play.api.Logging

import workbench.agent.{AgentEvent, AgentRunner, RunHandle, RunSpec}
import workbench.config.{AgentRunsExecutionContext, WorkbenchConfig}
import workbench.domain.*
import workbench.events.{BusMessage, EventBus}
import workbench.git.GitWorktrees
import workbench.store.WorkbenchRepo

/** Owns the lifecycle of every run: queue → worktree → agent session → result → review.
  *
  * Concurrency is bounded by a semaphore of `max-parallel-runs`; a run waiting for a slot is visibly `Queued`.
  * Each run is driven by exactly one thread, which is the only writer of its row and its event sequence.
  */
@Singleton
final class RunSupervisor @Inject() (
    cfg: WorkbenchConfig,
    repo: WorkbenchRepo,
    git: GitWorktrees,
    runner: AgentRunner,
    bus: EventBus,
    ec: AgentRunsExecutionContext
) extends Logging:

  private given AgentRunsExecutionContext = ec

  private val slots   = Semaphore(cfg.maxParallelRuns, true)
  private val live    = TrieMap.empty[RunId, RunHandle]
  private val cancels = TrieMap.empty[RunId, Unit]

  locally {
    val n = repo.failOrphanedRuns(Instant.now())
    if n > 0 then logger.warn(s"marked $n orphaned run(s) as Failed")
  }

  /** Persists `variants` runs for the task and schedules them. Returns immediately. */
  def submit(task: Task, variants: Int): Seq[Run] =
    val runs = (1 to variants).map { v =>
      val id = RunId.random()
      Run(
        id = id,
        taskId = task.id,
        variant = v,
        status = RunStatus.Queued,
        branch = s"wb/${task.id.value.toString.take(8)}-v$v",
        worktreePath = cfg.worktreeRoot.resolve(id.value.toString).toString,
        claudeSessionId = None,
        costUsd = None,
        numTurns = None,
        resultSummary = None,
        error = None,
        review = ReviewState.Pending,
        createdAt = Instant.now(),
        startedAt = None,
        finishedAt = None
      )
    }
    runs.foreach(repo.insertRun)
    runs.foreach(r => Future(execute(task, r)))
    runs

  def cancel(id: RunId): Either[String, Run] =
    repo.getRun(id).toRight("run not found").flatMap { run =>
      if run.status.isTerminal then Left(s"run is already ${run.status}")
      else
        cancels.put(id, ())
        live.get(id).foreach(_.cancel())
        Right(run)
    }

  /** Keeps the branch for merging, frees the worktree. */
  def accept(id: RunId): Either[String, Run] = review(id, ReviewState.Accepted, deleteBranch = false)

  /** Drops both the worktree and the branch. */
  def discard(id: RunId): Either[String, Run] = review(id, ReviewState.Discarded, deleteBranch = true)

  private def review(id: RunId, to: ReviewState, deleteBranch: Boolean): Either[String, Run] =
    for
      run  <- repo.getRun(id).toRight("run not found")
      _    <- Either.cond(run.status.isTerminal, (), s"run is still ${run.status}")
      _    <- Either.cond(run.review == ReviewState.Pending, (), s"run was already ${run.review}")
      task <- repo.getTask(run.taskId).toRight("task not found")
      _    <- git.remove(Paths.get(task.repoPath), Paths.get(run.worktreePath), run.branch, deleteBranch)
    yield save(run.copy(review = to))

  // --- execution (runs on the agent-runs dispatcher) --------------------------

  private def execute(task: Task, initial: Run): Unit =
    slots.acquire()
    var run = initial
    try
      if cancels.contains(run.id) then run = save(run.copy(status = RunStatus.Cancelled, finishedAt = Some(Instant.now())))
      else
        run = save(run.copy(status = RunStatus.Preparing, startedAt = Some(Instant.now())))
        val workdir = Paths.get(run.worktreePath)
        git.create(Paths.get(task.repoPath), workdir, run.branch, task.baseSha) match
          case Left(err) => run = finish(run, RunStatus.Failed, Some(err))
          case Right(_)  => run = drive(task, run, workdir)
    catch
      case NonFatal(e) =>
        logger.error(s"run ${run.id} crashed", e)
        run = finish(run, RunStatus.Failed, Some(e.getMessage))
    finally
      live.remove(run.id)
      cancels.remove(run.id)
      slots.release()

  private def drive(task: Task, start: Run, workdir: Path): Run =
    val run    = save(start.copy(status = RunStatus.Running))
    val seq    = AtomicInteger(0)
    val handle = runner.start(RunSpec(run.id, workdir, task.prompt), e => record(run.id, seq, e))
    live.put(run.id, handle)
    if cancels.contains(run.id) then handle.cancel() // cancel raced with start

    val outcome = Await.result(handle.outcome, Duration.Inf)
    val status =
      if cancels.contains(run.id) then RunStatus.Cancelled
      else if outcome.isError then RunStatus.Failed
      else RunStatus.Succeeded
    save(
      run.copy(
        status = status,
        claudeSessionId = outcome.sessionId,
        costUsd = outcome.costUsd,
        numTurns = outcome.numTurns,
        resultSummary = outcome.summary,
        error = if status == RunStatus.Failed then outcome.summary else None,
        finishedAt = Some(Instant.now())
      )
    )

  private def record(id: RunId, seq: AtomicInteger, e: AgentEvent): Unit =
    val (kind, payload) = AgentEvent.toRecord(e)
    val event           = RunEvent(id, seq.incrementAndGet(), kind, payload, Instant.now())
    repo.appendEvent(event)
    bus.publish(BusMessage.EventAppended(event))

  private def finish(run: Run, status: RunStatus, error: Option[String]): Run =
    save(run.copy(status = status, error = error, finishedAt = Some(Instant.now())))

  private def save(run: Run): Run =
    repo.updateRun(run)
    bus.publish(BusMessage.RunUpdated(run))
    run
