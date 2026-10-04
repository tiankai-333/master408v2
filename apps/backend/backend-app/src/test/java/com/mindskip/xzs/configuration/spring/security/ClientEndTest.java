package com.mindskip.xzs.configuration.spring.security;

import com.mindskip.xzs.domain.enums.RoleEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the end resolution rules: the request path selects the end, and the end only
 * selects which stored authentication is used. It never grants a role.
 */
class ClientEndTest {

    @Test
    @DisplayName("Client paths resolve to their own end")
    void resolvesClientPaths() {
        assertThat(ClientEnd.resolve(request("POST", "/api/student/user/current"))).isEqualTo(ClientEnd.STUDENT);
        assertThat(ClientEnd.resolve(request("POST", "/api/student/login"))).isEqualTo(ClientEnd.STUDENT);
        assertThat(ClientEnd.resolve(request("POST", "/api/admin/user/current"))).isEqualTo(ClientEnd.ADMIN);
        assertThat(ClientEnd.resolve(request("POST", "/api/admin/login"))).isEqualTo(ClientEnd.ADMIN);
    }

    @Test
    @DisplayName("Legacy and unknown paths resolve to the shared end")
    void resolvesSharedPaths() {
        assertThat(ClientEnd.resolve(request("POST", "/api/user/login"))).isEqualTo(ClientEnd.SHARED);
        assertThat(ClientEnd.resolve(request("POST", "/api/user/logout"))).isEqualTo(ClientEnd.SHARED);
        assertThat(ClientEnd.resolve(request("POST", "/api/wx/student/user/current"))).isEqualTo(ClientEnd.SHARED);
        assertThat(ClientEnd.resolve(request("GET", "/"))).isEqualTo(ClientEnd.SHARED);
    }

    @Test
    @DisplayName("A context path in front of the API path is ignored")
    void honoursContextPath() {
        MockHttpServletRequest request = request("POST", "/xzs/api/admin/user/current");
        request.setContextPath("/xzs");
        assertThat(ClientEnd.resolve(request)).isEqualTo(ClientEnd.ADMIN);
    }

    @Test
    @DisplayName("A legacy login is stored in the end implied by its role")
    void mapsLegacyLoginToRoleEnd() {
        assertThat(ClientEnd.ofRole(authentication("student", RoleEnum.STUDENT.getRoleName())))
                .isEqualTo(ClientEnd.STUDENT);
        assertThat(ClientEnd.ofRole(authentication("admin", RoleEnum.ADMIN.getRoleName())))
                .isEqualTo(ClientEnd.ADMIN);
        assertThat(ClientEnd.ofRole(authentication("teacher", RoleEnum.TEACHER.getRoleName())))
                .isEqualTo(ClientEnd.SHARED);
    }

    @Test
    @DisplayName("Anonymous and unauthenticated contexts carry no identity")
    void anonymousCarriesNoIdentity() {
        assertThat(ClientEnd.isAnonymous(null)).isTrue();
        assertThat(ClientEnd.isAnonymous(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))))).isTrue();
        assertThat(ClientEnd.ofRole(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")))))
                .isEqualTo(ClientEnd.SHARED);
        assertThat(ClientEnd.isAnonymous(authentication("student", RoleEnum.STUDENT.getRoleName()))).isFalse();
    }

    @Test
    @DisplayName("Each end owns a distinct remember-me cookie name")
    void ownsDistinctRememberMeCookie() {
        assertThat(ClientEnd.STUDENT.getRememberMeCookieName()).isEqualTo("remember-me-student");
        assertThat(ClientEnd.ADMIN.getRememberMeCookieName()).isEqualTo("remember-me-admin");
        assertThat(ClientEnd.SHARED.getRememberMeCookieName()).isEqualTo("remember-me");
        assertThat(ClientEnd.STUDENT.getRememberMeCookieName())
                .isNotEqualTo(ClientEnd.ADMIN.getRememberMeCookieName());
    }

    private MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    private Authentication authentication(String userName, String authority) {
        return new UsernamePasswordAuthenticationToken(userName, "n/a",
                List.of(new SimpleGrantedAuthority(authority)));
    }
}
