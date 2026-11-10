package workbench.agent

import java.nio.file.Path

import scala.concurrent.Future

import workbench.domain.RunId

final case class RunSpec(runId: RunId, workdir: Path, prompt: String)

final case class RunOutcome(
    sessionId: Option[String],
    isError: Boolean,
    costUsd: Option[BigDecimal],
    numTurns: Option[Int],
    summary: Option[String]
)

/** A started agent session. `cancel` is best-effort and idempotent; `outcome` always completes. */
final case class RunHandle(outcome: Future[RunOutcome], cancel: () => Unit)

/** Seam between the workbench and whatever drives the agent (Claude Code CLI, Agent SDK, a fake). */
trait AgentRunner:
  /** Starts a session in `spec.workdir`. `emit` is called sequentially, in order, from a single thread. */
  def start(spec: RunSpec, emit: AgentEvent => Unit): RunHandle
