package com.mindskip.xzs.ai.evaluation;

import java.util.List;

/**
 * Versioned, reproducible evaluation data loaded from the repository.
 */
public record AiEvaluationDataset(String version, String description,
                                  List<AiEvaluationCase> cases) {

    public AiEvaluationDataset {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
