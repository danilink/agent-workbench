package workbench.domain

import java.time.Instant

import play.api.libs.json.JsValue

/** A unit of work handed to agents: one prompt against one repo at a pinned commit. */
final case class Task(
    id: TaskId,
    repoPath: String,
    baseRef: String,
    baseSha: String,
    prompt: String,
    createdAt: Instant
)

enum RunStatus derives CanEqual:
  case Queued, Preparing, Running, Succeeded, Failed, Cancelled

  def isTerminal: Boolean = this match
    case Succeeded | Failed | Cancelled => true
    case Queued | Preparing | Running   => false

enum ReviewState derives CanEqual:
  case Pending, Accepted, Discarded

/** Verdict of the post-run check (`workbench.check.command`) in the run's worktree. `None` on a [[Run]] means
  * no check ran: none is configured, or the attempt did not succeed.
  */
enum CheckStatus derives CanEqual:
  case Running, Passed, Failed

/** One agent session working on a task inside its own git worktree. A task fans out to N runs. */
final case class Run(
    id: RunId,
    taskId: TaskId,
    variant: Int,
    status: RunStatus,
    branch: String,
    worktreePath: String,
    claudeSessionId: Option[String],
    costUsd: Option[BigDecimal],
    numTurns: Option[Int],
    resultSummary: Option[String],
    error: Option[String],
    checkStatus: Option[CheckStatus],
    checkOutput: Option[String],
    review: ReviewState,
    createdAt: Instant,
    startedAt: Option[Instant],
    finishedAt: Option[Instant]
)

enum EventKind derives CanEqual:
  case Init, Text, ToolUse, ToolResult, Result, Log, Unknown

/** Persisted, ordered record of what an agent did. `payload` is the normalised event, not the raw CLI line.
  */
final case class RunEvent(runId: RunId, seq: Int, kind: EventKind, payload: JsValue, at: Instant)

final case class TaskDetail(task: Task, runs: Seq[Run])
