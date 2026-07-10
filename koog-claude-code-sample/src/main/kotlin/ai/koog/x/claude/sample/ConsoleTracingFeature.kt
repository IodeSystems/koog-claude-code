package ai.koog.x.claude.sample

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentStorageKey
import ai.koog.agents.core.feature.AIAgentGraphFeature
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline
import ai.koog.serialization.KotlinClassToken

class ConsoleTracingConfig : FeatureConfig()

object ConsoleTracingFeature : AIAgentGraphFeature<ConsoleTracingConfig, ConsoleTracingFeature> {
    override val key = AIAgentStorageKey<ConsoleTracingFeature>(
        "ConsoleTracing",
        KotlinClassToken(ConsoleTracingFeature::class)
    )

    override fun createInitialConfig(agentConfig: AIAgentConfig) = ConsoleTracingConfig()

    override fun install(
        config: ConsoleTracingConfig,
        pipeline: AIAgentGraphPipeline
    ): ConsoleTracingFeature {
        pipeline.interceptLLMCallStarting(this) { ctx ->
            println("  ⟶ LLM call...")
        }

        pipeline.interceptLLMCallCompleted(this) { ctx ->
            ctx.response?.parts?.forEach { responsePart ->
                println("  ⟵ LLM: $responsePart")
            }
        }

        pipeline.interceptToolCallStarting(this) { ctx ->
            println("  ⚙ Tool call: ${ctx.toolName}(${ctx.toolArgs})")
        }

        pipeline.interceptToolCallCompleted(this) { ctx ->
            val resultStr = ctx.toolResult.toString()
            val truncated = if (resultStr.length > 200) resultStr.take(200) + "…" else resultStr
            println("  ✓ Tool result: $truncated")
        }

        pipeline.interceptToolCallFailed(this) { ctx ->
            println("  ✗ Tool failed: ${ctx.toolName} — ${ctx.message}")
        }

        pipeline.interceptToolValidationFailed(this) { ctx ->
            println("  ✗ Tool validation failed: ${ctx.toolName} — ${ctx.message}")
        }

        pipeline.interceptNodeExecutionStarting(this) { ctx ->
            println("  ▸ Node: ${ctx.node.name}")
        }

        return this
    }
}
