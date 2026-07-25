package com.mindskip.xzs.ai;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Keeps the first Plato turn rich in subject context while making later turns
 * actual student replies. Previous turns are injected by ChatMemory.
 */
@Component
public class PlatoConversationPromptPolicy {

    private final ObjectProvider<ChatMemory> chatMemoryProvider;

    public PlatoConversationPromptPolicy(ObjectProvider<ChatMemory> chatMemoryProvider) {
        this.chatMemoryProvider = chatMemoryProvider;
    }

    public String prepare(String style, String currentInput, String conversationId,
                          String initialPrompt) {
        if (!"plato".equals(style) || conversationId == null) {
            return initialPrompt;
        }
        ChatMemory chatMemory = chatMemoryProvider.getIfAvailable();
        if (chatMemory == null || chatMemory.get(conversationId).isEmpty()) {
            return initialPrompt;
        }
        return """
                这是学生对上一轮引导的本轮回复：

                %s

                请结合对话历史判断学生的理解，只给一句必要反馈，然后提出一个新的关键问句。
                整次回复只能出现一个问号，而且只能推进一个认知或计算动作，不能串联下一步。
                不要重复整道题，不要自问自答。如果学生明确要求直接答案或表示不会，则按系统规则揭示答案。
                """.formatted(currentInput == null ? "" : currentInput.trim());
    }
}
