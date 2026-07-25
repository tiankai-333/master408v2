package com.mindskip.xzs.configuration.spring.security;

import com.mindskip.xzs.configuration.property.CookieConfig;
import com.mindskip.xzs.configuration.property.SystemConfig;
import com.mindskip.xzs.domain.enums.RoleEnum;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
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
 */
@Configuration
@EnableWebSecurity
public class SecurityConfigurer {

    @Bean
    public AuthenticationManager authenticationManager(RestAuthenticationProvider authenticationProvider) {
        return new ProviderManager(authenticationProvider);
    }

    @Bean
    public RestLoginAuthenticationFilter authenticationFilter(
            AuthenticationManager authenticationManager,
            RestDetailsServiceImpl detailsService,
            RestAuthenticationSuccessHandler successHandler,
            RestAuthenticationFailureHandler failureHandler) {
        RestLoginAuthenticationFilter filter = new RestLoginAuthenticationFilter();
        filter.setAuthenticationManager(authenticationManager);
        filter.setAuthenticationSuccessHandler(successHandler);
        filter.setAuthenticationFailureHandler(failureHandler);
        filter.setUserDetailsService(detailsService);
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
                        .requestMatchers("/api/admin/**").hasRole(RoleEnum.ADMIN.getName())
                        .requestMatchers("/api/student/**").hasRole(RoleEnum.STUDENT.getName())
                        .anyRequest().permitAll())
                .formLogin(form -> form
                        .successHandler(successHandler)
                        .failureHandler(failureHandler))
                .logout(logout -> logout
                        .logoutUrl("/api/user/logout")
                        .logoutSuccessHandler(logoutSuccessHandler)
                        .invalidateHttpSession(true))
                .rememberMe(remember -> remember
                        .key(CookieConfig.getName())
                        .tokenValiditySeconds(CookieConfig.getInterval())
                        .userDetailsService(detailsService))
                .securityContext(securityContext -> securityContext.requireExplicitSave(false))
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
