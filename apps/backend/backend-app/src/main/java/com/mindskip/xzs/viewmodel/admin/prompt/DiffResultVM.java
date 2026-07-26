package com.mindskip.xzs.viewmodel.admin.prompt;

import java.util.List;

/** 两版本 Diff 结果：system 与 user 模板各自的逐行差异。 */
public record DiffResultVM(
        Long fromVersionId,
        Long toVersionId,
        List<DiffLine> system,
        List<DiffLine> user
) {

    /** type: context | add | remove */
    public record DiffLine(String type, String text) {
    }
}
