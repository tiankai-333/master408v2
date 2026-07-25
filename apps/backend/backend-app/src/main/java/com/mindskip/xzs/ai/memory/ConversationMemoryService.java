package com.mindskip.xzs.ai.memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Application boundary for user-controlled short-term conversation memory.
 */
@Service
public class ConversationMemoryService {

    private final ObjectProvider<ChatMemory> chatMemoryProvider;
    private final ConversationMemoryIdFactory memoryIdFactory;

    public ConversationMemoryService(ObjectProvider<ChatMemory> chatMemoryProvider,
                                     ConversationMemoryIdFactory memoryIdFactory) {
        this.chatMemoryProvider = chatMemoryProvider;
        this.memoryIdFactory = memoryIdFactory;
    }

    public boolean clear(Integer userId, Object rawConversationId) {
        String memoryId = memoryIdFactory.create(userId, rawConversationId);
        if (memoryId == null) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        ChatMemory chatMemory = chatMemoryProvider.getIfAvailable();
        if (chatMemory == null) {
            return false;
        }
        chatMemory.clear(memoryId);
        return true;
    }
}
