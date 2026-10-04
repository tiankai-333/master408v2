package com.mindskip.xzs.configuration.spring.security;

import com.mindskip.xzs.domain.enums.RoleEnum;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * The client application a request belongs to.
 *
 * <p>Both web clients are served from the same host (locally two ports, in the container image two
 * paths of one origin), so the browser keeps a single cookie jar for them: one {@code JSESSIONID}
 * and one session. A session can only carry one authentication, therefore the session keeps one
 * authentication <em>per end</em> instead of one for the browser.</p>
 *
 * <p>The end only selects which stored authentication context a request is judged by. It never
 * grants an authority: a context is created exclusively by a successful login on that end, and the
 * existing {@code /api/admin/**} and {@code /api/student/**} role checks are unchanged.</p>
 */
public enum ClientEnd {

    /**
     * The student client ({@code /api/student/**}).
     */
    STUDENT("student", "/api/student/", "remember-me-student"),

    /**
     * The management client ({@code /api/admin/**}).
     */
    ADMIN("admin", "/api/admin/", "remember-me-admin"),

    /**
     * The legacy shared entry ({@code /api/user/**}). It has no client of its own; a login through
     * this entry is stored in the end implied by the authenticated role, which keeps existing
     * callers working without letting one shared identity stand in for both clients.
     */
    SHARED("shared", null, "remember-me");

    private static final String STUDENT_PATH_PREFIX = "/api/student/";
    private static final String ADMIN_PATH_PREFIX = "/api/admin/";

    private final String key;
    private final String pathPrefix;
    private final String rememberMeCookieName;

    ClientEnd(String key, String pathPrefix, String rememberMeCookieName) {
        this.key = key;
        this.pathPrefix = pathPrefix;
        this.rememberMeCookieName = rememberMeCookieName;
    }

    /**
     * Session attribute discriminator and cookie suffix of this end.
     *
     * @return the key
     */
    public String getKey() {
        return key;
    }

    /**
     * Name of this end's remember-me cookie. Per-end names keep one end's persistent credential from
     * being replayed on the other end, because the browser would otherwise share a single cookie.
     *
     * @return the remember-me cookie name
     */
    public String getRememberMeCookieName() {
        return rememberMeCookieName;
    }

    /**
     * Resolves the end of a request from its path.
     *
     * @param request the request
     * @return the resolved end, {@link #SHARED} when the path belongs to no client
     */
    public static ClientEnd resolve(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return SHARED;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        if (uri.startsWith(STUDENT_PATH_PREFIX)) {
            return STUDENT;
        }
        if (uri.startsWith(ADMIN_PATH_PREFIX)) {
            return ADMIN;
        }
        return SHARED;
    }

    /**
     * Tells whether an authentication carries no usable identity. An anonymous or empty context must
     * never be persisted, otherwise it would overwrite the identity of an end.
     *
     * @param authentication the authentication, may be {@code null}
     * @return {@code true} when there is no established identity
     */
    public static boolean isAnonymous(Authentication authentication) {
        return authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken;
    }

    /**
     * Maps an authentication to the end its role belongs to. Used only by the legacy shared entry,
     * which has no end of its own.
     *
     * @param authentication the authentication
     * @return {@link #ADMIN} or {@link #STUDENT} for the matching role, otherwise {@link #SHARED}
     */
    public static ClientEnd ofRole(Authentication authentication) {
        if (isAnonymous(authentication)) {
            return SHARED;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (RoleEnum.ADMIN.getRoleName().equals(authority.getAuthority())) {
                return ADMIN;
            }
            if (RoleEnum.STUDENT.getRoleName().equals(authority.getAuthority())) {
                return STUDENT;
            }
        }
        return SHARED;
    }
}
