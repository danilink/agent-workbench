package workbench.agent

import java.nio.file.{Files, Paths}

import scala.concurrent.duration.*

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ProcSpec extends AnyWordSpec with Matchers:

  private val tmp = Paths.get(System.getProperty("java.io.tmpdir"))

  private def timed[A](f: => A): (A, Long) =
    val t0 = System.nanoTime()
    val a = f
    (a, (System.nanoTime() - t0) / 1_000_000)

  private def eventuallyDead(pid: Long): Boolean =
    (1 to 40).exists { _ =>
      Thread.sleep(50)
      !ProcessHandle.of(pid).map(_.isAlive).orElse(false)
    }

  "Proc.run" should {

    "return the exit code and both streams" in {
      val out = Proc.run(Seq("sh", "-c", "echo out; echo err >&2; exit 3"), tmp)
      out shouldBe Proc.Out(3, "out\n", "err\n")
    }

    "enforce the timeout on a process that keeps its output open" in {
      // The old implementation read stdout to EOF before waiting, so this took the full 30s.
      val (out, ms) = timed(Proc.run(Seq("sh", "-c", "echo started; sleep 30"), tmp, 500.millis))

      out.code shouldBe -1
      out.stderr should include("timed out")
      ms should be < 3000L
    }

    "kill what the process started when it times out" in {
      val pidFile = Files.createTempFile("proc-child", ".pid")
      Proc.run(Seq("sh", "-c", s"sleep 300 & echo $$! > $pidFile; wait"), tmp, 500.millis)

      withClue("child outlived the timeout: ")(
        eventuallyDead(Files.readString(pidFile).trim.toLong) shouldBe true
      )
    }

    "not deadlock when both streams carry more than a pipe buffer" in {
      val script =
        "i=0; while [ $i -lt 4000 ]; do echo 0123456789012345678901234567890123456789; echo e >&2; i=$((i+1)); done"
      val (out, ms) = timed(Proc.run(Seq("sh", "-c", script), tmp, 20.seconds))

      out.code shouldBe 0
      out.stdout.length shouldBe 4000 * 41
      ms should be < 15000L
    }
  }
