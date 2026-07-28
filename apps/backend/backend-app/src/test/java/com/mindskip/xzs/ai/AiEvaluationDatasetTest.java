package com.mindskip.xzs.ai;

import com.mindskip.xzs.ai.evaluation.AiEvaluationDataset;
import com.mindskip.xzs.ai.evaluation.FixedAiEvaluationDatasetLoader;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AiEvaluationDatasetTest {

    @Test
    void fixedDatasetCoversCoreAndFailureScenarios() {
        AiEvaluationDataset dataset = new FixedAiEvaluationDatasetLoader(new ObjectMapper()).load();
        Set<String> categories = new HashSet<>();
        Set<String> ids = new HashSet<>();
        dataset.cases().forEach(item -> {
            categories.add(item.category());
            ids.add(item.id());
        });

        assertThat(dataset.version()).isEqualTo("408-prompt-baseline-v1");
        assertThat(dataset.cases()).hasSizeGreaterThanOrEqualTo(12);
        assertThat(ids).hasSameSizeAs(dataset.cases());
        assertThat(dataset.cases().stream().filter(item -> item.isModelCase()).count())
                .isGreaterThanOrEqualTo(8);
        assertThat(categories)
                .contains("basic", "style", "rag", "boundary", "invalid", "failure");
    }
}
