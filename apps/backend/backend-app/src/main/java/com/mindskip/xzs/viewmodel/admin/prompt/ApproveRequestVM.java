package com.mindskip.xzs.viewmodel.admin.prompt;

/** 审批通过并开始灰度：percent 为初始灰度比例（0-100）。 */
public record ApproveRequestVM(
        Integer percent,
        String comment
) {
}
