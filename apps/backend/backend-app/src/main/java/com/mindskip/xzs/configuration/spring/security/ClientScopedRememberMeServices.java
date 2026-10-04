package com.mindskip.xzs.configuration.spring.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.logout.LogoutHandler;

import java.util.EnumMap;
import java.util.Map;

/**
 * Keeps one remember-me credential per {@link ClientEnd}.
 *
 * <p>A browser shares cookies by host, so a single remember-me cookie would let one end restore the
 * account that was remembered on the other end. Each end therefore owns its own cookie name
 * ({@code remember-me-student} / {@code remember-me-admin}), while the legacy entry keeps the
 * original {@code remember-me} cookie.</p>
 */
public class ClientScopedRememberMeServices implements RememberMeServices, LogoutHandler {

    private final Map<ClientEnd, RestTokenBasedRememberMeServices> delegates = new EnumMap<>(ClientEnd.class);

    /**
     * Instantiates a new client scoped remember me services.
     *
     * @param key                 the signing key
     * @param userDetailsService  the user details service used to restore the account
     * @param tokenValiditySeconds the credential lifetime in seconds
     */
    public ClientScopedRememberMeServices(String key, UserDetailsService userDetailsService, int tokenValiditySeconds) {
        for (ClientEnd end : ClientEnd.values()) {
            RestTokenBasedRememberMeServices services = new RestTokenBasedRememberMeServices(key, userDetailsService);
            services.setCookieName(end.getRememberMeCookieName());
            services.setTokenValiditySeconds(tokenValiditySeconds);
            delegates.put(end, services);
        }
    }

    @Override
    public Authentication autoLogin(HttpServletRequest request, HttpServletResponse response) {
        return delegate(request).autoLogin(request, response);
    }

    @Override
    public void loginFail(HttpServletRequest request, HttpServletResponse response) {
        delegate(request).loginFail(request, response);
    }

    @Override
    public void loginSuccess(HttpServletRequest request, HttpServletResponse response,
                             Authentication successfulAuthentication) {
        delegate(request).loginSuccess(request, response, successfulAuthentication);
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        ClientEnd end = ClientEnd.resolve(request);
        if (end != ClientEnd.SHARED) {
            delegate(request).logout(request, response, authentication);
            return;
        }
        // The legacy entry logs the whole browser out, so it also drops every end credential;
        // leaving one behind would silently restore an identity right after the logout.
        for (ClientEnd candidate : ClientEnd.values()) {
            delegates.get(candidate).logout(request, response, authentication);
        }
    }

    private RestTokenBasedRememberMeServices delegate(HttpServletRequest request) {
        return delegates.get(ClientEnd.resolve(request));
    }
}
