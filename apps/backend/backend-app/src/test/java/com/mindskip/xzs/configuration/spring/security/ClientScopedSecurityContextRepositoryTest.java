package com.mindskip.xzs.configuration.spring.security;

import com.mindskip.xzs.domain.enums.RoleEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the client-scoped session storage. One browser session must be able to hold the
 * student identity and the management identity at the same time, and an anonymous request must never
 * be able to erase either of them.
 */
class ClientScopedSecurityContextRepositoryTest {

    private final ClientScopedSecurityContextRepository repository = new ClientScopedSecurityContextRepository();
    private final MockHttpSession session = new MockHttpSession();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    @DisplayName("Both ends keep their own identity inside one shared session")
    void keepsOneIdentityPerEnd() {
        repository.saveContext(context("student", RoleEnum.STUDENT.getRoleName()),
                request("/api/student/login"), response);
        repository.saveContext(context("admin", RoleEnum.ADMIN.getRoleName()),
                request("/api/admin/login"), response);

        assertThat(identityOf("/api/student/user/current")).isEqualTo("student");
        assertThat(identityOf("/api/admin/user/current")).isEqualTo("admin");
    }

    @Test
    @DisplayName("Logging in on one end never replaces the other end")
    void secondLoginDoesNotReplaceFirstIdentity() {
        repository.saveContext(context("admin", RoleEnum.ADMIN.getRoleName()),
                request("/api/admin/login"), response);
        repository.saveContext(context("student", RoleEnum.STUDENT.getRoleName()),
                request("/api/student/login"), response);
        repository.saveContext(context("admin", RoleEnum.ADMIN.getRoleName()),
                request("/api/admin/login"), response);

        assertThat(identityOf("/api/student/user/current")).isEqualTo("student");
        assertThat(identityOf("/api/admin/user/current")).isEqualTo("admin");
    }

    @Test
    @DisplayName("A student identity never authorizes the management end")
    void studentIdentityDoesNotLeakIntoTheManagementEnd() {
        repository.saveContext(context("student", RoleEnum.STUDENT.getRoleName()),
                request("/api/student/login"), response);

        assertThat(load("/api/admin/user/current").getAuthentication()).isNull();
        assertThat(identityOf("/api/student/user/current")).isEqualTo("student");
    }

    @Test
    @DisplayName("An anonymous context never erases an established identity")
    void anonymousSaveIsIgnored() {
        repository.saveContext(context("student", RoleEnum.STUDENT.getRoleName()),
                request("/api/student/login"), response);

        repository.saveContext(SecurityContextHolder.createEmptyContext(),
                request("/api/student/user/current"), response);
        repository.saveContext(anonymousContext(),
                request("/api/student/user/current"), response);

        assertThat(identityOf("/api/student/user/current")).isEqualTo("student");
    }

    @Test
    @DisplayName("A legacy login is stored in the end of its own role")
    void legacyLoginLandsInTheRoleEnd() {
        repository.saveContext(context("admin", RoleEnum.ADMIN.getRoleName()),
                request("/api/user/login"), response);

        assertThat(identityOf("/api/admin/user/current")).isEqualTo("admin");
        assertThat(load("/api/student/user/current").getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Logout clears only the end that logged out")
    void clearRemovesOnlyItsOwnEnd() {
        repository.saveContext(context("student", RoleEnum.STUDENT.getRoleName()),
                request("/api/student/login"), response);
        repository.saveContext(context("admin", RoleEnum.ADMIN.getRoleName()),
                request("/api/admin/login"), response);

        repository.clearContext(request("/api/student/logout"));

        assertThat(load("/api/student/user/current").getAuthentication()).isNull();
        assertThat(identityOf("/api/admin/user/current")).isEqualTo("admin");
    }

    @Test
    @DisplayName("The legacy logout entry keeps its whole-browser semantics")
    void legacyClearRemovesEveryEnd() {
        repository.saveContext(context("student", RoleEnum.STUDENT.getRoleName()),
                request("/api/student/login"), response);
        repository.saveContext(context("admin", RoleEnum.ADMIN.getRoleName()),
                request("/api/admin/login"), response);

        repository.clearContext(request("/api/user/logout"));

        assertThat(load("/api/student/user/current").getAuthentication()).isNull();
        assertThat(load("/api/admin/user/current").getAuthentication()).isNull();
    }

    private SecurityContext load(String uri) {
        return repository.loadDeferredContext(request(uri)).get();
    }

    private String identityOf(String uri) {
        Authentication authentication = load(uri).getAuthentication();
        return authentication == null ? null : authentication.getName();
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setSession(session);
        return request;
    }

    private SecurityContext context(String userName, String authority) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(userName, "n/a",
                List.of(new SimpleGrantedAuthority(authority))));
        return context;
    }

    private SecurityContext anonymousContext() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new org.springframework.security.authentication.AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        return context;
    }
}
