package com.mindskip.xzs.controller.student;

import com.mindskip.xzs.ai.AnalysisService;
import com.mindskip.xzs.ai.RagService;
import com.mindskip.xzs.base.BaseApiController;
import com.mindskip.xzs.domain.User;
import com.mindskip.xzs.service.AiOrchestratorService;
import com.mindskip.xzs.utility.Utf8SseEventWriter;
import jakarta.servlet.http.HttpServletResponse;
import com.mindskip.xzs.viewmodel.student.ai.AiWorkbenchRequestVM;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

@RestController("StudentAiWorkbenchController")
@RequestMapping("/api/student/ai/workbench")
public class AiWorkbenchController extends BaseApiController {

    private static final Logger logger = LoggerFactory.getLogger(AiWorkbenchController.class);

    private final AiOrchestratorService aiOrchestratorService;

    public AiWorkbenchController(AiOrchestratorService aiOrchestratorService) {
        this.aiOrchestratorService = aiOrchestratorService;
    }

    @PostMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter stream(@RequestBody AiWorkbenchRequestVM request, HttpServletResponse response) {
        Utf8SseEventWriter.prepareResponse(response);
        SseEmitter emitter = new SseEmitter(600000L);
        final User currentUser = getCurrentUser();
        CompletableFuture.runAsync(() -> {
            AnalysisService.setCurrentUserId(currentUser.getId());
            RagService.setCurrentUserId(currentUser.getId());
            try {
                aiOrchestratorService.handleStream(request, currentUser,
                        (eventName, data) -> sendEvent(emitter, eventName, data));
                sendEvent(emitter, "done", "ok");
                emitter.complete();
            } catch (Exception e) {
                logger.error("AI 工作台流式处理失败", e);
                try {
                    sendEvent(emitter, "error", "AI工作台处理失败：" + e.getMessage());
                } catch (Exception ignored) {
                }
                emitter.completeWithError(e);
            } finally {
                RagService.clearCurrentUserId();
                AnalysisService.clearCurrentUserId();
            }
        });
        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String name, String data) throws IOException {
        Utf8SseEventWriter.send(emitter, name, data);
    }
}
