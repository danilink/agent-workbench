package workbench.agent

import scala.util.Try

import play.api.libs.json.*

/** Parses one line of `claude -p --output-format stream-json --verbose` into zero or more [[AgentEvent]]s.
  *
  * Pure and total: unknown shapes become [[AgentEvent.Unknown]] instead of failing, because the CLI adds
  * event types over time and a run must never die on an event we don't understand yet.
  */
object StreamJsonParser:

  val PreviewLimit = 2000

  def parseLine(line: String): Either[String, List[AgentEvent]] =
    val trimmed = line.trim
    if trimmed.isEmpty then Right(Nil)
    else
      Try(Json.parse(trimmed)).toEither.left
        .map(e => s"not JSON: ${e.getMessage}")
        .map(parse)

  def parse(js: JsValue): List[AgentEvent] =
    str(js, "type") match
      case Some("system") if str(js, "subtype").contains("init") =>
        str(js, "session_id").map(id => AgentEvent.Init(id, str(js, "model"), str(js, "cwd"))).toList
      case Some("system") =>
        Nil // retries, hooks, plugin installs: noise for the timeline
      case Some("assistant") =>
        contentBlocks(js).flatMap(assistantBlock)
      case Some("user") =>
        contentBlocks(js).flatMap(userBlock)
      case Some("result") =>
        List(
          AgentEvent.Result(
            sessionId = str(js, "session_id"),
            isError = (js \ "is_error").asOpt[Boolean].getOrElse(!str(js, "subtype").contains("success")),
            costUsd = (js \ "total_cost_usd").asOpt[BigDecimal],
            numTurns = (js \ "num_turns").asOpt[Int],
            summary = str(js, "result")
          )
        )
      case Some("stream_event") =>
        Nil // token deltas; we only persist whole messages
      case _ =>
        List(AgentEvent.Unknown(js))

  private def assistantBlock(block: JsValue): List[AgentEvent] =
    str(block, "type") match
      case Some("text") =>
        str(block, "text").filter(_.trim.nonEmpty).map(AgentEvent.Text(_)).toList
      case Some("tool_use") =>
        (for
          id <- str(block, "id")
          name <- str(block, "name")
        yield AgentEvent.ToolUse(id, name, (block \ "input").asOpt[JsValue].getOrElse(JsNull))).toList
      case _ => Nil // thinking blocks etc.

  private def userBlock(block: JsValue): List[AgentEvent] =
    if str(block, "type").contains("tool_result") then
      str(block, "tool_use_id").map { id =>
        AgentEvent.ToolResult(
          toolUseId = id,
          isError = (block \ "is_error").asOpt[Boolean].getOrElse(false),
          preview = truncate(toolResultText(block \ "content"))
        )
      }.toList
    else Nil

  /** `content` is either a plain string or an array of `{type: "text", text}` blocks. */
  private def toolResultText(content: JsLookupResult): String =
    content.toOption match
      case Some(JsString(s)) => s
      case Some(JsArray(items)) =>
        items.flatMap(i => str(i, "text")).mkString("\n")
      case Some(other) => other.toString
      case None        => ""

  private def contentBlocks(js: JsValue): List[JsValue] =
    (js \ "message" \ "content").asOpt[List[JsValue]].getOrElse(Nil)

  private def str(js: JsValue, field: String): Option[String] = (js \ field).asOpt[String]

  private def truncate(s: String): String =
    if s.length <= PreviewLimit then s
    else s.take(PreviewLimit) + s"… [${s.length - PreviewLimit} more chars]"
