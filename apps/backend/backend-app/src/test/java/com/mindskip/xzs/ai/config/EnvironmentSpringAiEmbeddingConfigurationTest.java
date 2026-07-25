package com.mindskip.xzs.ai.config;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentSpringAiEmbeddingConfigurationTest {

    private final EnvironmentSpringAiEmbeddingConfiguration configuration =
            new EnvironmentSpringAiEmbeddingConfiguration();

    @Test
    void createsIndependentOpenAiCompatibleEmbeddingModel() {
        EmbeddingModel model = configuration.zhipuEmbeddingModel(
                "test-key",
                "",
                "https://open.bigmodel.cn/api/paas/v4",
                "embedding-2",
                ObservationRegistry.NOOP);

        assertThat(model).isInstanceOf(OpenAiEmbeddingModel.class);
        assertThat(((OpenAiEmbeddingModel) model).getOptions().getModel())
                .isEqualTo("embedding-2");
    }

    @Test
    void failsFastWhenEmbeddingKeyIsMissing() {
        assertThatThrownBy(() -> configuration.zhipuEmbeddingModel(
                "",
                "",
                "https://open.bigmodel.cn/api/paas/v4",
                "embedding-2",
                ObservationRegistry.NOOP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_EMBEDDING_API_KEY");
    }
}
