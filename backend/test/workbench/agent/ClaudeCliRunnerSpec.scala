package workbench.agent

import java.nio.file.{Files, Path, Paths}
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, Promise}
import scala.jdk.CollectionConverters.*

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import workbench.config.{AgentRunsExecutionContext, ClaudeConfig, WorkbenchConfig}
import workbench.domain.RunId

/** Drives the real runner against a shell script standing in for `claude`, on a dispatcher configured like
  * production, started from one of that dispatcher's own threads (as RunSupervisor does).
  */
class ClaudeCliRunnerSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll:

  private val system = ActorSystem(
    "runner-spec",
    ConfigFactory.parseString("""
      agent-runs-dispatcher {
        executor = "thread-pool-executor"
        throughput = 1
        thread-pool-executor.fixed-pool-size = 16
      }
    """)
  )
  private val ec = AgentRunsExecutionContext(system)
  private given scala.concurrent.ExecutionContext = ec

  override def afterAll(): Unit = Await.ready(system.terminate(), 10.seconds): Unit

  private val init = """{"type":"system","subtype":"init","session_id":"s1","model":"m","cwd":"/tmp"}"""
  private val result =
    """{"type":"result","subtype":"success","is_error":false,"session_id":"s1","total_cost_usd":0.01,"num_turns":2,"result":"done"}"""

  private def fakeClaude(body: String): Path =
    val f = Files.createTempFile("fake-claude", ".sh")
    Files.writeString(f, s"#!/bin/sh\n$body\n")
    Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwx------"))
    f.toFile.deleteOnExit()
    f

  private def runner(binary: Path) =
    val claude = ClaudeConfig(binary.toString, "acceptEdits", Nil, 5, 1.0, Nil)
    val cfg = WorkbenchConfig(Nil, Paths.get("/tmp"), 1, 1.minute, cancelGrace = 1.second, "claude", claude)
    ClaudeCliRunner(cfg, ec)

  /** Starts the fake, waits for its Init event, cancels it. Returns ms from cancel to outcome, and the
    * outcome.
    */
  private def startThenCancel(binary: Path): (Long, RunOutcome) =
    val sawInit = Promise[Unit]()
    val handle = runner(binary).start(
      RunSpec(RunId(UUID.randomUUID()), Paths.get("/tmp"), "prompt"),
      {
        case _: AgentEvent.Init => sawInit.trySuccess(()): Unit
        case _                  => ()
      }
    )
    Await.result(sawInit.future, 10.seconds)
    val t0 = System.nanoTime()
    handle.cancel()
    val outcome = Await.result(handle.outcome, 15.seconds)
    ((System.nanoTime() - t0) / 1_000_000, outcome)

  private def eventuallyDead(pid: Long): Boolean =
    (1 to 40).exists { _ =>
      Thread.sleep(50)
      !ProcessHandle.of(pid).map(_.isAlive).orElse(false)
    }

  /** A script that starts a long-lived child (as an agent starting sbt would) and records its pid. */
  private def withChild(pidFile: Path, prelude: String = ""): Path =
    // A background job of a non-interactive shell ignores SIGINT, so it outlives its parent unless killed.
    fakeClaude(s"$prelude\nsleep 300 &\necho $$! > $pidFile\necho '$init'\nwait")

  /** Starts a run from inside a task on the agent-runs dispatcher and blocks there, like
    * RunSupervisor.execute.
    */
  private def runOnDispatcher(binary: Path): (RunOutcome, Seq[(Long, AgentEvent)], Long) =
    val events = ConcurrentLinkedQueue[(Long, AgentEvent)]()
    val t0 = System.nanoTime()
    val outcome = Future {
      val handle = runner(binary).start(
        RunSpec(RunId(UUID.randomUUID()), Paths.get("/tmp"), "prompt"),
        e => events.add((System.nanoTime() - t0) / 1_000_000 -> e): Unit
      )
      Await.result(handle.outcome, 30.seconds)
    }
    val o = Await.result(outcome, 40.seconds)
    (o, events.asScala.toSeq, (System.nanoTime() - t0) / 1_000_000)

  "ClaudeCliRunner" should {

    "stream stdout while the process is still running" in {
      val (outcome, events, totalMs) = runOnDispatcher(fakeClaude(s"echo '$init'\nsleep 3\necho '$result'"))

      val (initAtMs, _) = events.find(_._2.isInstanceOf[AgentEvent.Init]).getOrElse(fail("no Init event"))
      withClue(s"Init arrived at ${initAtMs}ms, process ended at ${totalMs}ms: ") {
        initAtMs should be < 2000L
      }
      outcome.isError shouldBe false
      outcome.summary shouldBe Some("done")
    }

    "read more output than fits in a pipe buffer without losing the result" in {
      // ~200 KB on one line: more than a pipe holds, so the writer blocks unless stdout is drained concurrently.
      val big =
        s"""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"${"x" * 200000}"}]}}"""
      val bin = fakeClaude(s"echo 'started' >&2\necho '$init'\necho '$big'\necho '$result'\nsleep 1")

      val (outcome, events, _) = runOnDispatcher(bin)

      events.collect { case (_, r: AgentEvent.ToolResult) => r }.size shouldBe 1
      outcome.isError shouldBe false
      outcome.summary shouldBe Some("done")
    }

    "on cancel, kill claude and the processes it started" in {
      val pidFile = Files.createTempFile("child", ".pid")
      val (ms, outcome) = startThenCancel(withChild(pidFile))

      withClue("child process survived the cancel: ")(
        eventuallyDead(Files.readString(pidFile).trim.toLong) shouldBe true
      )
      outcome.isError shouldBe true
      outcome.summary shouldBe Some("cancelled")
      ms should be < 3000L
    }

    "force-kill a claude that ignores SIGINT once the grace period is over" in {
      val pidFile = Files.createTempFile("child", ".pid")
      val (ms, outcome) = startThenCancel(withChild(pidFile, prelude = "trap '' INT"))

      withClue("child process survived the cancel: ")(
        eventuallyDead(Files.readString(pidFile).trim.toLong) shouldBe true
      )
      outcome.summary shouldBe Some("cancelled")
      ms should (be >= 1000L and be < 5000L)
    }
  }
