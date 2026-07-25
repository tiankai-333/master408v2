package com.mindskip.xzs.ai.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * Creates a dedicated OpenAI-compatible embedding model.
 *
 * <p>The chat model may point to DeepSeek while this model points to Zhipu.
 * Keeping separate credentials prevents one provider's rate limit or model
 * capability from leaking into the other concern.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ai.engine", havingValue = "spring")
@ConditionalOnProperty(name = "ai.spring.provider-source", havingValue = "environment")
public class EnvironmentSpringAiEmbeddingConfiguration {

    @Bean
    @Primary
    @ConditionalOnMissingBean(EmbeddingModel.class)
    EmbeddingModel zhipuEmbeddingModel(
            @Value("${ai.embedding.key:}") String apiKey,
            @Value("${ai.api.key:}") String fallbackApiKey,
            @Value("${ai.embedding.base-url:}") String baseUrl,
            @Value("${ai.embedding.model:embedding-2}") String model,
            ObservationRegistry observationRegistry) {
        String resolvedApiKey = apiKey == null || apiKey.isBlank() ? fallbackApiKey : apiKey;
        requireConfigured(resolvedApiKey, "AI_EMBEDDING_API_KEY or AI_API_KEY");
        requireConfigured(baseUrl, "AI_EMBEDDING_BASE_URL");
        requireConfigured(model, "AI_EMBEDDING_MODEL");

        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .apiKey(resolvedApiKey)
                .baseUrl(baseUrl)
                .model(model)
                .timeout(Duration.ofSeconds(30))
                .maxRetries(0)
                .build();

        return OpenAiEmbeddingModel.builder()
                .options(options)
                .observationRegistry(observationRegistry)
                .build();
    }

    private void requireConfigured(String value, String setting) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(setting + " is required for Spring AI embedding");
        }
    }
}
