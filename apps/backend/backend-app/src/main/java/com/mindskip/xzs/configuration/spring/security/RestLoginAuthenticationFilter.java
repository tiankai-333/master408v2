package com.mindskip.xzs.configuration.spring.security;

import com.mindskip.xzs.utility.JsonUtil;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;


/**
 * @version 3.5.0
 * @description: 登录参数序列化
 * Copyright (C), 2020-2026, 武汉思维跳跃科技有限公司
 * @date 2021/12/25 9:45
 */
public class RestLoginAuthenticationFilter extends AbstractAuthenticationProcessingFilter {

    /**
     * Legacy shared login entry, kept for callers written against the original single-client API.
     */
    public static final String LEGACY_LOGIN_URL = "/api/user/login";

    /**
     * Student client login entry.
     */
    public static final String STUDENT_LOGIN_URL = "/api/student/login";

    /**
     * Management client login entry.
     */
    public static final String ADMIN_LOGIN_URL = "/api/admin/login";

    private final org.slf4j.Logger logger = LoggerFactory.getLogger(RestLoginAuthenticationFilter.class);

    /**
     * Instantiates a new Rest login authentication filter.
     *
     * <p>Each client declares its end through its own entry point; the entry only selects the
     * authentication context the login is stored in, and the granted role still comes from the
     * account itself.</p>
     */
    public RestLoginAuthenticationFilter() {
        super(new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, LEGACY_LOGIN_URL),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, STUDENT_LOGIN_URL),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, ADMIN_LOGIN_URL)));
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response) throws AuthenticationException {
        UsernamePasswordAuthenticationToken authRequest;
        try (InputStream is = request.getInputStream()) {
            AuthenticationBean authenticationBean = JsonUtil.toJsonObject(is, AuthenticationBean.class);
            if (authenticationBean == null || authenticationBean.getUserName() == null || authenticationBean.getPassword() == null) {
                throw new BadCredentialsException("用户名或密码错误");
            }
            request.setAttribute(TokenBasedRememberMeServices.DEFAULT_PARAMETER, authenticationBean.isRemember());
            authRequest = new UsernamePasswordAuthenticationToken(authenticationBean.getUserName(), authenticationBean.getPassword());
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            authRequest = new UsernamePasswordAuthenticationToken("", "");
        }
        setDetails(request, authRequest);
        return this.getAuthenticationManager().authenticate(authRequest);

    }

    private void setDetails(HttpServletRequest request, UsernamePasswordAuthenticationToken authRequest) {
        authRequest.setDetails(authenticationDetailsSource.buildDetails(request));
    }
}
