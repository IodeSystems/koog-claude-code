package ai.koog.x.claude

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.x.claude.model.ClaudeStreamEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.*

private val logger = KotlinLogging.logger {}

class ClaudeCodeLLMClient(
    private val claudeBinary: String = "claude",
    private val externalMcpConfigFile: java.io.File? = null,
    private val allowedTools: String = "mcp__koog__*",
) : LLMClient {
    private val toolBridge = McpToolBridge()
    private val mcpServer = McpBridgeServer(toolBridge)
    private val conversationTracker = ConversationTracker()
    private var claudeProcess: ClaudeProcess? = null
    private var stdoutReaderJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val completedResponses = Channel<CompletedTurn>(Channel.BUFFERED)

    // Streaming: emits frames as they arrive from claude's partial output
    private val streamingFrames = Channel<StreamFrame>(Channel.BUFFERED)

    private sealed class CompletedTurn {
        data class Text(val text: String, val inputTokens: Int?, val outputTokens: Int?) : CompletedTurn()
        data class ToolUse(val calls: List<ToolCallRequest>) : CompletedTurn()
        data class ResultEvent(val text: String?, val isError: Boolean) : CompletedTurn()
        data class Error(val message: String) : CompletedTurn()
        data object ProcessDied : CompletedTurn()
    }

    // Track content block types by index for streaming
    private val activeBlockTypes = mutableMapOf<Int, String>()
    private val activeBlockIds = mutableMapOf<Int, String>()
    private val activeBlockNames = mutableMapOf<Int, String>()

    suspend fun initialize() {
        if (externalMcpConfigFile == null) {
            mcpServer.start()
        }
        startClaudeProcess()
    }

    private fun startClaudeProcess() {
        val configFile = externalMcpConfigFile ?: mcpServer.generateConfigFile()
        claudeProcess = ClaudeProcess(configFile, claudeBinary, allowedTools).also { it.start() }
        startStdoutReader()
    }

    private fun startStdoutReader() {
        stdoutReaderJob?.cancel()
        stdoutReaderJob = scope.launch {
            try {
                val process = claudeProcess ?: return@launch
                for (line in process.readLines()) {
                    if (!isActive) break
                    val event = ClaudeStreamParser.parse(line) ?: continue
                    handleStreamEvent(event)
                }
                completedResponses.send(CompletedTurn.ProcessDied)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Stdout reader error" }
                completedResponses.send(CompletedTurn.ProcessDied)
            }
        }
    }

    private suspend fun handleStreamEvent(event: ClaudeStreamEvent) {
        when (event) {
            is ClaudeStreamEvent.SystemInit -> {
                logger.info { "Claude session: ${event.sessionId}" }
            }

            is ClaudeStreamEvent.AssistantMessage -> {
                if (event.toolCalls.isNotEmpty()) {
                    // Tool calls are handled via MCP bridge, not here.
                    // Claude will call our MCP server which routes through pendingCall channel.
                    // This event is informational — we log it but don't emit a turn.
                    logger.debug { "Assistant message with ${event.toolCalls.size} tool calls (handled via MCP)" }
                } else if (event.text.isNotEmpty()) {
                    completedResponses.send(
                        CompletedTurn.Text(event.text, event.inputTokens, event.outputTokens)
                    )
                }
            }

            is ClaudeStreamEvent.Result -> {
                completedResponses.send(
                    CompletedTurn.ResultEvent(event.result, event.isError)
                )
            }

            is ClaudeStreamEvent.Error -> {
                completedResponses.send(CompletedTurn.Error(event.message))
            }

            is ClaudeStreamEvent.ContentBlockStart -> {
                activeBlockTypes[event.index] = event.type
                event.id?.let { activeBlockIds[event.index] = it }
                event.name?.let { activeBlockNames[event.index] = it }
            }

            is ClaudeStreamEvent.ContentBlockDelta -> {
                val blockType = activeBlockTypes[event.index]
                when {
                    event.text != null && (blockType == "text" || event.deltaType == "text_delta") -> {
                        streamingFrames.send(StreamFrame.TextDelta(event.text, event.index))
                    }
                    event.inputJson != null && (blockType == "tool_use" || event.deltaType == "input_json_delta") -> {
                        val id = activeBlockIds[event.index]
                        val name = activeBlockNames[event.index]
                        streamingFrames.send(StreamFrame.ToolCallDelta(id, name, event.inputJson, event.index))
                    }
                    event.thinking != null -> {
                        streamingFrames.send(StreamFrame.ReasoningDelta(event.thinking, index = event.index))
                    }
                }
            }

            is ClaudeStreamEvent.ContentBlockStop -> {
                val blockType = activeBlockTypes.remove(event.index)
                val id = activeBlockIds.remove(event.index)
                val name = activeBlockNames.remove(event.index)
                // TextComplete and ToolCallComplete are emitted when the full response
                // arrives via AssistantMessage — we don't need to emit them here
                logger.debug { "Content block $event.index ($blockType) stopped" }
            }

            is ClaudeStreamEvent.MessageStart -> {
                activeBlockTypes.clear()
                activeBlockIds.clear()
                activeBlockNames.clear()
            }

            is ClaudeStreamEvent.MessageDelta -> {
                val meta = createResponseMetaInfo(null, event.outputTokens)
                streamingFrames.send(StreamFrame.End(event.stopReason, meta))
            }

            is ClaudeStreamEvent.MessageStop -> {
                // No action needed
            }

            is ClaudeStreamEvent.Unknown -> {
                logger.debug { "Unknown event type: ${event.type}" }
            }
        }
    }

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): List<Message.Response> {
        ensureProcessAlive()
        if (externalMcpConfigFile == null) {
            mcpServer.updateTools(tools)
        }

        val newMessages = conversationTracker.getNewMessages(prompt)
        if (newMessages.isEmpty()) {
            return emptyList()
        }

        val userContent = buildUserContent(newMessages)
        if (userContent != null) {
            // Drain any stale streaming frames before sending
            while (streamingFrames.tryReceive().isSuccess) { /* discard */ }
            claudeProcess!!.sendMessage(userContent)
        } else {
            // Last message is a tool result — send it via the bridge
            val toolResults = newMessages.filterIsInstance<Message.Tool.Result>()
            for (result in toolResults) {
                toolBridge.pendingResult.send(
                    ToolCallResult(
                        id = result.id ?: "",
                        result = result.content ?: "",
                    )
                )
            }
        }

        return waitForResponse()
    }

    private suspend fun waitForResponse(): List<Message.Response> {
        while (true) {
            val turn = if (externalMcpConfigFile != null) {
                // External MCP: tool calls handled externally, just wait for text responses
                completedResponses.receive()
            } else {
                select<CompletedTurn> {
                    completedResponses.onReceive { it }
                    toolBridge.pendingCall.onReceive { call ->
                        CompletedTurn.ToolUse(listOf(call))
                    }
                }
            }

            when (turn) {
                is CompletedTurn.Text -> {
                    val metaInfo = createResponseMetaInfo(turn.inputTokens, turn.outputTokens)
                    return listOf(Message.Assistant(turn.text, metaInfo, "end_turn"))
                }

                is CompletedTurn.ToolUse -> {
                    val metaInfo = createResponseMetaInfo(null, null)
                    return turn.calls.map { call ->
                        Message.Tool.Call(call.id, call.name, call.argsJson, metaInfo)
                    }
                }

                is CompletedTurn.ResultEvent -> {
                    if (turn.isError) {
                        val metaInfo = createResponseMetaInfo(null, null)
                        return listOf(Message.Assistant("Error: ${turn.text}", metaInfo, "error"))
                    }
                    // Non-error result with text — this is the final answer if we haven't
                    // already returned a Text turn
                    if (turn.text != null) {
                        val metaInfo = createResponseMetaInfo(null, null)
                        return listOf(Message.Assistant(turn.text, metaInfo, "end_turn"))
                    }
                    // Empty result, keep waiting (shouldn't happen)
                    continue
                }

                is CompletedTurn.Error -> {
                    val metaInfo = createResponseMetaInfo(null, null)
                    return listOf(Message.Assistant("Error: ${turn.message}", metaInfo, "error"))
                }

                is CompletedTurn.ProcessDied -> {
                    logger.warn { "Claude process died, restarting..." }
                    conversationTracker.reset()
                    startClaudeProcess()
                    val metaInfo = createResponseMetaInfo(null, null)
                    return listOf(
                        Message.Assistant("Claude process died unexpectedly. Restarted.", metaInfo, "error")
                    )
                }
            }
        }
    }

    private fun buildUserContent(messages: List<Message>): String? {
        if (messages.last() is Message.Tool.Result) return null

        val parts = mutableListOf<String>()
        for (msg in messages) {
            when (msg) {
                is Message.System -> parts.add("[System] ${msg.content}")
                is Message.User -> parts.add(msg.content)
                is Message.Assistant -> parts.add("[Previous Assistant] ${msg.content}")
                is Message.Tool.Call -> parts.add("[Previous Tool Call] ${msg.tool}: ${msg.content}")
                is Message.Tool.Result -> parts.add("[Tool Result for ${msg.id}] ${msg.content}")
                else -> parts.add(msg.content)
            }
        }
        return parts.joinToString("\n\n")
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = flow {
        // Launch execute in background — it sends the message and waits for completion.
        // Meanwhile, we collect streaming frames as they arrive from the stdout reader.
        val executeJob = scope.async { execute(prompt, model, tools) }

        // Collect streaming frames until execute completes
        try {
            while (executeJob.isActive) {
                val frame = withTimeoutOrNull(50) {
                    streamingFrames.receive()
                }
                if (frame != null) {
                    emit(frame)
                }
            }
            // Drain remaining frames
            while (true) {
                val frame = streamingFrames.tryReceive().getOrNull() ?: break
                emit(frame)
            }
        } catch (e: CancellationException) {
            executeJob.cancel()
            throw e
        }

        // Get the final responses and emit complete frames
        val responses = executeJob.await()
        for (response in responses) {
            when (response) {
                is Message.Assistant -> {
                    emit(StreamFrame.TextComplete(response.content))
                }
                is Message.Tool.Call -> {
                    emit(StreamFrame.ToolCallComplete(response.id, response.tool, response.content))
                }
                else -> {}
            }
        }
        val lastMeta = responses.lastOrNull()?.metaInfo ?: createResponseMetaInfo(null, null)
        emit(StreamFrame.End("end_turn", lastMeta))
    }

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): List<List<Message.Response>> {
        return listOf(execute(prompt, model, tools))
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
        return ModerationResult(false, emptyMap())
    }

    override suspend fun models(): List<LLModel> = ClaudeCodeModels.all

    override fun llmProvider(): LLMProvider = ClaudeCodeProvider

    override fun close() {
        stdoutReaderJob?.cancel()
        claudeProcess?.stop()
        if (externalMcpConfigFile == null) {
            mcpServer.stop()
            toolBridge.close()
        }
        scope.cancel()
    }

    private fun ensureProcessAlive() {
        if (claudeProcess?.isAlive != true) {
            conversationTracker.reset()
            startClaudeProcess()
        }
    }

    @Suppress("DEPRECATION")
    private fun createResponseMetaInfo(inputTokens: Int?, outputTokens: Int?): ResponseMetaInfo {
        return ResponseMetaInfo(
            timestamp = kotlin.time.Clock.System.now(),
            totalTokensCount = if (inputTokens != null && outputTokens != null) inputTokens + outputTokens else null,
            inputTokensCount = inputTokens,
            outputTokensCount = outputTokens,
            additionalInfo = emptyMap(),
            metadata = JsonObject(emptyMap()),
        )
    }
}
