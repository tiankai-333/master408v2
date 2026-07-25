package com.mindskip.xzs.ai.memory;

import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ai.engine", havingValue = "spring")
@ConditionalOnProperty(name = "ai.memory.enabled", havingValue = "true", matchIfMissing = true)
public class ChatMemoryConfiguration {

    @Bean
    @Primary
    ChatMemoryRepository redisStringChatMemoryRepository(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${ai.memory.key-prefix:master408:chat-memory:}") String keyPrefix,
            @Value("${ai.memory.ttl:24h}") Duration ttl) {
        return new RedisStringChatMemoryRepository(
                redisTemplate, objectMapper, keyPrefix, ttl);
    }

    @Bean
    @Primary
    ChatMemory chatMemory(
            ChatMemoryRepository repository,
            @Value("${ai.memory.max-messages:12}") int maxMessages) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(maxMessages)
                .build();
    }

    @Bean
    MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }
}
