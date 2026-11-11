package workbench.git

import java.nio.file.{Files, Path}
import javax.inject.Singleton

import workbench.agent.Proc

/** Each run gets its own worktree + branch so N agents can edit the same repo in parallel without stepping on
  * each other, and every result is a reviewable branch.
  */
@Singleton
final class GitWorktrees:

  def resolveSha(repo: Path, ref: String): Either[String, String] =
    git(repo, "rev-parse", "--verify", s"$ref^{commit}").map(_.trim)

  def create(repo: Path, dir: Path, branch: String, baseSha: String): Either[String, Unit] =
    Files.createDirectories(dir.getParent)
    git(repo, "worktree", "add", "-b", branch, dir.toString, baseSha).map(_ => ())

  /** Everything the agent changed relative to the base commit: commits, staged, unstaged and new files. */
  def diff(worktree: Path, baseSha: String): Either[String, String] =
    for
      _ <- git(worktree, "add", "--intent-to-add", "--all")
      d <- git(worktree, "diff", "--no-color", "--no-ext-diff", baseSha)
    yield d

  /** Commits everything left in the worktree (agents rarely commit) onto its branch, so removing the checkout
    * cannot lose work. Returns false when there was nothing to commit.
    */
  def commitAll(worktree: Path, message: String): Either[String, Boolean] =
    for
      _ <- git(worktree, "add", "--all")
      status <- git(worktree, "status", "--porcelain")
      committed <-
        if status.trim.isEmpty then Right(false)
        else git(worktree, identity(worktree) ++ Seq("commit", "--no-verify", "-m", message)*).map(_ => true)
    yield committed

  // Use the user's git identity; fall back to a neutral one where none is configured (CI, fresh machines),
  // since `git commit` refuses to run without one.
  private def identity(worktree: Path): Seq[String] =
    if git(worktree, "config", "user.email").exists(_.trim.nonEmpty) then Nil
    else Seq("-c", "user.name=Agent Workbench", "-c", "user.email=agent-workbench@localhost")

  /** Removes the checkout. The branch is kept unless `deleteBranch`, so accepted work can be merged later. */
  def remove(repo: Path, worktree: Path, branch: String, deleteBranch: Boolean): Either[String, Unit] =
    val removed =
      if Files.exists(worktree) then
        git(repo, "worktree", "remove", "--force", worktree.toString).map(_ => ())
      else git(repo, "worktree", "prune").map(_ => ())
    removed.flatMap(_ => if deleteBranch then git(repo, "branch", "-D", branch).map(_ => ()) else Right(()))

  private def git(cwd: Path, args: String*): Either[String, String] =
    Proc.run("git" +: args, cwd).toEither(s"git ${args.headOption.getOrElse("")}")
