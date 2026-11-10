package workbench.agent

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.{Inject, Singleton}

import scala.concurrent.Future

import play.api.libs.json.Json

import workbench.config.AgentRunsExecutionContext

/** Replays a plausible session and writes a real file, so the UI, worktrees and diffs can be exercised for
  * free. Select it with `WORKBENCH_RUNNER=fake`.
  */
@Singleton
final class FakeRunner @Inject() (ec: AgentRunsExecutionContext) extends AgentRunner:
  private given AgentRunsExecutionContext = ec

  def start(spec: RunSpec, emit: AgentEvent => Unit): RunHandle =
    val cancelled = AtomicBoolean(false)
    def step(e: AgentEvent): Unit =
      if cancelled.get then throw InterruptedException("cancelled")
      Thread.sleep(600)
      emit(e)

    val file = s"NOTES-${spec.runId.value.toString.take(8)}.md"
    val outcome = Future {
      try
        step(AgentEvent.Init(s"fake-${spec.runId.value}", Some("fake-model"), Some(spec.workdir.toString)))
        step(AgentEvent.Text("I'll look at the repository layout first."))
        step(AgentEvent.ToolUse("t1", "Glob", Json.obj("pattern" -> "**/*")))
        step(AgentEvent.ToolResult("t1", isError = false, "README.md\nsrc/…"))
        step(AgentEvent.ToolUse("t2", "Write", Json.obj("file_path" -> file)))
        Files.writeString(spec.workdir.resolve(file), s"# Notes\n\nPrompt was:\n\n> ${spec.prompt}\n", UTF_8)
        step(AgentEvent.ToolResult("t2", isError = false, s"Wrote $file"))
        // `new` keeps the case type; the enum's apply widens to AgentEvent.
        val result = new AgentEvent.Result(
          Some(s"fake-${spec.runId.value}"),
          isError = false,
          Some(BigDecimal("0.0000")),
          Some(3),
          Some(s"Created $file")
        )
        step(result)
        RunOutcome(result.sessionId, result.isError, result.costUsd, result.numTurns, result.summary)
      catch
        case _: InterruptedException =>
          RunOutcome(None, isError = true, None, None, Some("cancelled"))
    }
    RunHandle(outcome, () => cancelled.set(true))
