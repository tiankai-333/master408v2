package com.mindskip.xzs.ai.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationMemoryServiceTest {

    @Test
    void clearsOnlyTheAuthenticatedUsersBoundConversation() {
        ChatMemory memory = mock(ChatMemory.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatMemory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(memory);
        ConversationMemoryService service = new ConversationMemoryService(
                provider, new ConversationMemoryIdFactory());

        assertThat(service.clear(7, "conversation_123")).isTrue();

        verify(memory).clear("user:7:conversation:conversation_123");
    }

    @Test
    void rejectsMissingOrMalformedConversationIdsBeforeDeletingAnything() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatMemory> provider = mock(ObjectProvider.class);
        ConversationMemoryService service = new ConversationMemoryService(
                provider, new ConversationMemoryIdFactory());

        assertThatThrownBy(() -> service.clear(7, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("conversationId 不能为空");
        assertThatThrownBy(() -> service.clear(7, "../../other-user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("conversationId 格式不正确");
    }

    @Test
    void reportsUnavailableMemoryWithoutPretendingToClear() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatMemory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ConversationMemoryService service = new ConversationMemoryService(
                provider, new ConversationMemoryIdFactory());

        assertThat(service.clear(7, "conversation_123")).isFalse();
    }
}
