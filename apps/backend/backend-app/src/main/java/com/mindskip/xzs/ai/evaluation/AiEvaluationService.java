package com.mindskip.xzs.ai.evaluation;

import com.mindskip.xzs.ai.AiAnalysisGateway;
import com.mindskip.xzs.domain.ai.AiEvaluationCaseResultRecord;
import com.mindskip.xzs.domain.ai.AiEvaluationRun;
import com.mindskip.xzs.repository.AiEvaluationMapper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiEvaluationService {

    private static final int MAX_CASES_PER_RUN = 20;

    private final AiEvaluationMapper mapper;
    private final FixedAiEvaluationDatasetLoader datasetLoader;
    private final AiEvaluationWorker worker;
    private final AiAnalysisGateway analysisGateway;

    public AiEvaluationService(AiEvaluationMapper mapper,
                               ObjectMapper objectMapper,
                               AiEvaluationWorker worker,
                               AiAnalysisGateway analysisGateway) {
        this.mapper = mapper;
        this.datasetLoader = new FixedAiEvaluationDatasetLoader(objectMapper);
        this.worker = worker;
        this.analysisGateway = analysisGateway;
    }

    public AiEvaluationDataset dataset() {
        return datasetLoader.load();
    }

    public AiEvaluationRun start(AiEvaluationRunRequest request, Integer operatorId) {
        AiEvaluationDataset dataset = dataset();
        List<AiEvaluationCase> selected = selectCases(dataset, request == null ? List.of() : request.caseIds());
        String candidateLabel = request == null ? null : request.candidateLabel();
        if (candidateLabel == null || candidateLabel.isBlank()) {
            candidateLabel = analysisGateway.engineName() + "-current";
        }
        if (candidateLabel.length() > 150) {
            throw new IllegalArgumentException("candidateLabel must not exceed 150 characters");
        }

        AiEvaluationRun run = new AiEvaluationRun();
        run.setDatasetVersion(dataset.version());
        run.setCandidateLabel(candidateLabel.trim());
        run.setEngine(analysisGateway.engineName());
        run.setStatus("queued");
        run.setCaseCount(selected.size());
        run.setCreatedBy(operatorId);
        run.setCreateTime(new java.util.Date());
        mapper.insertRun(run);

        try {
            worker.execute(run.getId(), selected);
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            mapper.failRun(run.getId(), "evaluation-queue-full");
            throw new IllegalStateException("Evaluation queue is full; retry later", exception);
        }
        return mapper.selectRun(run.getId());
    }

    public Map<String, Object> detail(Long runId) {
        AiEvaluationRun run = requireRun(runId);
        List<AiEvaluationCaseResultRecord> results = mapper.selectCaseResults(runId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("run", run);
        detail.put("results", results);
        return detail;
    }

    public List<AiEvaluationRun> recent(int limit) {
        return mapper.selectRecentRuns(Math.max(1, Math.min(limit, 100)));
    }

    public Map<String, Object> compare(Long baselineRunId, Long candidateRunId) {
        AiEvaluationRun baseline = requireCompletedRun(baselineRunId);
        AiEvaluationRun candidate = requireCompletedRun(candidateRunId);
        if (!baseline.getDatasetVersion().equals(candidate.getDatasetVersion())) {
            throw new IllegalArgumentException("Only runs of the same dataset version can be compared");
        }
        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("datasetVersion", baseline.getDatasetVersion());
        comparison.put("baseline", baseline);
        comparison.put("candidate", candidate);
        comparison.put("qualityScoreDelta", decimal(candidate.getAverageQualityScore())
                - decimal(baseline.getAverageQualityScore()));
        comparison.put("estimatedCostDelta", decimal(candidate.getEstimatedCost())
                - decimal(baseline.getEstimatedCost()));
        comparison.put("averageLatencyMsDelta", integer(candidate.getAverageLatencyMs())
                - integer(baseline.getAverageLatencyMs()));
        comparison.put("p95LatencyMsDelta", integer(candidate.getP95LatencyMs())
                - integer(baseline.getP95LatencyMs()));
        comparison.put("passedCountDelta", integer(candidate.getPassedCount())
                - integer(baseline.getPassedCount()));
        return comparison;
    }

    private List<AiEvaluationCase> selectCases(AiEvaluationDataset dataset, List<String> requestedIds) {
        List<AiEvaluationCase> modelCases = dataset.cases().stream()
                .filter(AiEvaluationCase::isModelCase)
                .toList();
        if (requestedIds == null || requestedIds.isEmpty()) {
            return modelCases;
        }
        if (requestedIds.size() > MAX_CASES_PER_RUN) {
            throw new IllegalArgumentException("At most " + MAX_CASES_PER_RUN + " cases may be run");
        }
        Map<String, AiEvaluationCase> byId = modelCases.stream()
                .collect(java.util.stream.Collectors.toMap(AiEvaluationCase::id, value -> value));
        List<AiEvaluationCase> selected = requestedIds.stream().distinct().map(id -> {
            AiEvaluationCase value = byId.get(id);
            if (value == null) {
                throw new IllegalArgumentException("Unknown or non-model evaluation case: " + id);
            }
            return value;
        }).toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("No executable model cases selected");
        }
        return selected;
    }

    private AiEvaluationRun requireRun(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("runId is required");
        }
        AiEvaluationRun run = mapper.selectRun(id);
        if (run == null) {
            throw new IllegalArgumentException("Evaluation run does not exist: " + id);
        }
        return run;
    }

    private AiEvaluationRun requireCompletedRun(Long id) {
        AiEvaluationRun run = requireRun(id);
        if (!"completed".equals(run.getStatus())) {
            throw new IllegalStateException("Evaluation run is not completed: " + id);
        }
        return run;
    }

    private double decimal(Number value) {
        return value == null ? 0 : value.doubleValue();
    }

    private int integer(Number value) {
        return value == null ? 0 : value.intValue();
    }
}
