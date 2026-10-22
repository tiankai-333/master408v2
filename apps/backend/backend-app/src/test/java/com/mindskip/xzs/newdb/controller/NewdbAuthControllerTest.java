package com.mindskip.xzs.newdb.controller;

import com.mindskip.xzs.newdb.auth.NewdbAuthTokenService;
import com.mindskip.xzs.newdb.config.NewdbProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V-06 隔离测试：登录拒绝场景必须 HTTP 401 且 body.code=401。
 * standalone MockMvc（无 Spring 应用上下文、无 DataSource/Flyway/导入器），
 * 真实 NewdbAuthTokenService + 合成配置；不覆盖成功登录（不签发 token）。
 */
class NewdbAuthControllerTest {

    private static final String USER = "unit-admin";
    private static final String PASS = "unit-pass-022";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        NewdbAuthTokenService tokenService = new NewdbAuthTokenService(
                new NewdbProperties(null, new NewdbProperties.Admin(USER, PASS), Duration.ofHours(1), null));
        mockMvc = MockMvcBuilders.standaloneSetup(new NewdbAuthController(tokenService)).build();
    }

    private static String loginBody(String username, String password) {
        return "{\"username\":" + toJson(username) + ",\"password\":" + toJson(password) + "}";
    }

    private static String toJson(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private void assertRejected(String username, String password) throws Exception {
        mockMvc.perform(post("/api/newdb/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void rejectsWrongCredentialsAsHttp401() throws Exception {
        assertRejected("unit-other", PASS);
        assertRejected(USER, "wrong-pass");
        assertRejected(null, PASS);
        assertRejected(USER, null);
    }

    @Test
    void rejectsBlankCredentialsAsHttp401() throws Exception {
        assertRejected("", PASS);
        assertRejected(" ", PASS);
        assertRejected(USER, "");
        assertRejected(USER, " ");
        assertRejected(null, null);
    }
}
