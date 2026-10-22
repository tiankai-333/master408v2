package com.mindskip.xzs.newdb.controller;

import com.mindskip.xzs.base.RestResponse;
import com.mindskip.xzs.newdb.auth.NewdbAuthFilter;
import com.mindskip.xzs.newdb.auth.NewdbAuthTokenService;
import com.mindskip.xzs.newdb.viewmodel.NewdbLoginRequestVM;
import com.mindskip.xzs.newdb.viewmodel.NewdbLoginResponseVM;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 新管理端鉴权（环境变量单账号，临时方案）。
 * HTTP 401 与 body.code=401 同步给出，前端可机读。
 */
@RestController
@RequestMapping("/api/newdb/auth")
@Profile("newdb")
public class NewdbAuthController {

    private final NewdbAuthTokenService tokenService;

    public NewdbAuthController(NewdbAuthTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping("/login")
    public ResponseEntity<RestResponse<Object>> login(@RequestBody NewdbLoginRequestVM request) {
        if (!tokenService.credentialsMatch(request.username(), request.password())) {
            RestResponse<Object> body = new RestResponse<>(401, "用户名或密码错误");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }
        String token = tokenService.issue(request.username().trim());
        NewdbAuthTokenService.TokenInfo info = tokenService.validate(token).orElseThrow();
        return ResponseEntity.ok(RestResponse.ok(
                new NewdbLoginResponseVM(token, info.username(), info.expiresAt())));
    }

    @PostMapping("/logout")
    public RestResponse<Object> logout(HttpServletRequest request) {
        String token = NewdbAuthTokenService.extractToken(request.getHeader("Authorization"),
                request.getHeader("X-Newdb-Token"));
        tokenService.revoke(token);
        return RestResponse.ok("已退出");
    }

    @GetMapping("/me")
    public RestResponse<Object> me(HttpServletRequest request) {
        String token = NewdbAuthTokenService.extractToken(request.getHeader("Authorization"),
                request.getHeader("X-Newdb-Token"));
        NewdbAuthTokenService.TokenInfo info = tokenService.validate(token).orElseThrow();
        String username = (String) request.getAttribute(NewdbAuthFilter.ATTR_USERNAME);
        return RestResponse.ok(Map.of("username", username, "expiresAt", info.expiresAt()));
    }
}
