package com.mindskip.xzs.ai.config;

import com.mindskip.xzs.domain.ai.AiProviderConfig;
import com.mindskip.xzs.service.AiProviderConfigService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseSpringAiConfigurationTest {

    private final DatabaseSpringAiConfiguration configuration =
            new DatabaseSpringAiConfiguration();

    @Test
    void buildsOpenAiCompatibleModelFromEncryptedProviderRegistry() {
        AiProviderConfigService providerService = mock(AiProviderConfigService.class);
        AiProviderConfig provider = provider("zhipu", "https://open.bigmodel.cn/api/paas/v4",
                "glm-4.5-air");
        when(providerService.getFirstEnabled()).thenReturn(provider);
        when(providerService.resolveApiKey("zhipu")).thenReturn("test-key-not-sent");

        ChatModel model = configuration.databaseChatModel(
                providerService, ObservationRegistry.NOOP, new SimpleMeterRegistry());

        assertThat(model).isNotNull();
        OpenAiChatOptions options = (OpenAiChatOptions) model.getOptions();
        assertThat(options.getBaseUrl()).isEqualTo("https://open.bigmodel.cn/api/paas/v4");
        assertThat(options.getModel()).isEqualTo("glm-4.5-air");
    }

    @Test
    void failsFastWhenSpringModeHasNoEnabledProvider() {
        AiProviderConfigService providerService = mock(AiProviderConfigService.class);

        assertThatThrownBy(() -> configuration.databaseChatModel(
                providerService, ObservationRegistry.NOOP, new SimpleMeterRegistry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enabled ai_provider_config");
    }

    private AiProviderConfig provider(String code, String baseUrl, String model) {
        AiProviderConfig provider = new AiProviderConfig();
        provider.setProviderCode(code);
        provider.setApiBaseUrl(baseUrl);
        provider.setChatModel(model);
        provider.setEnabled(true);
        return provider;
    }
}
