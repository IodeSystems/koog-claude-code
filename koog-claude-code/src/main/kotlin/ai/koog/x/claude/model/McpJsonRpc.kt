package ai.koog.x.claude.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class McpJsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonObject? = null,
)

@Serializable
data class McpJsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: McpJsonRpcError? = null,
)

@Serializable
data class McpJsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

@Serializable
data class McpToolDef(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

@Serializable
data class McpInitializeResult(
    val protocolVersion: String = "2025-11-25",
    val capabilities: McpServerCapabilities = McpServerCapabilities(),
    val serverInfo: McpServerInfo = McpServerInfo(),
)

@Serializable
data class McpServerCapabilities(
    val tools: McpToolsCapability? = McpToolsCapability(),
)

@Serializable
data class McpToolsCapability(
    val listChanged: Boolean = true,
)

@Serializable
data class McpServerInfo(
    val name: String = "koog-bridge",
    val version: String = "0.1.0",
)

@Serializable
data class McpToolsListResult(
    val tools: List<McpToolDef>,
)

@Serializable
data class McpToolCallParams(
    val name: String,
    val arguments: JsonObject? = null,
)

@Serializable
data class McpToolCallResult(
    val content: List<McpToolContent>,
    val isError: Boolean = false,
)

@Serializable
data class McpToolContent(
    val type: String = "text",
    val text: String,
)

@Serializable
data class McpConfig(
    val mcpServers: Map<String, McpServerConfig>,
)

@Serializable
data class McpServerConfig(
    val url: String,
    val type: String = "sse",
)
