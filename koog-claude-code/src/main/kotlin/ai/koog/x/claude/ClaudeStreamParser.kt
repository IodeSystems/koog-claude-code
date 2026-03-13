package ai.koog.x.claude

import ai.koog.x.claude.model.ClaudeStreamEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.*

private val logger = KotlinLogging.logger {}

object ClaudeStreamParser {

    fun parse(line: String): ClaudeStreamEvent? {
        if (line.isBlank()) return null
        val json = try {
            Json.parseToJsonElement(line).jsonObject
        } catch (e: Exception) {
            logger.warn { "Failed to parse JSON line: $line" }
            return null
        }

        val type = json["type"]?.jsonPrimitive?.contentOrNull ?: return null

        return when (type) {
            "system" -> parseSystem(json)
            "assistant" -> parseAssistantMessage(json)
            "message_start" -> parseMessageStart(json)
            "content_block_start" -> parseContentBlockStart(json)
            "content_block_delta" -> parseContentBlockDelta(json)
            "content_block_stop" -> parseContentBlockStop(json)
            "message_delta" -> parseMessageDelta(json)
            "message_stop" -> ClaudeStreamEvent.MessageStop
            "result" -> parseResult(json)
            "error" -> parseError(json)
            "rate_limit_event", "user" -> null
            else -> ClaudeStreamEvent.Unknown(type, line)
        }
    }

    private fun parseSystem(json: JsonObject): ClaudeStreamEvent {
        val sessionId = json["session_id"]?.jsonPrimitive?.contentOrNull ?: ""
        return ClaudeStreamEvent.SystemInit(sessionId)
    }

    private fun parseAssistantMessage(json: JsonObject): ClaudeStreamEvent {
        val message = json["message"]?.jsonObject
            ?: return ClaudeStreamEvent.Unknown("assistant", json.toString())
        val content = message["content"]?.jsonArray
            ?: return ClaudeStreamEvent.Unknown("assistant", json.toString())
        val usage = message["usage"]?.jsonObject

        val textParts = mutableListOf<String>()
        val toolCalls = mutableListOf<ClaudeStreamEvent.ToolUseBlock>()

        for (block in content) {
            val blockObj = block.jsonObject
            when (blockObj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> {
                    blockObj["text"]?.jsonPrimitive?.contentOrNull?.let { textParts.add(it) }
                }
                "tool_use" -> {
                    toolCalls.add(
                        ClaudeStreamEvent.ToolUseBlock(
                            id = blockObj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                            name = blockObj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                            input = blockObj["input"]?.jsonObject?.toString() ?: "{}",
                        )
                    )
                }
            }
        }

        return ClaudeStreamEvent.AssistantMessage(
            text = textParts.joinToString(""),
            toolCalls = toolCalls,
            inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull,
            outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull,
            cacheReadInputTokens = usage?.get("cache_read_input_tokens")?.jsonPrimitive?.intOrNull,
        )
    }

    private fun parseMessageStart(json: JsonObject): ClaudeStreamEvent {
        val message = json["message"]?.jsonObject
        val usage = message?.get("usage")?.jsonObject
        return ClaudeStreamEvent.MessageStart(
            messageId = message?.get("id")?.jsonPrimitive?.contentOrNull ?: "",
            role = message?.get("role")?.jsonPrimitive?.contentOrNull ?: "assistant",
            inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull,
            outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull,
            cacheReadInputTokens = usage?.get("cache_read_input_tokens")?.jsonPrimitive?.intOrNull,
            cacheCreationInputTokens = usage?.get("cache_creation_input_tokens")?.jsonPrimitive?.intOrNull,
        )
    }

    private fun parseContentBlockStart(json: JsonObject): ClaudeStreamEvent {
        val index = json["index"]?.jsonPrimitive?.intOrNull ?: 0
        val block = json["content_block"]?.jsonObject
        return ClaudeStreamEvent.ContentBlockStart(
            index = index,
            type = block?.get("type")?.jsonPrimitive?.contentOrNull ?: "text",
            id = block?.get("id")?.jsonPrimitive?.contentOrNull,
            name = block?.get("name")?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun parseContentBlockDelta(json: JsonObject): ClaudeStreamEvent {
        val index = json["index"]?.jsonPrimitive?.intOrNull ?: 0
        val delta = json["delta"]?.jsonObject
        val deltaType = delta?.get("type")?.jsonPrimitive?.contentOrNull ?: "text_delta"
        return ClaudeStreamEvent.ContentBlockDelta(
            index = index,
            deltaType = deltaType,
            text = delta?.get("text")?.jsonPrimitive?.contentOrNull,
            thinking = delta?.get("thinking")?.jsonPrimitive?.contentOrNull,
            inputJson = delta?.get("partial_json")?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun parseContentBlockStop(json: JsonObject): ClaudeStreamEvent {
        return ClaudeStreamEvent.ContentBlockStop(
            index = json["index"]?.jsonPrimitive?.intOrNull ?: 0
        )
    }

    private fun parseMessageDelta(json: JsonObject): ClaudeStreamEvent {
        val delta = json["delta"]?.jsonObject
        val usage = json["usage"]?.jsonObject
        return ClaudeStreamEvent.MessageDelta(
            stopReason = delta?.get("stop_reason")?.jsonPrimitive?.contentOrNull,
            outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull,
        )
    }

    private fun parseResult(json: JsonObject): ClaudeStreamEvent {
        return ClaudeStreamEvent.Result(
            result = json["result"]?.jsonPrimitive?.contentOrNull,
            isError = json["is_error"]?.jsonPrimitive?.booleanOrNull ?: false,
            durationMs = json["duration_ms"]?.jsonPrimitive?.longOrNull,
        )
    }

    private fun parseError(json: JsonObject): ClaudeStreamEvent {
        return ClaudeStreamEvent.Error(
            message = json["error"]?.jsonPrimitive?.contentOrNull
                ?: json["message"]?.jsonPrimitive?.contentOrNull
                ?: "Unknown error",
            subtype = json["subtype"]?.jsonPrimitive?.contentOrNull,
        )
    }
}
