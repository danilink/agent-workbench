package workbench.config

import javax.inject.{Inject, Singleton}

import org.apache.pekko.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext

/** For blocking JDBC calls. */
@Singleton
final class DatabaseExecutionContext @Inject() (system: ActorSystem)
    extends CustomExecutionContext(system, "database.dispatcher")

/** For long-lived, blocking agent sessions (process I/O). */
@Singleton
final class AgentRunsExecutionContext @Inject() (system: ActorSystem)
    extends CustomExecutionContext(system, "agent-runs-dispatcher")
