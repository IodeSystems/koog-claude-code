package ai.koog.x.claude

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

object ClaudeCodeProvider : LLMProvider("claude-code", "Claude Code") {
    override fun toString(): String = "ClaudeCodeProvider"
}

object ClaudeCodeModels {
    val Claude4_5Sonnet = LLModel(
        provider = ClaudeCodeProvider,
        id = "claude-sonnet-4-5-20250514",
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Tools,
            LLMCapability.Temperature,
        ),
        contextLength = 200_000L,
        maxOutputTokens = 16_384L,
    )

    val Claude4Opus = LLModel(
        provider = ClaudeCodeProvider,
        id = "claude-opus-4-20250514",
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Tools,
            LLMCapability.Temperature,
        ),
        contextLength = 200_000L,
        maxOutputTokens = 16_384L,
    )

    val Claude3_5Haiku = LLModel(
        provider = ClaudeCodeProvider,
        id = "claude-haiku-4-5-20251001",
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Tools,
            LLMCapability.Temperature,
        ),
        contextLength = 200_000L,
        maxOutputTokens = 8_192L,
    )

    val all = listOf(Claude4_5Sonnet, Claude4Opus, Claude3_5Haiku)
}
