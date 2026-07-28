package com.mindskip.xzs.controller.admin;

import com.mindskip.xzs.ai.evaluation.AiEvaluationDataset;
import com.mindskip.xzs.ai.evaluation.AiEvaluationRunRequest;
import com.mindskip.xzs.ai.evaluation.AiEvaluationService;
import com.mindskip.xzs.base.BaseApiController;
import com.mindskip.xzs.base.RestResponse;
import com.mindskip.xzs.domain.User;
import com.mindskip.xzs.domain.ai.AiEvaluationRun;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/ai-evaluation")
public class AiEvaluationController extends BaseApiController {

    private final AiEvaluationService evaluationService;

    public AiEvaluationController(AiEvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @GetMapping("/dataset")
    public RestResponse<AiEvaluationDataset> dataset() {
        return RestResponse.ok(evaluationService.dataset());
    }

    @PostMapping("/runs")
    public RestResponse<AiEvaluationRun> start(
            @RequestBody(required = false) AiEvaluationRunRequest request) {
        User operator = getCurrentUser();
        return RestResponse.ok(evaluationService.start(
                request == null ? new AiEvaluationRunRequest(null, List.of()) : request,
                operator == null ? null : operator.getId()));
    }

    @GetMapping("/runs")
    public RestResponse<List<AiEvaluationRun>> recent(
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return RestResponse.ok(evaluationService.recent(limit));
    }

    @GetMapping("/runs/{id}")
    public RestResponse<Map<String, Object>> detail(@PathVariable("id") Long id) {
        return RestResponse.ok(evaluationService.detail(id));
    }

    @GetMapping("/compare")
    public RestResponse<Map<String, Object>> compare(
            @RequestParam("baselineRunId") Long baselineRunId,
            @RequestParam("candidateRunId") Long candidateRunId) {
        return RestResponse.ok(evaluationService.compare(baselineRunId, candidateRunId));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public RestResponse<Object> handleBadRequest(Exception exception) {
        return RestResponse.fail(400, exception.getMessage());
    }
}
