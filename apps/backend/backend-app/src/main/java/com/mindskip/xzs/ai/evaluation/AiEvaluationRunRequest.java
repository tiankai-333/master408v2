package com.mindskip.xzs.ai.evaluation;

import java.util.List;

public record AiEvaluationRunRequest(String candidateLabel, List<String> caseIds) {

    public AiEvaluationRunRequest {
        caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
    }
}
