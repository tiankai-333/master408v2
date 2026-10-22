package com.mindskip.xzs.newdb.auth;

import com.mindskip.xzs.newdb.config.NewdbProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V-06 隔离单元测试：临时管理账号凭据核对。
 * 直接构造 NewdbProperties，不起 Spring 上下文、不连任何数据库。
 * 凭据全部为合成值，与环境变量/真实口令无关。
 */
class NewdbAuthTokenServiceTest {

    private static final String USER = "unit-admin";
    private static final String PASS = "unit-pass-022";

    private static NewdbAuthTokenService service(NewdbProperties.Admin admin) {
        return new NewdbAuthTokenService(new NewdbProperties(null, admin, Duration.ofHours(1), null));
    }

    @Test
    void rejectsWhenAdminUnconfigured() {
        assertFalse(service(null).credentialsMatch(USER, PASS), "admin 未配置（null）必须拒绝");
    }

    @Test
    void rejectsWhenConfiguredUsernameNullOrBlank() {
        assertFalse(service(new NewdbProperties.Admin(null, PASS)).credentialsMatch(USER, PASS));
        assertFalse(service(new NewdbProperties.Admin("   ", PASS)).credentialsMatch(USER, PASS));
    }

    @Test
    void rejectsWhenConfiguredPasswordNullOrBlank() {
        assertFalse(service(new NewdbProperties.Admin(USER, null)).credentialsMatch(USER, PASS));
        assertFalse(service(new NewdbProperties.Admin(USER, "   ")).credentialsMatch(USER, PASS));
    }

    @Test
    void rejectsWhenBothConfiguredFieldsEmpty() {
        assertFalse(service(new NewdbProperties.Admin(null, null)).credentialsMatch(USER, PASS));
        assertFalse(service(new NewdbProperties.Admin("", "")).credentialsMatch(USER, PASS));
    }

    @Test
    void rejectsWrongUsername() {
        NewdbAuthTokenService service = service(new NewdbProperties.Admin(USER, PASS));
        assertFalse(service.credentialsMatch("unit-other", PASS));
        assertFalse(service.credentialsMatch(null, PASS));
    }

    @Test
    void rejectsWrongPassword() {
        NewdbAuthTokenService service = service(new NewdbProperties.Admin(USER, PASS));
        assertFalse(service.credentialsMatch(USER, "wrong-pass"));
        assertFalse(service.credentialsMatch(USER, null));
    }

    @Test
    void acceptsSyntheticPositiveControl() {
        NewdbAuthTokenService service = service(new NewdbProperties.Admin(USER, PASS));
        assertTrue(service.credentialsMatch(USER, PASS), "合成正确凭据必须匹配（正向对照）");
        // 空白输入同样不匹配已配置账号
        assertFalse(service.credentialsMatch(" ", PASS));
        assertFalse(service.credentialsMatch(USER, " "));
    }
}
