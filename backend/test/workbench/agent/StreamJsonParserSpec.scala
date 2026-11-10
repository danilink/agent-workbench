package workbench.agent

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Json

class StreamJsonParserSpec extends AnyWordSpec with Matchers:

  "StreamJsonParser" should {

    "read the init event" in {
      val line = """{"type":"system","subtype":"init","session_id":"abc","model":"claude-x","cwd":"/tmp/w"}"""
      StreamJsonParser.parseLine(line) shouldBe Right(
        List(AgentEvent.Init("abc", Some("claude-x"), Some("/tmp/w")))
      )
    }

    "split an assistant message into text and tool calls, skipping thinking" in {
      val line =
        """{"type":"assistant","message":{"content":[
          |  {"type":"thinking","thinking":"hmm"},
          |  {"type":"text","text":"Reading the file"},
          |  {"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"a.scala"}}
          |]}}""".stripMargin.replace("\n", "")
      StreamJsonParser.parseLine(line) shouldBe Right(
        List(
          AgentEvent.Text("Reading the file"),
          AgentEvent.ToolUse("t1", "Read", Json.obj("file_path" -> "a.scala"))
        )
      )
    }

    "accept tool results as a string or as text blocks" in {
      val asString =
        """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}"""
      val asBlocks =
        """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t2","is_error":true,"content":[{"type":"text","text":"boom"}]}]}}"""
      StreamJsonParser.parseLine(asString) shouldBe Right(
        List(AgentEvent.ToolResult("t1", isError = false, "ok"))
      )
      StreamJsonParser.parseLine(asBlocks) shouldBe Right(
        List(AgentEvent.ToolResult("t2", isError = true, "boom"))
      )
    }

    "truncate huge tool output" in {
      val big = "x" * (StreamJsonParser.PreviewLimit + 10)
      val line = Json.stringify(
        Json.obj(
          "type" -> "user",
          "message" -> Json.obj(
            "content" -> Json.arr(
              Json.obj("type" -> "tool_result", "tool_use_id" -> "t", "content" -> big)
            )
          )
        )
      )
      val Right(List(AgentEvent.ToolResult(_, _, preview))) = StreamJsonParser.parseLine(line): @unchecked
      preview should startWith("x" * StreamJsonParser.PreviewLimit)
      preview should endWith("[10 more chars]")
    }

    "read the final result with cost and turns" in {
      val line =
        """{"type":"result","subtype":"success","is_error":false,"total_cost_usd":0.1234,"num_turns":7,"result":"Done","session_id":"abc"}"""
      StreamJsonParser.parseLine(line) shouldBe Right(
        List(
          AgentEvent.Result(Some("abc"), isError = false, Some(BigDecimal("0.1234")), Some(7), Some("Done"))
        )
      )
    }

    "treat an error result subtype without is_error as an error" in {
      val line = """{"type":"result","subtype":"error_max_turns","num_turns":40}"""
      StreamJsonParser
        .parseLine(line)
        .map(_.collect { case r: AgentEvent.Result => r.isError }) shouldBe Right(List(true))
    }

    "ignore blank lines and system noise, keep unknown types, reject non-JSON" in {
      StreamJsonParser.parseLine("   ") shouldBe Right(Nil)
      StreamJsonParser.parseLine("""{"type":"system","subtype":"api_retry","attempt":1}""") shouldBe Right(
        Nil
      )
      StreamJsonParser.parseLine("""{"type":"brand_new"}""") shouldBe Right(
        List(AgentEvent.Unknown(Json.obj("type" -> "brand_new")))
      )
      StreamJsonParser.parseLine("not json").isLeft shouldBe true
    }
  }
