package com.mindskip.xzs.utility;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class Utf8SseEventWriterTest {

    @Test
    void explicitlyUsesUtf8ForChineseEventData() {
        assertThat(Utf8SseEventWriter.TEXT_PLAIN_UTF8.getCharset())
                .isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void preparesServletResponseAsUtf8EventStream() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        Utf8SseEventWriter.prepareResponse(response);

        assertThat(response.getCharacterEncoding()).isEqualTo(StandardCharsets.UTF_8.name());
        assertThat(response.getContentType()).isEqualTo("text/event-stream;charset=UTF-8");
    }
}
