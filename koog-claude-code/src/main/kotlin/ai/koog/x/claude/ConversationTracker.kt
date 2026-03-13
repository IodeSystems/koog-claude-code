package ai.koog.x.claude

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.message.Message

class ConversationTracker {
    private var sentCount: Int = 0

    fun getNewMessages(prompt: Prompt): List<Message> {
        val all = prompt.messages
        if (sentCount > all.size) {
            // Conversation was truncated/reset externally
            sentCount = 0
        }
        val newMessages = all.subList(sentCount, all.size)
        sentCount = all.size
        return newMessages
    }

    fun reset() {
        sentCount = 0
    }
}
