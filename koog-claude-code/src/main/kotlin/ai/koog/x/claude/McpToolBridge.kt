package ai.koog.x.claude

import kotlinx.coroutines.channels.Channel

data class ToolCallRequest(
    val id: String,
    val name: String,
    val argsJson: String,
)

data class ToolCallResult(
    val id: String,
    val result: String,
    val isError: Boolean = false,
)

class McpToolBridge {
    val pendingCall = Channel<ToolCallRequest>(Channel.RENDEZVOUS)
    val pendingResult = Channel<ToolCallResult>(Channel.RENDEZVOUS)

    fun close() {
        pendingCall.close()
        pendingResult.close()
    }
}
