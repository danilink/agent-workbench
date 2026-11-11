package workbench.runs

import java.nio.file.{Files, Path, Paths}

import scala.concurrent.duration.*

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import workbench.config.{CheckConfig, ClaudeConfig, WorkbenchConfig}
import workbench.domain.CheckStatus

class CheckRunnerSpec extends AnyWordSpec with Matchers:

  private val workdir: Path = Files.createTempDirectory("check-wd")

  private def checks(command: Seq[String], timeout: FiniteDuration = 10.seconds) =
    val claude = ClaudeConfig("claude", "acceptEdits", Nil, 1, 1.0, Nil)
    CheckRunner(
      WorkbenchConfig(
        Nil,
        Paths.get("/tmp"),
        1,
        1.minute,
        1.second,
        "claude",
        claude,
        CheckConfig(command, timeout)
      )
    )

  "CheckRunner" should {

    "be off when no command is configured" in {
      checks(Nil).enabled shouldBe false
      checks(Seq("true")).enabled shouldBe true
    }

    "pass on exit 0 and run in the worktree" in {
      Files.writeString(workdir.resolve("marker.txt"), "here")
      val r = checks(Seq("sh", "-c", "cat marker.txt")).run(workdir)

      r.status shouldBe CheckStatus.Passed
      r.output should include("exit 0")
      r.output should include("here")
    }

    "fail on a non-zero exit and keep what the command printed" in {
      val r = checks(Seq("sh", "-c", "echo 'test FooSpec failed' >&2; exit 1")).run(workdir)

      r.status shouldBe CheckStatus.Failed
      r.output should include("exit 1")
      r.output should include("test FooSpec failed")
    }

    "fail, without throwing, when the command does not exist" in {
      val r = checks(Seq("no-such-command-xyz")).run(workdir)

      r.status shouldBe CheckStatus.Failed
      r.output should include("could not start")
    }

    "fail once the timeout passes instead of holding the slot" in {
      val t0 = System.nanoTime()
      val r = checks(Seq("sh", "-c", "sleep 30"), timeout = 500.millis).run(workdir)

      r.status shouldBe CheckStatus.Failed
      r.output should include("timed out")
      (System.nanoTime() - t0) / 1_000_000 should be < 3000L
    }

    "keep only the tail of long output, where failures are" in {
      val r = checks(
        Seq(
          "sh",
          "-c",
          "i=0; while [ $i -lt 3000 ]; do echo noise-line-$i; i=$((i+1)); done; echo THE-FAILURE"
        )
      )
        .run(workdir)

      r.output should include("THE-FAILURE")
      r.output should include("earlier characters omitted")
      r.output should not include "noise-line-0\n"
    }

    "hand the process to the caller so it can be killed early" in {
      var seen: Option[Process] = None
      checks(Seq("true")).run(workdir, p => seen = Some(p))
      seen should not be empty
    }
  }
