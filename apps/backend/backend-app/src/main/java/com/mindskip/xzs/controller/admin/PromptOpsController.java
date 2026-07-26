package com.mindskip.xzs.controller.admin;

import com.mindskip.xzs.base.BaseApiController;
import com.mindskip.xzs.base.RestResponse;
import com.mindskip.xzs.domain.User;
import com.mindskip.xzs.service.PromptOpsService;
import com.mindskip.xzs.viewmodel.admin.prompt.ApproveRequestVM;
import com.mindskip.xzs.viewmodel.admin.prompt.DefinitionDetailVM;
import com.mindskip.xzs.viewmodel.admin.prompt.DefinitionSummaryVM;
import com.mindskip.xzs.viewmodel.admin.prompt.KillSwitchRequestVM;
import com.mindskip.xzs.viewmodel.admin.prompt.PercentRequestVM;
import com.mindskip.xzs.viewmodel.admin.prompt.PromptTestRequestVM;
import com.mindskip.xzs.viewmodel.admin.prompt.PromptVersionRequestVM;
import com.mindskip.xzs.viewmodel.admin.prompt.RollbackRequestVM;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI Prompt 控制平面管理端 API：不可变版本 / 审批 / 灰度 / 发布 / 回滚 / Kill Switch。
 * 路径前缀 /api/admin/prompt-ops。
 */
@RestController
@RequestMapping("/api/admin/prompt-ops")
public class PromptOpsController extends BaseApiController {

    @Autowired
    private PromptOpsService promptOpsService;

    @Autowired
    private HttpServletRequest request;

    @GetMapping("/definitions")
    public RestResponse<List<DefinitionSummaryVM>> definitions() {
        return RestResponse.ok(promptOpsService.listDefinitions());
    }

    @GetMapping("/definitions/{key}")
    public RestResponse<DefinitionDetailVM> definitionDetail(@PathVariable("key") String key) {
        return RestResponse.ok(promptOpsService.getDefinitionDetail(key));
    }

    @GetMapping("/versions/{id}")
    public RestResponse<Object> version(@PathVariable("id") Long id) {
        return RestResponse.ok(promptOpsService.getVersion(id));
    }

    @GetMapping("/versions/{id}/diff")
    public RestResponse<Object> diff(@PathVariable("id") Long id,
                                     @RequestParam("against") Long againstVersionId) {
        return RestResponse.ok(promptOpsService.diff(id, againstVersionId));
    }

    @PostMapping("/definitions/{key}/versions")
    public RestResponse<Object> createDraft(@PathVariable("key") String key,
                                            @RequestBody(required = false) PromptVersionRequestVM body) {
        return RestResponse.ok(promptOpsService.createDraft(key, body(body), operator()));
    }

    @PostMapping("/versions/{id}")
    public RestResponse<Object> editDraft(@PathVariable("id") Long id,
                                          @RequestBody PromptVersionRequestVM body) {
        return RestResponse.ok(promptOpsService.editDraft(id, body, operator()));
    }

    @PostMapping("/versions/{id}/test")
    public RestResponse<String> test(@PathVariable("id") Long id,
                                     @RequestBody PromptTestRequestVM body) throws Exception {
        return RestResponse.ok(promptOpsService.test(id, body));
    }

    @PostMapping("/versions/{id}/submit")
    public RestResponse<Object> submit(@PathVariable("id") Long id) {
        return RestResponse.ok(promptOpsService.submitForApproval(id, operator()));
    }

    @PostMapping("/versions/{id}/approve")
    public RestResponse<Object> approve(@PathVariable("id") Long id, @RequestBody ApproveRequestVM body) {
        Integer pct = body == null ? null : body.percent();
        String comment = body == null ? null : body.comment();
        return RestResponse.ok(promptOpsService.approve(id, pct, comment, operator()));
    }

    @PostMapping("/versions/{id}/canary")
    public RestResponse<Object> canary(@PathVariable("id") Long id, @RequestBody PercentRequestVM body) {
        Integer pct = body == null ? null : body.percent();
        return RestResponse.ok(promptOpsService.setCanary(id, pct, operator()));
    }

    @PostMapping("/versions/{id}/promote")
    public RestResponse<Object> promote(@PathVariable("id") Long id) {
        return RestResponse.ok(promptOpsService.promote(id, operator()));
    }

    @PostMapping("/definitions/{key}/rollback")
    public RestResponse<Object> rollback(@PathVariable("key") String key, @RequestBody RollbackRequestVM body) {
        return RestResponse.ok(promptOpsService.rollback(key, body == null ? null : body.toVersionId(), operator()));
    }

    @PostMapping("/definitions/{key}/kill-switch")
    public RestResponse<Object> killSwitch(@PathVariable("key") String key, @RequestBody KillSwitchRequestVM body) {
        promptOpsService.killSwitch(key, body == null ? null : body.enabled(), operator());
        return RestResponse.ok();
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public RestResponse<Object> handleBadRequest(Exception e) {
        return RestResponse.fail(400, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public RestResponse<Object> handleFailure(Exception e) {
        return RestResponse.fail(500, e.getMessage());
    }

    private PromptVersionRequestVM body(PromptVersionRequestVM body) {
        return body == null ? new PromptVersionRequestVM(null, null, null, null, null, null) : body;
    }

    private PromptOpsService.Operator operator() {
        User u = getCurrentUser();
        return new PromptOpsService.Operator(
                u == null ? null : u.getId(),
                u == null ? null : u.getUserName(),
                clientIp());
    }

    private String clientIp() {
        try {
            String ip = request.getHeader("X-Forwarded-For");
            if (ip == null || ip.isBlank()) {
                ip = request.getRemoteAddr();
            }
            return ip;
        } catch (Exception e) {
            return null;
        }
    }
}
