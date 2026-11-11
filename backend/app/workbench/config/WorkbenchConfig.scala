package workbench.config

import java.nio.file.{Path, Paths}

import scala.concurrent.duration.FiniteDuration

import play.api.Configuration

final case class ClaudeConfig(
    binary: String,
    permissionMode: String,
    allowedTools: Seq[String],
    maxTurns: Int,
    maxBudgetUsd: Double,
    extraArgs: Seq[String]
)

final case class WorkbenchConfig(
    repos: Seq[Path],
    worktreeRoot: Path,
    maxParallelRuns: Int,
    maxRunDuration: FiniteDuration,
    cancelGrace: FiniteDuration,
    runner: String,
    claude: ClaudeConfig
):
  /** Only explicitly allow-listed repositories can be handed to an agent. */
  def allowedRepo(raw: String): Option[Path] =
    val p = WorkbenchConfig.normalise(raw)
    repos.find(_ == p)

object WorkbenchConfig:
  def normalise(raw: String): Path = Paths.get(raw).toAbsolutePath.normalize

  def from(c: Configuration): WorkbenchConfig =
    val fromEnv = sys.env.get("WORKBENCH_REPOS").toSeq.flatMap(_.split(',')).map(_.trim).filter(_.nonEmpty)
    WorkbenchConfig(
      repos = (c.get[Seq[String]]("workbench.repos") ++ fromEnv).map(normalise).distinct,
      worktreeRoot = normalise(c.get[String]("workbench.worktree-root")),
      maxParallelRuns = c.get[Int]("workbench.max-parallel-runs"),
      maxRunDuration = c.get[FiniteDuration]("workbench.max-run-duration"),
      cancelGrace = c.get[FiniteDuration]("workbench.cancel-grace"),
      runner = c.get[String]("workbench.runner"),
      claude = ClaudeConfig(
        binary = c.get[String]("workbench.claude.binary"),
        permissionMode = c.get[String]("workbench.claude.permission-mode"),
        allowedTools = c.get[Seq[String]]("workbench.claude.allowed-tools"),
        maxTurns = c.get[Int]("workbench.claude.max-turns"),
        maxBudgetUsd = c.get[Double]("workbench.claude.max-budget-usd"),
        extraArgs = c.get[Seq[String]]("workbench.claude.extra-args")
      )
    )
