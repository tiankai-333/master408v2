package com.mindskip.xzs.ai.memory;

import org.springframework.stereotype.Component;

@Component
public class ConversationMemoryIdFactory {

    public String create(Integer userId, Object rawConversationId) {
        if (rawConversationId == null) {
            return null;
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        String clientId = String.valueOf(rawConversationId).trim();
        if (!clientId.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new IllegalArgumentException("conversationId 格式不正确");
        }
        return "user:" + userId + ":conversation:" + clientId;
    }
}
