package workbench.git

import java.nio.file.{Files, Path}

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import workbench.agent.Proc

class GitWorktreesSpec extends AnyWordSpec with Matchers:

  private val git = GitWorktrees()

  private def sh(cwd: Path, cmd: String*): String =
    val out = Proc.run(cmd, cwd)
    withClue(s"${cmd.mkString(" ")}: ${out.stderr}")(out.ok shouldBe true)
    out.stdout

  /** A repo with one commit, and a worktree on a fresh branch off it, like a run's. */
  private def repoWithWorktree(): (Path, Path, String) =
    val repo = Files.createTempDirectory("wb-repo")
    sh(repo, "git", "init", "-q")
    Files.writeString(repo.resolve("README.md"), "hello\n")
    sh(repo, "git", "add", ".")
    sh(repo, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "base")
    val base = sh(repo, "git", "rev-parse", "HEAD").trim
    val worktree = Files.createTempDirectory("wb-wt").resolve("run")
    git.create(repo, worktree, "wb/test-v1", base) shouldBe Right(())
    (repo, worktree, base)

  "GitWorktrees" should {

    "keep an accepted run's uncommitted work on its branch after removing the worktree" in {
      val (repo, worktree, base) = repoWithWorktree()
      Files.writeString(worktree.resolve("README.md"), "changed by the agent\n")
      Files.writeString(worktree.resolve("NEW.md"), "new file\n")

      git.commitAll(worktree, "Agent work") shouldBe Right(true)
      git.remove(repo, worktree, "wb/test-v1", deleteBranch = false) shouldBe Right(())

      Files.exists(worktree) shouldBe false
      sh(repo, "git", "show", "wb/test-v1:README.md") shouldBe "changed by the agent\n"
      sh(repo, "git", "show", "wb/test-v1:NEW.md") shouldBe "new file\n"
      sh(repo, "git", "log", "-1", "--format=%s", "wb/test-v1").trim shouldBe "Agent work"
      sh(repo, "git", "rev-parse", "wb/test-v1~1").trim shouldBe base
    }

    "not create an empty commit when the agent changed nothing" in {
      val (repo, worktree, base) = repoWithWorktree()

      git.commitAll(worktree, "nothing") shouldBe Right(false)
      sh(repo, "git", "rev-parse", "wb/test-v1").trim shouldBe base
    }
  }
