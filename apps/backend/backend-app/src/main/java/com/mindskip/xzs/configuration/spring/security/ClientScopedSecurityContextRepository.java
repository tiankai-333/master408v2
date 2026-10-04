package com.mindskip.xzs.configuration.spring.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;

import java.util.function.Supplier;

/**
 * Stores one authentication context per {@link ClientEnd} inside the browser's single session.
 *
 * <p>The default {@code HttpSessionSecurityContextRepository} keys the context by session only, so a
 * login on one client replaces the identity of the other client that shares the same cookie jar.
 * This repository keeps the contexts apart: each request is judged by the context of its own end.</p>
 *
 * <p>An anonymous or empty context is never written, so a request that simply carries no identity
 * cannot delete an identity established on another end. Clearing is explicit and happens on logout
 * through {@link #clearContext(HttpServletRequest)}.</p>
 */
public class ClientScopedSecurityContextRepository implements SecurityContextRepository {

    /**
     * Session attribute prefix; the end key is appended.
     */
    static final String ATTRIBUTE_PREFIX = "XZS_SECURITY_CONTEXT:";

    @Override
    public SecurityContext loadContext(HttpRequestResponseHolder requestResponseHolder) {
        return loadDeferredContext(requestResponseHolder.getRequest()).get();
    }

    @Override
    public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        return new EndDeferredSecurityContext(() -> read(request));
    }

    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = context == null ? null : context.getAuthentication();
        if (ClientEnd.isAnonymous(authentication)) {
            return;
        }
        ClientEnd end = ClientEnd.resolve(request);
        if (end == ClientEnd.SHARED) {
            end = ClientEnd.ofRole(authentication);
        }
        request.getSession(true).setAttribute(attributeName(end), context);
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return false;
        }
        ClientEnd end = ClientEnd.resolve(request);
        if (end != ClientEnd.SHARED) {
            return session.getAttribute(attributeName(end)) != null;
        }
        for (ClientEnd candidate : ClientEnd.values()) {
            if (session.getAttribute(attributeName(candidate)) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes the stored context of the request's end. A request on the legacy shared entry clears
     * every end, which preserves the original whole-session logout semantics for callers that still
     * use it; the two client entry points clear only their own end.
     *
     * @param request the request
     */
    public void clearContext(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        ClientEnd end = ClientEnd.resolve(request);
        if (end == ClientEnd.SHARED) {
            for (ClientEnd candidate : ClientEnd.values()) {
                session.removeAttribute(attributeName(candidate));
            }
            return;
        }
        session.removeAttribute(attributeName(end));
    }

    private SecurityContext read(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        ClientEnd end = ClientEnd.resolve(request);
        if (end != ClientEnd.SHARED) {
            return (SecurityContext) session.getAttribute(attributeName(end));
        }
        // The legacy entry carries no end, so it falls back to whichever single identity this
        // browser currently holds. All identity-bearing endpoints live under the two client
        // prefixes, so this fallback only serves the legacy entry itself.
        if (session.getAttribute(attributeName(ClientEnd.SHARED)) != null) {
            return (SecurityContext) session.getAttribute(attributeName(ClientEnd.SHARED));
        }
        for (ClientEnd candidate : new ClientEnd[]{ClientEnd.STUDENT, ClientEnd.ADMIN}) {
            if (session.getAttribute(attributeName(candidate)) != null) {
                return (SecurityContext) session.getAttribute(attributeName(candidate));
            }
        }
        return null;
    }

    static String attributeName(ClientEnd end) {
        return ATTRIBUTE_PREFIX + end.getKey();
    }

    private static final class EndDeferredSecurityContext implements DeferredSecurityContext {

        private final Supplier<SecurityContext> supplier;
        private SecurityContext context;
        private boolean generated;

        private EndDeferredSecurityContext(Supplier<SecurityContext> supplier) {
            this.supplier = supplier;
        }

        @Override
        public SecurityContext get() {
            if (context == null) {
                context = supplier.get();
                if (context == null) {
                    context = SecurityContextHolder.createEmptyContext();
                    generated = true;
                }
            }
            return context;
        }

        @Override
        public boolean isGenerated() {
            get();
            return generated;
        }
    }
}
