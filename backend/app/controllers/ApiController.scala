package controllers

import java.nio.file.Paths
import java.time.Instant
import javax.inject.{Inject, Singleton}

import scala.concurrent.Future
import scala.concurrent.duration.*

import org.apache.pekko.stream.scaladsl.Source
import play.api.http.ContentTypes
import play.api.libs.EventSource
import play.api.libs.json.*
import play.api.mvc.*

import workbench.api.CreateTaskRequest
import workbench.api.JsonFormats.{*, given}
import workbench.config.{DatabaseExecutionContext, WorkbenchConfig}
import workbench.domain.*
import workbench.events.{BusMessage, EventBus}
import workbench.git.GitWorktrees
import workbench.runs.RunSupervisor
import workbench.store.WorkbenchRepo

@Singleton
final class ApiController @Inject() (
    cc: ControllerComponents,
    cfg: WorkbenchConfig,
    repo: WorkbenchRepo,
    git: GitWorktrees,
    supervisor: RunSupervisor,
    bus: EventBus,
    dbEc: DatabaseExecutionContext
) extends AbstractController(cc):

  private given DatabaseExecutionContext = dbEc

  val MaxVariants = 4

  def health: Action[AnyContent] = Action(Ok(Json.obj("status" -> "ok")))

  def config: Action[AnyContent] = Action {
    Ok(
      Json.obj(
        "repos"           -> cfg.repos.map(_.toString),
        "runner"          -> cfg.runner,
        "maxParallelRuns" -> cfg.maxParallelRuns,
        "maxVariants"     -> MaxVariants
      )
    )
  }

  def listTasks: Action[AnyContent] = Action.async {
    Future(repo.listTasks(50).map(t => TaskDetail(t, repo.runsForTask(t.id)))).map(ts => Ok(Json.toJson(ts)))
  }

  def createTask: Action[JsValue] = Action(parse.json).async { req =>
    req.body.validate[CreateTaskRequest] match
      case e: JsError => Future.successful(BadRequest(JsError.toJson(e)))
      case JsSuccess(body, _) =>
        Future {
          val variants = body.variants.getOrElse(1)
          val baseRef  = body.baseRef.map(_.trim).filter(_.nonEmpty).getOrElse("HEAD")
          val created = for
            repoPath <- cfg.allowedRepo(body.repoPath).toRight(s"repo not allow-listed: ${body.repoPath}")
            prompt   <- Some(body.prompt.trim).filter(_.nonEmpty).toRight("prompt is empty")
            _        <- Either.cond(1 <= variants && variants <= MaxVariants, (), s"variants must be 1..$MaxVariants")
            sha      <- git.resolveSha(repoPath, baseRef)
          yield
            val task = Task(TaskId.random(), repoPath.toString, baseRef, sha, prompt, Instant.now())
            repo.insertTask(task)
            TaskDetail(task, supervisor.submit(task, variants))
          created.fold(e => BadRequest(apiError(e)), d => Created(Json.toJson(d)))
        }
  }

  def getTask(id: String): Action[AnyContent] = Action.async {
    withId(TaskId.parse(id)) { taskId =>
      repo.getTask(taskId).map(t => TaskDetail(t, repo.runsForTask(taskId)))
    }
  }

  def getRun(id: String): Action[AnyContent] = Action.async {
    withId(RunId.parse(id))(repo.getRun)
  }

  def runEvents(id: String, after: Option[Int]): Action[AnyContent] = Action.async {
    withId(RunId.parse(id))(runId => repo.getRun(runId).map(_ => repo.events(runId, after.getOrElse(0))))
  }

  def runDiff(id: String): Action[AnyContent] = Action.async {
    RunId.parse(id) match
      case None => Future.successful(BadRequest(apiError("invalid id")))
      case Some(runId) =>
        Future {
          val diff = for
            run  <- repo.getRun(runId).toRight("run not found")
            task <- repo.getTask(run.taskId).toRight("task not found")
            _    <- Either.cond(run.review == ReviewState.Pending, (), "worktree already removed")
            d    <- git.diff(Paths.get(run.worktreePath), task.baseSha)
          yield d
          diff.fold(e => NotFound(apiError(e)), d => Ok(d).as(ContentTypes.TEXT))
        }
  }

  def cancelRun(id: String): Action[AnyContent]  = runCommand(id)(supervisor.cancel)
  def acceptRun(id: String): Action[AnyContent]  = runCommand(id)(supervisor.accept)
  def discardRun(id: String): Action[AnyContent] = runCommand(id)(supervisor.discard)

  /** One SSE stream for everything; the UI filters client-side. `event:` is the message type. */
  def stream: Action[AnyContent] = Action {
    val events: Source[EventSource.Event, ?] = bus.subscribe
      .map {
        case BusMessage.RunUpdated(run)       => EventSource.Event(Json.stringify(Json.toJson(run)), None, Some("run"))
        case BusMessage.EventAppended(event) => EventSource.Event(Json.stringify(Json.toJson(event)), None, Some("run-event"))
      }
      .keepAlive(15.seconds, () => EventSource.Event("{}", None, Some("ping")))
    Ok.chunked(events).as(ContentTypes.EVENT_STREAM)
  }

  // --- helpers ----------------------------------------------------------------

  private def withId[I, A: Writes](id: Option[I])(load: I => Option[A]): Future[Result] =
    id match
      case None => Future.successful(BadRequest(apiError("invalid id")))
      case Some(i) =>
        Future(load(i)).map {
          case Some(a) => Ok(Json.toJson(a))
          case None    => NotFound(apiError("not found"))
        }

  private def runCommand(id: String)(cmd: RunId => Either[String, Run]): Action[AnyContent] = Action.async {
    RunId.parse(id) match
      case None => Future.successful(BadRequest(apiError("invalid id")))
      case Some(runId) =>
        Future(cmd(runId)).map(_.fold(e => Conflict(apiError(e)), r => Ok(Json.toJson(r))))
  }
