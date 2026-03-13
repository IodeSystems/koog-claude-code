package ai.koog.x.claude.sample

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.x.claude.ClaudeCodeLLMClient
import ai.koog.x.claude.ClaudeCodeModels
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking

@LLMDescription("Execute a bash command and return its output")
class BashTools : ToolSet {
    @Tool
    @LLMDescription("Run a bash command. Returns stdout. Use for file listing, searching, calculations, etc.")
    fun bash(
        @LLMDescription("The bash command to execute") command: String
    ): String {
        return try {
            val process = ProcessBuilder("bash", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                "Exit code: $exitCode\n$output"
            } else {
                output
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }
}

class ClaudeCodeChat : SuspendingCliktCommand(name = "claude-code-chat") {
    private val promptArg by option("--prompt", "-p", help = "Run a single prompt and exit")
    private val model by option("--model", "-m", help = "Model to use")
        .default("claude-sonnet-4-5-20250514")
    private val dir by option("--dir", "-d", help = "Working directory context")

    override suspend fun run() {
        echo("Initializing Claude Code LLM client...")
        val client = ClaudeCodeLLMClient()
        client.initialize()

        val executor = SingleLLMPromptExecutor(client)
        val llmModel = ClaudeCodeModels.all.find { it.id == model } ?: ClaudeCodeModels.Claude4_5Sonnet

        echo("Using model: ${llmModel.id}")

        val bashTools = BashTools()
        val toolRegistry = ToolRegistry {
            tools(bashTools)
        }

        val systemPrompt = buildString {
            appendLine("You are a helpful assistant with access to bash via the bash tool.")
            appendLine("Use the bash tool to execute commands when the user asks you to compute, transform, or query data.")
            if (dir != null) {
                appendLine("Working directory context: $dir")
            }
        }

        suspend fun runPrompt(userInput: String) {
            val agent = AIAgent(
                promptExecutor = executor,
                strategy = singleRunStrategy(),
                agentConfig = AIAgentConfig(
                    prompt = prompt("chat") {
                        system(systemPrompt)
                    },
                    model = llmModel,
                    maxAgentIterations = 20
                ),
                toolRegistry = toolRegistry
            ) {
                install(ConsoleTracingFeature)
            }
            val result = agent.run(userInput)
            echo()
            echo("llm> $result")
        }

        try {
            if (promptArg != null) {
                try {
                    runPrompt(promptArg!!)
                } catch (e: Exception) {
                    echo("Error: ${e.message}", err = true)
                }
                return
            }

            echo()
            echo("claude-code-chat (type 'quit' to exit)")
            echo("─".repeat(50))

            while (true) {
                echo()
                print("you> ")
                val userInput = readlnOrNull()?.trim() ?: break
                if (userInput.equals("quit", ignoreCase = true) || userInput.equals("exit", ignoreCase = true)) {
                    break
                }
                if (userInput.isEmpty()) continue

                try {
                    runPrompt(userInput)
                } catch (e: Exception) {
                    echo()
                    echo("Error: ${e.message}", err = true)
                }
            }

            echo("Goodbye.")
        } finally {
            client.close()
        }
    }
}

fun main(args: Array<String>) = runBlocking {
    ClaudeCodeChat().main(args)
}
