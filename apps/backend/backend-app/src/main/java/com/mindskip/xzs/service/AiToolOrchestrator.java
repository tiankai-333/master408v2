package com.mindskip.xzs.service;

import com.mindskip.xzs.domain.User;
import com.mindskip.xzs.viewmodel.student.ai.AiAgentPlanResponseVM;
import com.mindskip.xzs.viewmodel.student.ai.AiWorkbenchRequestVM;

/**
 * Optional Spring AI tool-routing boundary. When unavailable, the deterministic
 * orchestrator continues to work without an additional model dependency.
 */
public interface AiToolOrchestrator {

    Outcome route(AiWorkbenchRequestVM request, User user);

    /**
     * @param terminatedByLimit true 当工具循环达到轮次上限且模型仍在请求工具调用：
     *                          此时 answer 不承载未消费 tool-call 消息的文本，耗尽原因记录在 run log。
     */
    record Outcome(AiAgentPlanResponseVM agentDraft, String answer, boolean toolCalled,
                   boolean terminatedByLimit) {
    }
}
