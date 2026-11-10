import javax.inject.Singleton

import com.google.inject.{AbstractModule, Provides}
import play.api.Configuration

import workbench.agent.{AgentRunner, ClaudeCliRunner, FakeRunner}
import workbench.config.WorkbenchConfig

/** Picked up automatically by Play (root package, named `Module`). */
class Module extends AbstractModule:

  override def configure(): Unit = ()

  @Provides @Singleton
  def workbenchConfig(c: Configuration): WorkbenchConfig = WorkbenchConfig.from(c)

  @Provides @Singleton
  def agentRunner(cfg: WorkbenchConfig, claude: ClaudeCliRunner, fake: FakeRunner): AgentRunner =
    cfg.runner match
      case "fake" => fake
      case _      => claude
