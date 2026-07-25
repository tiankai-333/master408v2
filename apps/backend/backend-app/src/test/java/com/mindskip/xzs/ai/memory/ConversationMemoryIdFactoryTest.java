package com.mindskip.xzs.ai.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationMemoryIdFactoryTest {

    private final ConversationMemoryIdFactory factory =
            new ConversationMemoryIdFactory();

    @Test
    void bindsClientConversationIdToAuthenticatedUser() {
        String clientId = "65a271bd-5ce1-4380-bef7-6c964ddab822";

        assertThat(factory.create(7, clientId))
                .isEqualTo("user:7:conversation:" + clientId);
        assertThat(factory.create(8, clientId))
                .isEqualTo("user:8:conversation:" + clientId)
                .isNotEqualTo(factory.create(7, clientId));
    }

    @Test
    void rejectsUntrustedConversationIdShapes() {
        assertThatThrownBy(() -> factory.create(7, "../../other-user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conversationId");
    }
}
