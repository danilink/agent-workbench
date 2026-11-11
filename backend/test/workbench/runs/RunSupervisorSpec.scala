package workbench.runs

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration.*
import scala.concurrent.{Future, Promise}

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import workbench.agent.{RunHandle, RunOutcome}

class RunSupervisorSpec extends AnyWordSpec with Matchers:
  import RunSupervisor.{Watched, awaitWithDeadline}

  private val ok = RunOutcome(Some("s1"), isError = false, None, Some(3), Some("done"))

  private def timed[A](f: => A): (A, Long) =
    val t0 = System.nanoTime()
    val a = f
    (a, (System.nanoTime() - t0) / 1_000_000)

  "awaitWithDeadline" should {

    "return the outcome of a run that ends in time, without cancelling it" in {
      val handle = RunHandle(Future.successful(ok), () => fail("a run that ended must not be cancelled"))
      awaitWithDeadline(handle, 1.second, 1.second) shouldBe Watched.Finished(ok)
    }

    "cancel a run that outlives its deadline and keep what it reported while winding down" in {
      val outcome = Promise[RunOutcome]()
      val dying = ok.copy(isError = true, summary = Some("cancelled"))
      val cancels = AtomicInteger(0)
      val handle = RunHandle(
        outcome.future,
        () =>
          cancels.incrementAndGet(); outcome.trySuccess(dying); ()
      )

      val (watched, ms) = timed(awaitWithDeadline(handle, 200.millis, 2.seconds))

      watched shouldBe Watched.TimedOut(Some(dying))
      cancels.get shouldBe 1
      ms should (be >= 200L and be < 1500L)
    }

    "give up on a run that will not die, so its slot is still released" in {
      val handle = RunHandle(Promise[RunOutcome]().future, () => ())

      val (watched, ms) = timed(awaitWithDeadline(handle, 200.millis, 300.millis))

      watched shouldBe Watched.TimedOut(None)
      ms should be < 1500L
    }
  }
