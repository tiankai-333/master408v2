package com.mindskip.xzs.configuration.spring.security;

import com.mindskip.xzs.configuration.property.CookieConfig;
import com.mindskip.xzs.configuration.property.SystemConfig;
import com.mindskip.xzs.domain.enums.RoleEnum;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Collections;
import java.util.List;

/**
 * API security configuration.
 *
 * <p>The original project extended {@code WebSecurityConfigurerAdapter}. Spring Security 6
 * removed that inheritance-based API, so the same authorization contract is expressed with
 * explicit beans. Keeping the URL and handler behavior stable is important because the existing
 * web and mini-program clients depend on it.</p>
 *
 * <p>The student client and the management client are served from the same host, so they share one
 * browser cookie jar and one session. Authentication is therefore kept per client end
 * ({@link ClientEnd}) instead of once per session, each end uses its own login entry and its own
 * remember-me credential, and a logout only clears the end that asked for it.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfigurer {

    /**
     * Legacy shared logout entry; it keeps the original whole-browser logout semantics.
     */
    public static final String LEGACY_LOGOUT_URL = "/api/user/logout";

    /**
     * Student client logout entry.
     */
    public static final String STUDENT_LOGOUT_URL = "/api/student/logout";

    /**
     * Management client logout entry.
     */
    public static final String ADMIN_LOGOUT_URL = "/api/admin/logout";

    @Bean
    public AuthenticationManager authenticationManager(RestAuthenticationProvider authenticationProvider) {
        return new ProviderManager(authenticationProvider);
    }

    @Bean
    public ClientScopedSecurityContextRepository clientScopedSecurityContextRepository() {
        return new ClientScopedSecurityContextRepository();
    }

    @Bean
    public ClientScopedRememberMeServices clientScopedRememberMeServices(RestDetailsServiceImpl detailsService) {
        return new ClientScopedRememberMeServices(CookieConfig.getName(), detailsService, CookieConfig.getInterval());
    }

    @Bean
    public RestLoginAuthenticationFilter authenticationFilter(
            AuthenticationManager authenticationManager,
            RestAuthenticationSuccessHandler successHandler,
            RestAuthenticationFailureHandler failureHandler,
            ClientScopedRememberMeServices rememberMeServices,
            ClientScopedSecurityContextRepository securityContextRepository) {
        RestLoginAuthenticationFilter filter = new RestLoginAuthenticationFilter();
        filter.setAuthenticationManager(authenticationManager);
        filter.setAuthenticationSuccessHandler(successHandler);
        filter.setAuthenticationFailureHandler(failureHandler);
        filter.setRememberMeServices(rememberMeServices);
        filter.setSecurityContextRepository(securityContextRepository);
        return filter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SystemConfig systemConfig,
            RestAuthenticationProvider authenticationProvider,
            RestLoginAuthenticationFilter authenticationFilter,
            LoginAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            RestAuthenticationSuccessHandler successHandler,
            RestAuthenticationFailureHandler failureHandler,
            RestLogoutSuccessHandler logoutSuccessHandler,
            ClientScopedSecurityContextRepository securityContextRepository,
            ClientScopedRememberMeServices rememberMeServices,
            RestDetailsServiceImpl detailsService) throws Exception {

        List<String> securityIgnoreUrls = systemConfig.getSecurityIgnoreUrls();
        String[] ignores = securityIgnoreUrls.toArray(String[]::new);

        http
                .headers(headers -> headers.frameOptions(frameOptions -> frameOptions.disable()))
                .addFilterAt(authenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .authenticationProvider(authenticationProvider)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(ignores).permitAll()
                        .requestMatchers(HttpMethod.POST,
                                RestLoginAuthenticationFilter.STUDENT_LOGIN_URL,
                                RestLoginAuthenticationFilter.ADMIN_LOGIN_URL).permitAll()
                        .requestMatchers("/api/admin/**").hasRole(RoleEnum.ADMIN.getName())
                        .requestMatchers("/api/student/**").hasRole(RoleEnum.STUDENT.getName())
                        // 公网部署（specs/2026-09-23-public-deploy PD-05）：默认拒绝替代默认放行。
                        // dev 依赖 ignore-urls 首位匹配保留 /api/test/** 调试入口；生产 ignore 表为空，
                        // 调试入口在此被显式拒绝，其余未列路径一律要求认证（默认拒绝）。
                        .requestMatchers("/api/test/**").denyAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .successHandler(successHandler)
                        .failureHandler(failureHandler))
                .logout(logout -> logout
                        .logoutRequestMatcher(new OrRequestMatcher(
                                PathPatternRequestMatcher.pathPattern(LEGACY_LOGOUT_URL),
                                PathPatternRequestMatcher.pathPattern(STUDENT_LOGOUT_URL),
                                PathPatternRequestMatcher.pathPattern(ADMIN_LOGOUT_URL)))
                        .logoutSuccessHandler(logoutSuccessHandler)
                        .invalidateHttpSession(false)
                        .addLogoutHandler(new ClientEndLogoutHandler(securityContextRepository))
                        .addLogoutHandler(rememberMeServices))
                .rememberMe(remember -> remember
                        .key(CookieConfig.getName())
                        .tokenValiditySeconds(CookieConfig.getInterval())
                        .userDetailsService(detailsService)
                        .rememberMeServices(rememberMeServices))
                .securityContext(securityContext -> securityContext
                        .securityContextRepository(securityContextRepository)
                        .requireExplicitSave(false))
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()));

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setMaxAge(3600L);
        configuration.setAllowedOriginPatterns(Collections.singletonList("*"));
        configuration.setAllowedMethods(Collections.singletonList("*"));
        configuration.setAllowCredentials(true);
        configuration.setAllowedHeaders(Collections.singletonList("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
