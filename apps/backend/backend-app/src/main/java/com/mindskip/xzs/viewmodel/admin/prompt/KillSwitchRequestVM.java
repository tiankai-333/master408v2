package com.mindskip.xzs.viewmodel.admin.prompt;

/**
 * Kill Switch：enabled=true 恢复发布（status=active），enabled=false 冻结灰度（status=disabled，仍服务 stable）。
 */
public record KillSwitchRequestVM(Boolean enabled) {
}
