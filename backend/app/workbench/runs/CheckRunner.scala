package workbench.runs

import java.nio.file.Path
import javax.inject.{Inject, Singleton}

import scala.util.control.NonFatal

import play.api.Logging

import workbench.agent.Proc
import workbench.config.WorkbenchConfig
import workbench.domain.CheckStatus

final case class CheckResult(status: CheckStatus, output: String)

/** Runs the configured verification command (e.g. `sbt test`) in a run's worktree.
  *
  * An agent reporting success only means it finished its turns; the check is what says whether the code it
  * left behind actually builds and passes. It is deliberately not part of the agent session: the command is
  * ours, not the agent's, and its result can't be talked into being green.
  */
@Singleton
final class CheckRunner @Inject() (cfg: WorkbenchConfig) extends Logging:

  def enabled: Boolean = cfg.check.enabled

  /** Blocks for at most `workbench.check.timeout`. Never throws: a check that cannot even start is a failed
    * check, not a failed run.
    */
  def run(workdir: Path): CheckResult =
    val cmd = cfg.check.command
    val line = cmd.mkString(" ")
    try
      val out = Proc.run(cmd, workdir, cfg.check.timeout)
      val body = Seq(out.stdout, out.stderr).map(_.trim).filter(_.nonEmpty).mkString("\n")
      logger.info(s"check `$line` in $workdir exited ${out.code}")
      CheckResult(
        if out.ok then CheckStatus.Passed else CheckStatus.Failed,
        s"$$ $line\nexit ${out.code}\n\n${tail(body)}"
      )
    catch
      case NonFatal(e) =>
        logger.warn(s"check `$line` could not start in $workdir", e)
        CheckResult(CheckStatus.Failed, s"$$ $line\n\ncould not start: ${e.getMessage}")

  /** Keep the tail: build noise is at the top, the failure is at the bottom. */
  private def tail(s: String): String =
    if s.length <= CheckRunner.MaxOutput then s
    else s"…(${s.length - CheckRunner.MaxOutput} earlier characters omitted)\n${s.takeRight(CheckRunner.MaxOutput)}"

object CheckRunner:
  /** Enough to read a failing test, small enough to keep in a row and push over SSE. */
  val MaxOutput = 16_000
