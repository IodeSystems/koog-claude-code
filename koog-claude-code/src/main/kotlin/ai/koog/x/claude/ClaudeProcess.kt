package ai.koog.x.claude

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File

private val logger = KotlinLogging.logger {}

class ClaudeProcess(
    private val mcpConfigFile: File,
    private val claudeBinary: String = "claude",
) {
    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private var stdoutReader: BufferedReader? = null

    val isAlive: Boolean get() = process?.isAlive == true

    fun start() {
        val cmd = listOf(
            claudeBinary,
            "--print",
            "--output-format", "stream-json",
            "--input-format", "stream-json",
            "--verbose",
            "--dangerously-skip-permissions",
            "--tools", "",
            "--allowed-tools", "mcp__koog__*",
            "--mcp-config", mcpConfigFile.absolutePath,
        )

        logger.info { "Starting claude process: ${cmd.joinToString(" ")}" }

        val pb = ProcessBuilder(cmd)
        pb.environment().remove("CLAUDECODE")
        pb.environment().remove("CLAUDE_CODE_ENTRYPOINT")
        pb.redirectErrorStream(false)
        process = pb.start()
        stdin = process!!.outputStream.bufferedWriter()
        stdoutReader = process!!.inputStream.bufferedReader()

        // Log stderr in background
        val stderr = process!!.errorStream.bufferedReader()
        Thread({
            try {
                stderr.lineSequence().forEach { line ->
                    logger.info { "[claude stderr] $line" }
                }
            } catch (_: Exception) {
            }
        }, "claude-stderr").apply { isDaemon = true }.start()
    }

    fun sendMessage(text: String) {
        val msg = buildJsonObject {
            put("type", "user")
            putJsonObject("message") {
                put("role", "user")
                put("content", text)
            }
        }
        val line = msg.toString()
        logger.debug { "Sending to claude stdin: $line" }
        stdin?.write(line)
        stdin?.newLine()
        stdin?.flush()
    }

    fun readLines(): Sequence<String> {
        return stdoutReader?.lineSequence() ?: emptySequence()
    }

    fun stop() {
        try {
            stdin?.close()
        } catch (_: Exception) {
        }
        try {
            process?.destroyForcibly()
        } catch (_: Exception) {
        }
        process = null
        stdin = null
        stdoutReader = null
    }
}
