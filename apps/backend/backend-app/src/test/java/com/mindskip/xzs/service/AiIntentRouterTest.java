package com.mindskip.xzs.service;

import com.mindskip.xzs.viewmodel.student.ai.AiWorkbenchRequestVM;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiIntentRouterTest {

    private final AiIntentRouter router = new AiIntentRouter();

    @Test
    void explicitIntentWinsWithoutModelRouting() {
        AiWorkbenchRequestVM request = request("随便聊聊");
        request.setIntent(AiIntentRouter.PRACTICE_PLAN);

        AiIntentRouter.RouteDecision decision = router.resolveDecision(request);

        assertEquals(AiIntentRouter.PRACTICE_PLAN, decision.intent());
        assertEquals(AiIntentRouter.RouteSource.EXPLICIT, decision.source());
        assertFalse(decision.needsModelRouting());
    }

    @Test
    void highConfidencePracticeRuleAvoidsExtraModelCost() {
        AiIntentRouter.RouteDecision decision =
                router.resolveDecision(request("帮我针对二叉树出三道练习题"));

        assertEquals(AiIntentRouter.PRACTICE_PLAN, decision.intent());
        assertEquals(AiIntentRouter.RouteSource.RULE, decision.source());
        assertFalse(decision.needsModelRouting());
    }

    @Test
    void ambiguousTextFallsBackToModelToolRouting() {
        AiIntentRouter.RouteDecision decision =
                router.resolveDecision(request("我二叉树掌握得不太好，帮我安排一下"));

        assertEquals(AiIntentRouter.FREE_CHAT, decision.intent());
        assertEquals(AiIntentRouter.RouteSource.FALLBACK, decision.source());
        assertTrue(decision.needsModelRouting());
    }

    @Test
    void clientDefaultFreeChatStillAllowsToolSelection() {
        AiWorkbenchRequestVM request = request("我二叉树掌握得不太好，帮我安排一下");
        request.setIntent(AiIntentRouter.FREE_CHAT);

        assertTrue(router.resolveDecision(request).needsModelRouting());
    }

    private AiWorkbenchRequestVM request(String message) {
        AiWorkbenchRequestVM request = new AiWorkbenchRequestVM();
        request.setUserMessage(message);
        return request;
    }
}
