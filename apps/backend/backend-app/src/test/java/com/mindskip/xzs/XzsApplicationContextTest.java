package com.mindskip.xzs;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies that the migrated application can assemble its complete Spring context.
 *
 * <p>The database password remains external and is supplied through {@code DB_PASSWORD} when
 * this test runs. This test intentionally loads the real Mapper and Security configuration so
 * framework migration failures are detected before Spring AI is introduced.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/master408_v2"
                        + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                "spring.datasource.username=root"
        })
class XzsApplicationContextTest {

    @Test
    void contextLoads() {
    }
}
