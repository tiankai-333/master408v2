package com.mindskip.xzs.newdb.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * /api/newdb/** 统一鉴权（Spring Security 已对前缀放行，见 application.yml ignore-urls）。
 *
 * 实现说明：本应用旧 MVC 配置继承 WebMvcConfigurationSupport（不委托 WebMvcConfigurer），
 * HandlerInterceptor 注册方式对旧配置不可见，故用 Servlet Filter（Boot 对 Filter bean
 * 全局生效，与 MVC 配置方式无关；旧文件零改动）。未登录/凭证失效 → HTTP 401 + 机读 body。
 */
@Component
@Profile("newdb")
public class NewdbAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_USERNAME = "newdb.username";

    private static final String LOGIN_PATH = "/api/newdb/auth/login";

    private final NewdbAuthTokenService tokenService;

    public NewdbAuthFilter(NewdbAuthTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (!path.startsWith("/api/newdb/")) {
            return true;
        }
        return LOGIN_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = NewdbAuthTokenService.extractToken(request.getHeader("Authorization"),
                request.getHeader("X-Newdb-Token"));
        Optional<NewdbAuthTokenService.TokenInfo> info = tokenService.validate(token);
        if (info.isEmpty()) {
            unauthorized(response);
            return;
        }
        request.setAttribute(ATTR_USERNAME, info.get().username());
        filterChain.doFilter(request, response);
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"未登录或凭证失效\",\"response\":null}");
    }
}
