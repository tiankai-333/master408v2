package com.mindskip.xzs.service;

import com.mindskip.xzs.viewmodel.student.ai.AiWorkbenchContextVM;
import com.mindskip.xzs.viewmodel.student.ai.AiWorkbenchRequestVM;
import org.springframework.stereotype.Service;

@Service
public class AiIntentRouter {

    public static final String EXPLAIN_QUESTION = "explain_question";
    public static final String EXPLAIN_KNOWLEDGE = "explain_knowledge";
    public static final String LEARNING_PROFILE = "learning_profile";
    public static final String PRACTICE_PLAN = "practice_plan";
    public static final String COMPOSE_PAPER = "compose_paper";
    public static final String FREE_CHAT = "free_chat";

    public String resolve(AiWorkbenchRequestVM request) {
        return resolveDecision(request).intent();
    }

    public RouteDecision resolveDecision(AiWorkbenchRequestVM request) {
        String explicit = normalize(request == null ? null : request.getIntent());
        if (FREE_CHAT.equals(explicit)) {
            // The current web client sends free_chat as its default rather than as a
            // deliberate routing command. Keep this path eligible for model tool selection.
            return new RouteDecision(FREE_CHAT, RouteSource.FALLBACK, 0.2);
        }
        if (isSupported(explicit)) {
            return new RouteDecision(explicit, RouteSource.EXPLICIT, 1.0);
        }

        String message = normalize(request == null ? null : request.getUserMessage());
        if (looksLikePractice(message)) {
            return new RouteDecision(PRACTICE_PLAN, RouteSource.RULE, 0.9);
        }
        if (looksLikeProfile(message)) {
            return new RouteDecision(LEARNING_PROFILE, RouteSource.RULE, 0.9);
        }
        if (looksLikeCompose(message)) {
            return new RouteDecision(COMPOSE_PAPER, RouteSource.RULE, 0.95);
        }

        AiWorkbenchContextVM context = request == null ? null : request.getContext();
        if (context != null) {
            if (context.getQuestion() != null || normalize(context.getPastedText()) != null) {
                return new RouteDecision(EXPLAIN_QUESTION, RouteSource.CONTEXT, 0.85);
            }
            if (context.getKnowledgePoint() != null) {
                return new RouteDecision(EXPLAIN_KNOWLEDGE, RouteSource.CONTEXT, 0.8);
            }
        }

        return new RouteDecision(FREE_CHAT, RouteSource.FALLBACK, 0.2);
    }

    public record RouteDecision(String intent, RouteSource source, double confidence) {
        public boolean needsModelRouting() {
            return source == RouteSource.FALLBACK;
        }
    }

    public enum RouteSource {
        EXPLICIT, RULE, CONTEXT, FALLBACK
    }

    private boolean isSupported(String intent) {
        return EXPLAIN_QUESTION.equals(intent)
                || EXPLAIN_KNOWLEDGE.equals(intent)
                || LEARNING_PROFILE.equals(intent)
                || PRACTICE_PLAN.equals(intent)
                || COMPOSE_PAPER.equals(intent)
                || FREE_CHAT.equals(intent);
    }

    private boolean looksLikePractice(String message) {
        return message != null && message.matches(".*(练习|组卷|出题|挑选|生成.*卷|针对.*题|同类题).*");
    }

    private boolean looksLikeProfile(String message) {
        return message != null && message.matches(".*(学习画像|薄弱点|学习状态|掌握情况|复习建议|正确率).*");
    }

    private boolean looksLikeCompose(String message) {
        return message != null && (message.contains("/compose paper") || message.matches(".*(直接建卷|确认生成试卷).*"));
    }

    private String normalize(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
