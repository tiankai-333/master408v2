package com.mindskip.xzs.ai.evaluation;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the immutable public evaluation set bundled with the application.
 */
public class FixedAiEvaluationDatasetLoader {

    public static final String DEFAULT_RESOURCE = "ai/evaluation/analysis-cases-v1.json";

    private final ObjectMapper objectMapper;
    private final String resourcePath;

    public FixedAiEvaluationDatasetLoader(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_RESOURCE);
    }

    FixedAiEvaluationDatasetLoader(ObjectMapper objectMapper, String resourcePath) {
        this.objectMapper = objectMapper;
        this.resourcePath = resourcePath;
    }

    public AiEvaluationDataset load() {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        try (InputStream input = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(input);
            List<AiEvaluationCase> cases = new ArrayList<>();
            root.path("cases").forEach(node -> cases.add(toCase(node)));
            return new AiEvaluationDataset(
                    text(root, "version", "unknown"),
                    text(root, "description", ""),
                    cases);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load AI evaluation dataset: " + resourcePath, exception);
        }
    }

    private AiEvaluationCase toCase(JsonNode node) {
        return new AiEvaluationCase(
                text(node, "id", ""),
                text(node, "category", ""),
                text(node, "executionMode", "model"),
                text(node, "style", "default"),
                text(node, "taskType", "explain"),
                text(node, "question", ""),
                text(node, "knowledgePoints", ""),
                text(node, "referenceDocs", ""),
                strings(node.path("expectedConcepts")),
                strings(node.path("forbiddenPhrases")),
                node.path("minimumConceptCoverage").asDouble(0.5),
                node.path("minimumResponseChars").asInt(1),
                node.path("maximumResponseChars").asInt(4000),
                text(node, "expectedOutcome", "answer"));
    }

    private List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? fallback : value.asText(fallback);
    }
}
