package workbench.agent

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.concurrent.{CompletableFuture, TimeUnit}

import scala.concurrent.duration.*

/** Minimal blocking wrapper around short-lived child processes (git, kill). */
object Proc:

  final case class Out(code: Int, stdout: String, stderr: String):
    def ok: Boolean = code == 0
    def toEither(what: String): Either[String, String] =
      if ok then Right(stdout) else Left(s"$what failed (exit $code): ${stderr.trim}")

  def run(cmd: Seq[String], cwd: Path, timeout: FiniteDuration = 60.seconds): Out =
    val p = new ProcessBuilder(cmd*).directory(cwd.toFile).start()
    p.getOutputStream.close()
    // Drain stderr concurrently so a chatty process can't deadlock on a full pipe.
    val err = CompletableFuture.supplyAsync(() => new String(p.getErrorStream.readAllBytes(), UTF_8))
    val out = new String(p.getInputStream.readAllBytes(), UTF_8)
    if p.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS) then Out(p.exitValue(), out, err.get())
    else
      p.destroyForcibly()
      Out(-1, out, s"timed out after $timeout")
