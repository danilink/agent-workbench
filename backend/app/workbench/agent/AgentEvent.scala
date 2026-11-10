package workbench.agent

import play.api.libs.json.*

import workbench.domain.EventKind

/** What we keep from Claude Code's `stream-json` output, independent of the CLI's wire format. */
enum AgentEvent:
  case Init(sessionId: String, model: Option[String], cwd: Option[String])
  case Text(text: String)
  case ToolUse(id: String, name: String, input: JsValue)
  case ToolResult(toolUseId: String, isError: Boolean, preview: String)
  case Result(
      sessionId: Option[String],
      isError: Boolean,
      costUsd: Option[BigDecimal],
      numTurns: Option[Int],
      summary: Option[String]
  )
  case Log(stream: String, line: String)
  case Unknown(raw: JsValue)

object AgentEvent:

  /** Normalised (kind, payload) pair that is persisted and pushed to the UI. */
  def toRecord(e: AgentEvent): (EventKind, JsObject) = e match
    case Init(sessionId, model, cwd) =>
      EventKind.Init -> Json.obj("sessionId" -> sessionId, "model" -> model, "cwd" -> cwd)
    case Text(text) =>
      EventKind.Text -> Json.obj("text" -> text)
    case ToolUse(id, name, input) =>
      EventKind.ToolUse -> Json.obj("id" -> id, "name" -> name, "input" -> input)
    case ToolResult(toolUseId, isError, preview) =>
      EventKind.ToolResult -> Json.obj("toolUseId" -> toolUseId, "isError" -> isError, "preview" -> preview)
    case Result(sessionId, isError, cost, turns, summary) =>
      EventKind.Result -> Json.obj(
        "sessionId" -> sessionId,
        "isError" -> isError,
        "costUsd" -> cost,
        "numTurns" -> turns,
        "summary" -> summary
      )
    case Log(stream, line) =>
      EventKind.Log -> Json.obj("stream" -> stream, "line" -> line)
    case Unknown(raw) =>
      EventKind.Unknown -> Json.obj("raw" -> raw)
