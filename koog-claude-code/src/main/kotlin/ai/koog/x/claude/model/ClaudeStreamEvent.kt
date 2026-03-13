package ai.koog.x.claude.model

sealed class ClaudeStreamEvent {
    data class SystemInit(val sessionId: String) : ClaudeStreamEvent()

    data class ToolUseBlock(
        val id: String,
        val name: String,
        val input: String,
    )

    data class AssistantMessage(
        val text: String,
        val toolCalls: List<ToolUseBlock>,
        val inputTokens: Int?,
        val outputTokens: Int?,
        val cacheReadInputTokens: Int?,
    ) : ClaudeStreamEvent()

    data class MessageStart(
        val messageId: String,
        val role: String,
        val inputTokens: Int?,
        val outputTokens: Int?,
        val cacheReadInputTokens: Int?,
        val cacheCreationInputTokens: Int?,
    ) : ClaudeStreamEvent()

    data class ContentBlockStart(
        val index: Int,
        val type: String,
        val id: String? = null,
        val name: String? = null,
    ) : ClaudeStreamEvent()

    data class ContentBlockDelta(
        val index: Int,
        val deltaType: String,
        val text: String? = null,
        val thinking: String? = null,
        val inputJson: String? = null,
    ) : ClaudeStreamEvent()

    data class ContentBlockStop(val index: Int) : ClaudeStreamEvent()

    data class MessageDelta(
        val stopReason: String?,
        val outputTokens: Int?,
    ) : ClaudeStreamEvent()

    data object MessageStop : ClaudeStreamEvent()

    data class Result(
        val result: String?,
        val isError: Boolean,
        val durationMs: Long?,
    ) : ClaudeStreamEvent()

    data class Error(val message: String, val subtype: String? = null) : ClaudeStreamEvent()

    data class Unknown(val type: String, val raw: String) : ClaudeStreamEvent()
}
