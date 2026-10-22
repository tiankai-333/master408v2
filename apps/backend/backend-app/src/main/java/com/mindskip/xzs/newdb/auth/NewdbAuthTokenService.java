package com.mindskip.xzs.newdb.auth;

import com.mindskip.xzs.newdb.config.NewdbProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 新管理端临时鉴权：环境变量单账号 + 内存态不透明 token（无用户表，用户域入库后替换）。
 * token 不落库、重启即失效——属预期行为，前端 401 拦截自动回登录页。
 */
@Component
@Profile("newdb")
public class NewdbAuthTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 登录态：用户名与过期时间。
     */
    public record TokenInfo(String username, Instant expiresAt) {
    }

    private record Entry(String username, Instant expiresAt) {
    }

    private final NewdbProperties properties;
    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();

    public NewdbAuthTokenService(NewdbProperties properties) {
        this.properties = properties;
    }

    public String issue(String username) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Duration ttl = properties.tokenTtl();
        tokens.put(token, new Entry(username, Instant.now().plus(ttl)));
        return token;
    }

    public Optional<TokenInfo> validate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Entry entry = tokens.get(token);
        if (entry == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(entry.expiresAt())) {
            tokens.remove(token);
            return Optional.empty();
        }
        return Optional.of(new TokenInfo(entry.username(), entry.expiresAt()));
    }

    public void revoke(String token) {
        if (token != null) {
            tokens.remove(token);
        }
    }

    /**
     * 凭据核对：账号或口令任一未配置即不匹配（不提供默认账号）；常时比较防时序侧信道。
     */
    public boolean credentialsMatch(String rawUsername, String rawPassword) {
        NewdbProperties.Admin admin = properties.admin();
        if (admin == null || isBlank(admin.username()) || isBlank(admin.password())) {
            return false;
        }
        return constantTimeEquals(rawUsername, admin.username())
                && constantTimeEquals(rawPassword, admin.password());
    }

    /**
     * 从请求头取 token：优先 Authorization: Bearer，兼容 X-Newdb-Token。
     */
    public static String extractToken(String authorizationHeader, String fallbackHeader) {
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            return authorizationHeader.substring("Bearer ".length()).trim();
        }
        return fallbackHeader;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
