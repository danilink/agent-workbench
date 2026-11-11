package workbench.runs

import java.nio.file.{Path, Paths}
import java.time.Instant
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{Semaphore, TimeUnit, TimeoutException}
import javax.inject.{Inject, Singleton}

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, Promise}
import scala.util.Try
import scala.util.control.NonFatal

import play.api.Logging
import play.api.inject.ApplicationLifecycle

import workbench.agent.{AgentEvent, AgentRunner, RunHandle, RunOutcome, RunSpec}
import workbench.config.{AgentRunsExecutionContext, WorkbenchConfig}
import workbench.domain.*
import workbench.events.{BusMessage, EventBus}
import workbench.git.GitWorktrees
import workbench.store.WorkbenchRepo

/** Owns the lifecycle of every run: queue → worktree → agent session → result → review.
  *
  * Concurrency is bounded by a semaphore of `max-parallel-runs`; a run waiting for a slot is visibly
  * `Queued`. Each run is driven by exactly one thread, which is the only writer of its row and its event
  * sequence.
  *
  * Every run ends: `max-run-duration` cancels a run that outlives it, and stopping the server (including a
  * dev-mode reload) cancels all live runs and waits for their processes to die. See docs/adr/0004.
  */
@Singleton
final class RunSupervisor @Inject() (
    cfg: WorkbenchConfig,
    repo: WorkbenchRepo,
    git: GitWorktrees,
    runner: AgentRunner,
    bus: EventBus,
    ec: AgentRunsExecutionContext,
    lifecycle: ApplicationLifecycle
) extends Logging:
  import RunSupervisor.Watched

  private given AgentRunsExecutionContext = ec

  private val slots = Semaphore(cfg.maxParallelRuns, true)
  private val live = TrieMap.empty[RunId, RunHandle]
  private val cancels = TrieMap.empty[RunId, Unit]
  private val stopping = AtomicBoolean(false)
  // Completed when a run's thread is done with it, however it ended. The stop hook waits on these.
  private val inFlight = TrieMap.empty[RunId, Promise[Unit]]

  // How long to wait for a cancelled run to wind down: SIGINT grace, then force-kill, plus margin.
  private val windDown = cfg.cancelGrace + 5.seconds

  lifecycle.addStopHook { () =>
    stopping.set(true)
    val pending = inFlight.values.map(_.future).toList
    if pending.nonEmpty then
      logger.warn(
        s"server stopping: cancelling ${live.size} live run(s), waiting for ${pending.size} in flight"
      )
    live.values.foreach(_.cancel())
    Future(Try(Await.ready(Future.sequence(pending), windDown))).map(_ => ())
  }

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
    runs.foreach(r => inFlight.put(r.id, Promise()))
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
      run <- repo.getRun(id).toRight("run not found")
      _ <- Either.cond(run.status.isTerminal, (), s"run is still ${run.status}")
      _ <- Either.cond(run.review == ReviewState.Pending, (), s"run was already ${run.review}")
      task <- repo.getTask(run.taskId).toRight("task not found")
      _ <- git.remove(Paths.get(task.repoPath), Paths.get(run.worktreePath), run.branch, deleteBranch)
    yield save(run.copy(review = to))

  // --- execution (runs on the agent-runs dispatcher) --------------------------

  private def execute(task: Task, initial: Run): Unit =
    var run = initial
    try
      if !awaitSlot(run.id) then
        // Cancelled while queued. If the server is stopping instead, leave it Queued: the next boot fails it.
        if !stopping.get then run = finish(run, RunStatus.Cancelled, None)
      else
        try
          run = save(run.copy(status = RunStatus.Preparing, startedAt = Some(Instant.now())))
          val workdir = Paths.get(run.worktreePath)
          git.create(Paths.get(task.repoPath), workdir, run.branch, task.baseSha) match
            case Left(err) => run = finish(run, RunStatus.Failed, Some(err))
            case Right(_)  => run = drive(task, run, workdir)
        finally slots.release()
    catch
      case NonFatal(e) =>
        logger.error(s"run ${run.id} crashed", e)
        run = finish(run, RunStatus.Failed, Some(e.getMessage))
    finally
      live.remove(run.id)
      cancels.remove(run.id)
      inFlight.remove(run.id).foreach(_.trySuccess(()))

  /** Waits for a slot, but gives up as soon as the run is cancelled or the server stops. False: don't run it.
    */
  @tailrec private def awaitSlot(id: RunId): Boolean =
    def abandoned = stopping.get || cancels.contains(id)
    if abandoned then false
    else if slots.tryAcquire(1, TimeUnit.SECONDS) then
      if abandoned then
        slots.release()
        false
      else true
    else awaitSlot(id)

  private def drive(task: Task, start: Run, workdir: Path): Run =
    val run = save(start.copy(status = RunStatus.Running))
    val seq = AtomicInteger(0)
    val handle = runner.start(RunSpec(run.id, workdir, task.prompt), e => record(run.id, seq, e))
    live.put(run.id, handle)
    if cancels.contains(run.id) || stopping.get then handle.cancel() // raced with start

    val watched = RunSupervisor.awaitWithDeadline(handle, cfg.maxRunDuration, windDown)
    val (status, error, outcome) = watched match
      case Watched.TimedOut(o) =>
        if o.isEmpty then logger.error(s"run ${run.id} still not dead ${windDown} after cancelling it")
        (RunStatus.Failed, Some(s"timed out after ${cfg.maxRunDuration}"), o)
      case Watched.Finished(o) if cancels.contains(run.id) => (RunStatus.Cancelled, None, Some(o))
      case Watched.Finished(o) if o.isError && stopping.get =>
        (RunStatus.Failed, Some("interrupted by server shutdown"), Some(o))
      case Watched.Finished(o) if o.isError => (RunStatus.Failed, o.summary, Some(o))
      case Watched.Finished(o)              => (RunStatus.Succeeded, None, Some(o))
    save(
      run.copy(
        status = status,
        claudeSessionId = outcome.flatMap(_.sessionId),
        costUsd = outcome.flatMap(_.costUsd),
        numTurns = outcome.flatMap(_.numTurns),
        resultSummary = outcome.flatMap(_.summary),
        error = error,
        finishedAt = Some(Instant.now())
      )
    )

  private def record(id: RunId, seq: AtomicInteger, e: AgentEvent): Unit =
    val (kind, payload) = AgentEvent.toRecord(e)
    val event = RunEvent(id, seq.incrementAndGet(), kind, payload, Instant.now())
    repo.appendEvent(event)
    bus.publish(BusMessage.EventAppended(event))

  private def finish(run: Run, status: RunStatus, error: Option[String]): Run =
    save(run.copy(status = status, error = error, finishedAt = Some(Instant.now())))

  private def save(run: Run): Run =
    repo.updateRun(run)
    bus.publish(BusMessage.RunUpdated(run))
    run

object RunSupervisor:

  enum Watched:
    case Finished(outcome: RunOutcome)

    /** Outlived its deadline and was cancelled. `outcome` is what it reported while winding down, if it did.
      */
    case TimedOut(outcome: Option[RunOutcome])

  /** Blocks until the run ends, cancelling it once `deadline` passes. Always returns within deadline +
    * windDown.
    */
  def awaitWithDeadline(handle: RunHandle, deadline: FiniteDuration, windDown: FiniteDuration): Watched =
    try Watched.Finished(Await.result(handle.outcome, deadline))
    catch
      case _: TimeoutException =>
        handle.cancel()
        Watched.TimedOut(Try(Await.result(handle.outcome, windDown)).toOption)
