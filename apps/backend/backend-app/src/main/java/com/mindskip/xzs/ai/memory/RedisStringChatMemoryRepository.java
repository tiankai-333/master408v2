package com.mindskip.xzs.ai.memory;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * ChatMemoryRepository for a plain Redis server.
 *
 * <p>Spring AI's built-in Redis repository requires Redis Stack modules
 * (RedisJSON and Query Engine). The existing Master408 deployment uses plain
 * Redis, so this repository stores the bounded message window as one JSON value.</p>
 */
public class RedisStringChatMemoryRepository implements ChatMemoryRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final Duration ttl;

    public RedisStringChatMemoryRepository(StringRedisTemplate redisTemplate,
                                           ObjectMapper objectMapper,
                                           String keyPrefix,
                                           Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.keyPrefix = keyPrefix;
        this.ttl = ttl;
    }

    @Override
    public List<String> findConversationIds() {
        Set<String> keys = redisTemplate.keys(keyPrefix + "*");
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }
        return keys.stream()
                .map(key -> key.substring(keyPrefix.length()))
                .sorted()
                .toList();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        String json = redisTemplate.opsForValue().get(key(conversationId));
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<StoredMessage> stored = objectMapper.readValue(
                    json, new TypeReference<List<StoredMessage>>() {});
            List<Message> messages = new ArrayList<>(stored.size());
            for (StoredMessage item : stored) {
                messages.add(toMessage(item));
            }
            return messages;
        } catch (Exception exception) {
            throw new IllegalStateException("Chat memory JSON is invalid", exception);
        }
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        try {
            List<StoredMessage> stored = messages.stream()
                    .map(message -> new StoredMessage(
                            message.getMessageType().name(), message.getText()))
                    .toList();
            redisTemplate.opsForValue().set(
                    key(conversationId), objectMapper.writeValueAsString(stored), ttl);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to persist chat memory", exception);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        redisTemplate.delete(key(conversationId));
    }

    private String key(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        return keyPrefix + conversationId;
    }

    private Message toMessage(StoredMessage stored) {
        MessageType type = MessageType.valueOf(stored.type());
        return switch (type) {
            case USER -> new UserMessage(stored.text());
            case ASSISTANT -> new AssistantMessage(stored.text());
            case SYSTEM -> new SystemMessage(stored.text());
            case TOOL -> throw new IllegalStateException(
                    "Tool messages are not supported by the first memory slice");
        };
    }

    private record StoredMessage(String type, String text) {
    }
}
