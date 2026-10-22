package com.mindskip.xzs.newdb.viewmodel;

import java.time.Instant;

/**
 * 登录响应：内存态不透明 token（重启失效，属预期）。
 */
public record NewdbLoginResponseVM(String token, String username, Instant expiresAt) {
}
