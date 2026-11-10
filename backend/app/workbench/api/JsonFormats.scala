package workbench.api

import play.api.libs.json.*

import workbench.domain.*

final case class CreateTaskRequest(
    repoPath: String,
    prompt: String,
    baseRef: Option[String],
    variants: Option[Int]
)

object JsonFormats:
  given Writes[TaskId] = Writes(id => JsString(id.value.toString))
  given Writes[RunId] = Writes(id => JsString(id.value.toString))
  given Writes[RunStatus] = Writes(s => JsString(s.toString))
  given Writes[ReviewState] = Writes(s => JsString(s.toString))
  given Writes[EventKind] = Writes(k => JsString(k.toString))

  given Writes[Task] = Json.writes[Task]
  given Writes[Run] = Json.writes[Run]
  given Writes[RunEvent] = Json.writes[RunEvent]
  given Writes[TaskDetail] = Json.writes[TaskDetail]

  given Reads[CreateTaskRequest] = Json.reads[CreateTaskRequest]

  def apiError(message: String): JsObject = Json.obj("error" -> message)
