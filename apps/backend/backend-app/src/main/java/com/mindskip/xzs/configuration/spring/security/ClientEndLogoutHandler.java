package com.mindskip.xzs.configuration.spring.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;

/**
 * Removes the stored authentication context of the logging-out client end.
 *
 * <p>{@code SecurityContextLogoutHandler} writes an empty context through the repository, which the
 * client-scoped repository deliberately ignores so that anonymous traffic cannot erase an identity.
 * Clearing is therefore explicit: this handler removes the context of the request's end only, and
 * the whole session is kept alive for the other end.</p>
 */
public class ClientEndLogoutHandler implements LogoutHandler {

    private final ClientScopedSecurityContextRepository securityContextRepository;

    /**
     * Instantiates a new client end logout handler.
     *
     * @param securityContextRepository the client-scoped repository
     */
    public ClientEndLogoutHandler(ClientScopedSecurityContextRepository securityContextRepository) {
        this.securityContextRepository = securityContextRepository;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        securityContextRepository.clearContext(request);
    }
}
