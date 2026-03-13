package ai.koog.x.claude

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.x.claude.model.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

class McpBridgeServer(
    private val toolBridge: McpToolBridge,
) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val tools = AtomicReference<List<ToolDescriptor>>(emptyList())
    private val sseOutbound = MutableSharedFlow<String>(extraBufferCapacity = 64)
    var port: Int = 0
        private set

    fun updateTools(newTools: List<ToolDescriptor>) {
        tools.set(newTools)
        sseOutbound.tryEmit(json.encodeToString(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/tools/list_changed")
        }))
    }

    suspend fun start() {
        port = ServerSocket(0).use { it.localPort }
        server = embeddedServer(Netty, port = port) {
            install(ContentNegotiation) { json(json) }
            install(SSE)
            routing {
                // Streamable HTTP transport: single endpoint for all requests
                post("/mcp") {
                    val body = call.receiveText()
                    logger.debug { "MCP request: $body" }
                    val request = json.decodeFromString<McpJsonRpcRequest>(body)

                    if (request.method.startsWith("notifications/")) {
                        call.respondText("", ContentType.Application.Json, HttpStatusCode.OK)
                        return@post
                    }

                    val response = handleRequest(request)
                    val responseText = json.encodeToString(response)
                    logger.debug { "MCP response: $responseText" }
                    call.respondText(responseText, ContentType.Application.Json, HttpStatusCode.OK)
                }

                // Legacy SSE transport for compatibility
                sse("/sse") {
                    val messageUrl = "http://localhost:$port/message"
                    send(data = messageUrl, event = "endpoint")
                    sseOutbound.collect { message ->
                        send(data = message, event = "message")
                    }
                }
                post("/message") {
                    val body = call.receiveText()
                    logger.debug { "MCP request (SSE): $body" }
                    val request = json.decodeFromString<McpJsonRpcRequest>(body)

                    if (request.method.startsWith("notifications/")) {
                        call.respond(HttpStatusCode.Accepted)
                        return@post
                    }

                    // For SSE transport, response goes via SSE stream
                    val response = handleRequest(request)
                    val responseText = json.encodeToString(response)
                    logger.debug { "MCP response (SSE): $responseText" }
                    sseOutbound.emit(responseText)
                    call.respond(HttpStatusCode.Accepted)
                }
            }
        }.start(wait = false)
        logger.info { "MCP bridge server started on port $port" }
    }

    private suspend fun handleRequest(request: McpJsonRpcRequest): McpJsonRpcResponse {
        return when (request.method) {
            "initialize" -> {
                val result = McpInitializeResult()
                McpJsonRpcResponse(
                    id = request.id,
                    result = json.encodeToJsonElement(result),
                )
            }

            "tools/list" -> {
                val toolDefs = tools.get().map { it.toMcpTool() }
                val result = McpToolsListResult(toolDefs)
                McpJsonRpcResponse(
                    id = request.id,
                    result = json.encodeToJsonElement(result),
                )
            }

            "tools/call" -> {
                handleToolCall(request)
            }

            "ping" -> {
                McpJsonRpcResponse(id = request.id, result = JsonObject(emptyMap()))
            }

            else -> {
                logger.warn { "Unknown MCP method: ${request.method}" }
                McpJsonRpcResponse(
                    id = request.id,
                    error = McpJsonRpcError(-32601, "Method not found: ${request.method}"),
                )
            }
        }
    }

    private suspend fun handleToolCall(request: McpJsonRpcRequest): McpJsonRpcResponse {
        val params = request.params?.let { json.decodeFromJsonElement<McpToolCallParams>(it) }
            ?: return McpJsonRpcResponse(
                id = request.id,
                error = McpJsonRpcError(-32602, "Invalid params"),
            )

        val toolName = params.name
        val argsJson = params.arguments?.toString() ?: "{}"

        val callId = request.id?.jsonPrimitive?.contentOrNull ?: "unknown"
        logger.info { "Tool call: $toolName($argsJson)" }

        val toolRequest = ToolCallRequest(
            id = callId,
            name = toolName,
            argsJson = argsJson,
        )
        toolBridge.pendingCall.send(toolRequest)

        val toolResult = toolBridge.pendingResult.receive()

        val mcpResult = McpToolCallResult(
            content = listOf(McpToolContent(text = toolResult.result)),
            isError = toolResult.isError,
        )
        return McpJsonRpcResponse(
            id = request.id,
            result = json.encodeToJsonElement(mcpResult),
        )
    }

    fun generateConfigFile(): File {
        val configFile = File.createTempFile("mcp-config-", ".json")
        configFile.deleteOnExit()
        val config = McpConfig(
            mcpServers = mapOf(
                "koog" to McpServerConfig(url = "http://localhost:$port/mcp", type = "http")
            )
        )
        configFile.writeText(json.encodeToString(config))
        return configFile
    }

    fun stop() {
        server?.stop(500, 1000)
        server = null
    }
}

fun ToolDescriptor.toMcpTool(): McpToolDef {
    val properties = mutableMapOf<String, JsonElement>()
    val required = mutableListOf<String>()

    for (param in requiredParameters) {
        properties[param.name] = buildJsonObject {
            put("type", param.type.toJsonSchemaType())
            put("description", param.description)
        }
        required.add(param.name)
    }
    for (param in optionalParameters) {
        properties[param.name] = buildJsonObject {
            put("type", param.type.toJsonSchemaType())
            put("description", param.description)
        }
    }

    val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties))
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }

    return McpToolDef(
        name = name,
        description = description,
        inputSchema = inputSchema,
    )
}

fun ToolParameterType.toJsonSchemaType(): String = when (this) {
    is ToolParameterType.String -> "string"
    is ToolParameterType.Integer -> "integer"
    is ToolParameterType.Float -> "number"
    is ToolParameterType.Boolean -> "boolean"
    else -> "string"
}
