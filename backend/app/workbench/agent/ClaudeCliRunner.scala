package workbench.agent

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.{Inject, Singleton}

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, Promise}
import scala.util.control.NonFatal

import play.api.Logging

import workbench.config.{AgentRunsExecutionContext, WorkbenchConfig}

/** Drives `claude -p` headless and turns its stream-json stdout into [[AgentEvent]]s.
  *
  * Threads per run: one reads stdout (and owns `emit`), one drains stderr into a buffer that is emitted from
  * the stdout thread once the process exits, so `emit` stays single-threaded as the [[AgentRunner]] contract
  * says.
  */
@Singleton
final class ClaudeCliRunner @Inject() (cfg: WorkbenchConfig, ec: AgentRunsExecutionContext)
    extends AgentRunner
    with Logging:

  private given AgentRunsExecutionContext = ec

  def command(spec: RunSpec): Seq[String] =
    val c = cfg.claude
    Seq(
      c.binary,
      "-p",
      spec.prompt,
      "--output-format",
      "stream-json",
      "--verbose",
      "--permission-mode",
      c.permissionMode,
      "--max-turns",
      c.maxTurns.toString,
      "--max-budget-usd",
      c.maxBudgetUsd.toString
    ) ++ (if c.allowedTools.nonEmpty then Seq("--allowedTools", c.allowedTools.mkString(",")) else Nil)
      ++ c.extraArgs

  def start(spec: RunSpec, emit: AgentEvent => Unit): RunHandle =
    val cmd = command(spec)
    logger.info(s"run ${spec.runId}: starting ${cmd.head} in ${spec.workdir}")
    val process = new ProcessBuilder(cmd*).directory(spec.workdir.toFile).start()
    process.getOutputStream.close() // otherwise `claude -p` may wait on stdin
    val cancelled = AtomicBoolean(false)
    val stderrBuf = new StringBuilder

    val stderrReader = Future {
      val r = BufferedReader(InputStreamReader(process.getErrorStream, UTF_8))
      Iterator
        .continually(r.readLine())
        .takeWhile(_ != null)
        .foreach(l => stderrBuf.synchronized { stderrBuf.append(l).append('\n'); () })
    }

    val outcome = Promise[RunOutcome]()
    Future {
      var last: Option[AgentEvent.Result] = None
      try
        val r = BufferedReader(InputStreamReader(process.getInputStream, UTF_8))
        Iterator.continually(r.readLine()).takeWhile(_ != null).foreach { line =>
          StreamJsonParser.parseLine(line) match
            case Right(events) =>
              events.foreach {
                case res: AgentEvent.Result => last = Some(res); emit(res)
                case other                  => emit(other)
              }
            case Left(_) => emit(AgentEvent.Log("stdout", line))
        }
        val code = process.waitFor()
        Await.ready(stderrReader, 5.seconds)
        val stderr = stderrBuf.synchronized(stderrBuf.toString)
        stderr.linesIterator.filter(_.nonEmpty).foreach(l => emit(AgentEvent.Log("stderr", l)))
        val result = last match
          case Some(r) => RunOutcome(r.sessionId, r.isError || code != 0, r.costUsd, r.numTurns, r.summary)
          case None =>
            val why =
              if cancelled.get then "cancelled" else s"claude exited with code $code and no result event"
            RunOutcome(None, isError = true, None, None, Some(why))
        outcome.success(result)
      catch
        case NonFatal(e) =>
          process.destroyForcibly()
          outcome.failure(e)
    }

    RunHandle(outcome.future, () => if cancelled.compareAndSet(false, true) then interrupt(process))

  /** SIGINT lets Claude Code end the turn cleanly (SIGTERM drops it); force-kill if it lingers. */
  private def interrupt(p: Process): Unit =
    val pid = p.pid().toString
    Proc.run(Seq("kill", "-INT", pid), Paths.get("."), 5.seconds)
    Future {
      if !p.waitFor(10, TimeUnit.SECONDS) then
        logger.warn(s"pid $pid ignored SIGINT, killing")
        p.destroyForcibly()
    }
