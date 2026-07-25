package com.mindskip.xzs.ai;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AiEvaluationDatasetTest {

    @Test
    void fixedDatasetCoversCoreAndFailureScenarios() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/ai/analysis-cases.json")) {
            assertThat(input).isNotNull();
            JsonNode cases = new ObjectMapper().readTree(input);
            Set<String> categories = new HashSet<>();
            cases.forEach(item -> categories.add(item.get("category").asText()));

            assertThat(cases.size()).isGreaterThanOrEqualTo(12);
            assertThat(categories)
                    .contains("basic", "style", "rag", "boundary", "invalid", "failure");
        }
    }
}
