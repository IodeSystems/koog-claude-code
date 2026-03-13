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
import com.iodesystems.tshell.TShell
import com.iodesystems.tshell.toolkit.CoreToolkit
import com.iodesystems.tshell.toolkit.FileToolkit
import com.iodesystems.tshell.toolkit.MathToolkit
import kotlinx.coroutines.runBlocking
import java.io.File

class TShellTools(private val shell: TShell) : ToolSet {
    @Tool
    @LLMDescription(TShell.TOOL_DESCRIPTION)
    fun tshell(
        @LLMDescription("tshell source code") code: String
    ): String {
        return try {
            shell.evalExported(code).toDisplayString()
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

        val workingDir = dir?.let { File(it) } ?: File(System.getProperty("user.dir"))
        val shell = TShell()
        CoreToolkit.install(shell)
        MathToolkit().install(shell)
        FileToolkit(workingDir.toPath(), readOnly = true).install(shell)

        val tshellTools = TShellTools(shell)
        val toolRegistry = ToolRegistry {
            tools(tshellTools)
        }

        val systemPrompt = buildString {
            appendLine("You are a helpful assistant with access to tshell.")
            appendLine("Use the tshell tool to execute code when the user asks you to compute, transform, or query data.")
            appendLine()
            appendLine(shell.toPrompt())
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
