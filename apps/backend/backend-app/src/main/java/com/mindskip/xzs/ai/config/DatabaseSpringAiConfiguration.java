package com.mindskip.xzs.ai.config;

import com.mindskip.xzs.domain.ai.AiProviderConfig;
import com.mindskip.xzs.service.AiProviderConfigService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Adapts the existing encrypted provider registry to Spring AI's OpenAI-compatible model.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SpringAiProviderProperties.class)
@ConditionalOnProperty(name = "ai.engine", havingValue = "spring")
@ConditionalOnProperty(name = "ai.spring.provider-source", havingValue = "database",
        matchIfMissing = true)
public class DatabaseSpringAiConfiguration {

    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    ChatModel databaseChatModel(AiProviderConfigService providerService,
                                ObservationRegistry observationRegistry,
                                MeterRegistry meterRegistry) {
        AiProviderConfig provider = providerService.getFirstEnabled();
        if (provider == null) {
            throw new IllegalStateException(
                    "AI_ENGINE=spring requires an enabled ai_provider_config row");
        }

        String apiKey = providerService.resolveApiKey(provider.getProviderCode());
        if (isBlank(apiKey)) {
            throw new IllegalStateException(
                    "Enabled AI provider has no decryptable API key: "
                            + provider.getProviderCode());
        }
        if (isBlank(provider.getApiBaseUrl()) || isBlank(provider.getChatModel())) {
            throw new IllegalStateException(
                    "Enabled AI provider requires apiBaseUrl and chatModel: "
                            + provider.getProviderCode());
        }

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(provider.getApiBaseUrl())
                .apiKey(apiKey)
                .model(provider.getChatModel())
                .timeout(Duration.ofSeconds(90))
                // Retries are coordinated at the application boundary so 429
                // backoff, circuit state and fallback are observable in one place.
                .maxRetries(0)
                .build();

        return OpenAiChatModel.builder()
                .options(options)
                .observationRegistry(observationRegistry)
                .meterRegistry(meterRegistry)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(ChatClient.Builder.class)
    ChatClient.Builder databaseChatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
