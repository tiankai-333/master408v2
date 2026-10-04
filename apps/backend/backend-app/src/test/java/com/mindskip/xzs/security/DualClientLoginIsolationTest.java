package com.mindskip.xzs.security;

import com.mindskip.xzs.domain.User;
import com.mindskip.xzs.listener.UserLogListener;
import com.mindskip.xzs.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

import jakarta.servlet.http.Cookie;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Regression coverage for the management-client / student-client account conflict.
 *
 * <p>Both clients run on the same host, so the browser shares one cookie jar: a shared
 * {@code JSESSIONID} and a shared remember-me cookie. The contract under test is that an
 * admin login over the admin entry point must not replace the student identity that was
 * established over the student entry point in the same browser session, and vice versa.</p>
 *
 * <p>The test drives the real Spring Security filter chain through MockMvc while replacing
 * {@link UserService} with a stub, so no account row in the database is created, modified or
 * deleted and no login event is written for the test accounts.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/master408_v2"
                        + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                "spring.datasource.username=root"
        })
class DualClientLoginIsolationTest {

    private static final String TEST_PASSWORD = "dual-client-isolation-test";
    private static final String STUDENT_LOGIN = "/api/student/login";
    private static final String ADMIN_LOGIN = "/api/admin/login";
    private static final String STUDENT_CURRENT = "/api/student/user/current";
    private static final String ADMIN_CURRENT = "/api/admin/user/current";

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserLogListener userLogListener;

    private MockMvc mockMvc;

    /** One browser: every request shares this session, exactly like one shared cookie jar. */
    private final MockHttpSession browser = new MockHttpSession();

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(context).apply(springSecurity()).build();
        String encoded = new BCryptPasswordEncoder().encode(TEST_PASSWORD);
        when(userService.getUserByUserName("student")).thenReturn(user(2, "student", 1, encoded));
        when(userService.getUserByUserName("admin")).thenReturn(user(1, "admin", 3, encoded));
    }

    @Test
    @DisplayName("AUTH-01: student first, then admin - both ends keep their own identity")
    void studentThenAdminKeepsBothIdentities() throws Exception {
        login(STUDENT_LOGIN, "student").andExpect(jsonPath("$.code").value(1));
        current(STUDENT_CURRENT).andExpect(jsonPath("$.response.userName").value("student"));

        login(ADMIN_LOGIN, "admin").andExpect(jsonPath("$.code").value(1));
        current(ADMIN_CURRENT).andExpect(jsonPath("$.response.userName").value("admin"));

        current(STUDENT_CURRENT).andExpect(jsonPath("$.response.userName").value("student"));
    }

    @Test
    @DisplayName("AUTH-01: admin first, then student - both ends keep their own identity")
    void adminThenStudentKeepsBothIdentities() throws Exception {
        login(ADMIN_LOGIN, "admin").andExpect(jsonPath("$.code").value(1));
        current(ADMIN_CURRENT).andExpect(jsonPath("$.response.userName").value("admin"));

        login(STUDENT_LOGIN, "student").andExpect(jsonPath("$.code").value(1));
        current(STUDENT_CURRENT).andExpect(jsonPath("$.response.userName").value("student"));

        current(ADMIN_CURRENT).andExpect(jsonPath("$.response.userName").value("admin"));
    }

    @Test
    @DisplayName("AUTH-02: logout on one end leaves the other end authenticated")
    void logoutAffectsOnlyItsOwnEnd() throws Exception {
        login(STUDENT_LOGIN, "student").andExpect(jsonPath("$.code").value(1));
        login(ADMIN_LOGIN, "admin").andExpect(jsonPath("$.code").value(1));

        logout("/api/student/logout");

        current(ADMIN_CURRENT).andExpect(jsonPath("$.response.userName").value("admin"));
        current(STUDENT_CURRENT).andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("AUTH-02: a failed login on one end does not disturb the other end")
    void failedLoginDoesNotDisturbTheOtherEnd() throws Exception {
        login(ADMIN_LOGIN, "admin").andExpect(jsonPath("$.code").value(1));

        mockMvc.perform(post(STUDENT_LOGIN).session(browser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("student", "definitely-wrong")))
                .andExpect(jsonPath("$.code").value(402));

        current(ADMIN_CURRENT).andExpect(jsonPath("$.response.userName").value("admin"));
    }

    @Test
    @DisplayName("AUTH-03: a student session cannot reach the admin API")
    void studentSessionCannotReachAdminApi() throws Exception {
        login(STUDENT_LOGIN, "student").andExpect(jsonPath("$.code").value(1));

        current(ADMIN_CURRENT).andExpect(jsonPath("$.code").value(401));
        current(STUDENT_CURRENT).andExpect(jsonPath("$.response.userName").value("student"));
    }

    @Test
    @DisplayName("AUTH-05: remember-me is stored per end and only restores its own end")
    void rememberMeIsScopedPerEnd() throws Exception {
        Cookie studentRememberMe = rememberMeCookieOf(login(STUDENT_LOGIN, "student", true));
        Cookie adminRememberMe = rememberMeCookieOf(login(ADMIN_LOGIN, "admin", true));

        assertThat(studentRememberMe.getName()).isEqualTo("remember-me-student");
        assertThat(adminRememberMe.getName()).isEqualTo("remember-me-admin");

        // Session expired: only the student end's remember-me credential is presented.
        mockMvc.perform(post(STUDENT_CURRENT).cookie(studentRememberMe)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.response.userName").value("student"));
        mockMvc.perform(post(ADMIN_CURRENT).cookie(studentRememberMe)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("The legacy shared login entry still authorizes the end that matches its role")
    void legacyLoginEntryStillWorks() throws Exception {
        login("/api/user/login", "student").andExpect(jsonPath("$.code").value(1));

        current(STUDENT_CURRENT).andExpect(jsonPath("$.response.userName").value("student"));
        current(ADMIN_CURRENT).andExpect(jsonPath("$.code").value(401));
    }

    private ResultActions login(String url, String userName) throws Exception {
        return login(url, userName, false);
    }

    private ResultActions login(String url, String userName, boolean remember) throws Exception {
        String body = "{\"userName\":\"" + userName + "\",\"password\":\"" + TEST_PASSWORD + "\""
                + ",\"remember\":" + remember + "}";
        return mockMvc.perform(post(url).session(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .header("request-ajax", "true")
                .content(body));
    }

    private ResultActions logout(String url) throws Exception {
        return mockMvc.perform(post(url).session(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .header("request-ajax", "true"));
    }

    private ResultActions current(String url) throws Exception {
        return mockMvc.perform(post(url).session(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .header("request-ajax", "true"));
    }

    private String credentials(String userName, String password) {
        return "{\"userName\":\"" + userName + "\",\"password\":\"" + password + "\"}";
    }

    private Cookie rememberMeCookieOf(ResultActions actions) throws Exception {
        MvcResult result = actions.andReturn();
        Optional<Cookie> cookie = java.util.Arrays.stream(result.getResponse().getCookies())
                .filter(candidate -> candidate.getName().startsWith("remember-me"))
                .findFirst();
        assertThat(cookie).as("remember-me cookie was issued").isPresent();
        return cookie.get();
    }

    private User user(int id, String userName, int role, String encodedPassword) {
        User user = new User();
        user.setId(id);
        user.setUserName(userName);
        user.setRealName(userName);
        user.setRole(role);
        user.setStatus(1);
        user.setPassword(encodedPassword);
        user.setUserUuid(UUID.randomUUID().toString());
        user.setDeleted(false);
        return user;
    }
}
