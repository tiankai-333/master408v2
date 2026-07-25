package com.mindskip.xzs.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatoConversationPromptPolicyTest {

    @Test
    void keepsTheFullPromptOnTheFirstTurn() {
        ChatMemory memory = mock(ChatMemory.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatMemory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(memory);
        when(memory.get("memory-123")).thenReturn(List.of());
        PlatoConversationPromptPolicy policy = new PlatoConversationPromptPolicy(provider);

        assertThat(policy.prepare("plato", "original question", "memory-123", "full prompt"))
                .isEqualTo("full prompt");
    }

    @Test
    void turnsLaterInputIntoAStudentReplyWithoutRepeatingTheFullPrompt() {
        ChatMemory memory = mock(ChatMemory.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatMemory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(memory);
        when(memory.get("memory-123")).thenReturn(List.of(new AssistantMessage("one question")));
        PlatoConversationPromptPolicy policy = new PlatoConversationPromptPolicy(provider);

        String result = policy.prepare(
                "plato", "I think it is caused by locality", "memory-123", "full original prompt");

        assertThat(result)
                .contains("I think it is caused by locality")
                .contains("结合对话历史")
                .contains("只能出现一个问号")
                .contains("一个认知或计算动作")
                .doesNotContain("full original prompt");
    }

    @Test
    void neverChangesOtherAnalysisStyles() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatMemory> provider = mock(ObjectProvider.class);
        PlatoConversationPromptPolicy policy = new PlatoConversationPromptPolicy(provider);

        assertThat(policy.prepare("feynman", "reply", "memory-123", "feynman prompt"))
                .isEqualTo("feynman prompt");
    }
}
