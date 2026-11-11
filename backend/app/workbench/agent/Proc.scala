package workbench.agent

import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.concurrent.{CompletableFuture, Executors, TimeUnit}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Minimal blocking wrapper around child processes that are expected to end (git, kill, checks). */
object Proc:

  final case class Out(code: Int, stdout: String, stderr: String):
    def ok: Boolean = code == 0
    def toEither(what: String): Either[String, String] =
      if ok then Right(stdout) else Left(s"$what failed (exit $code): ${stderr.trim}")

  // Readers block on pipes for as long as a process lives, so they get their own threads rather than the
  // common pool or a dispatcher.
  private val readers = Executors.newCachedThreadPool { r =>
    val t = Thread(r, "proc-reader")
    t.setDaemon(true)
    t
  }

  // After the process has exited, how long to wait for its pipes to close. A grandchild that inherited them can
  // keep them open indefinitely.
  private val drainGrace = 5.seconds

  /** Runs `cmd` and returns within roughly `timeout`. On timeout the process and everything it started is
    * killed, and the result is exit -1. `onStart` receives the process, so a caller can kill it early.
    */
  def run(
      cmd: Seq[String],
      cwd: Path,
      timeout: FiniteDuration = 60.seconds,
      onStart: Process => Unit = _ => ()
  ): Out =
    val p = new ProcessBuilder(cmd*).directory(cwd.toFile).start()
    p.getOutputStream.close()
    onStart(p)
    // Drain both streams concurrently, and wait on the process rather than on EOF: reading one stream to EOF first
    // can deadlock on the other's full pipe, and EOF only comes when the process exits, so it would also ignore
    // the timeout.
    val out = readAll(p.getInputStream)
    val err = readAll(p.getErrorStream)
    if p.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS) then Out(p.exitValue(), drained(out), drained(err))
    else
      killTree(p)
      Out(-1, drained(out), s"timed out after $timeout")

  /** Force-kills `p` and every process it started. `known` adds descendants captured earlier, before `p`
    * could exit and orphan them.
    */
  def killTree(p: Process, known: List[ProcessHandle] = Nil): Unit =
    (known ++ descendants(p)).distinct.filter(_.isAlive).foreach(_.destroyForcibly())
    if p.isAlive then p.destroyForcibly()
    ()

  def descendants(p: Process): List[ProcessHandle] = p.descendants().iterator().asScala.toList

  private def readAll(in: InputStream): CompletableFuture[String] =
    CompletableFuture.supplyAsync(() => new String(in.readAllBytes(), UTF_8), readers)

  private def drained(f: CompletableFuture[String]): String =
    Try(f.get(drainGrace.toMillis, TimeUnit.MILLISECONDS)).getOrElse("")
