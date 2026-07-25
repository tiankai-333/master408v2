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

    record Outcome(AiAgentPlanResponseVM agentDraft, String answer, boolean toolCalled) {
    }
}
