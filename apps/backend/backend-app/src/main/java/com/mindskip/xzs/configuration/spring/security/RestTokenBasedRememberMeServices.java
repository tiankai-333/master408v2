package com.mindskip.xzs.configuration.spring.security;

import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;

import jakarta.servlet.http.HttpServletRequest;


/**
 * @version 3.5.0
 * @description: 记住我，Cookie
 * Copyright (C), 2020-2026, 武汉思维跳跃科技有限公司
 * @date 2021/12/25 9:45
 */
public class RestTokenBasedRememberMeServices extends TokenBasedRememberMeServices {
    /**
     * Instantiates a new Rest token based remember me services.
     *
     * @param key                the key
     * @param userDetailsService the user details service
     */
    public RestTokenBasedRememberMeServices(String key, UserDetailsService userDetailsService) {
        super(key, userDetailsService);
    }

    /**
     * The login filter decides whether the user asked to be remembered and forwards the answer as a
     * request attribute. Auto-login requests carry no such attribute, so an absent value means
     * "not requested" instead of failing.
     */
    @Override
    protected boolean rememberMeRequested(HttpServletRequest request, String parameter) {
        return Boolean.TRUE.equals(request.getAttribute(DEFAULT_PARAMETER));
    }

}
