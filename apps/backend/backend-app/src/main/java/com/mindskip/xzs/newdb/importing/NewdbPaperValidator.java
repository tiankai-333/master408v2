package com.mindskip.xzs.newdb.importing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 整卷导入前校验与 raw 组装（纯函数，不碰库）。
 * 口径：单选（type=1）必须 4 选项 + correct∈ABCD；综合（type=5）correct=NULL 合法，
 * 解析缺失不拒收——display_ready=0 + 原因码（不删分母、不静默丢）。
 * 题号必须 1..47 连续且与提取器 questionTotal 一致；复杂内容盘点（SVG/代码/表格/公式）
 * 消毒前后计数一致才算结构完整，不一致整题拒绝。
 */
public final class NewdbPaperValidator {

    public static final Set<String> SUBJECT_WHITELIST =
            Set.of("数据结构", "计算机组成原理", "操作系统", "计算机网络");

    /** 提取器 h4 段名 → 学科规范名（与 NewdbQuestionValidator 口径对齐）。 */
    private static final Map<String, String> SUBJECT_CANONICAL = Map.of(
            "数据结构", "数据结构",
            "组成原理", "计算机组成原理",
            "操作系统", "操作系统",
            "计算机网络", "计算机网络");

    private static final Set<String> ANSWER_KEYS = Set.of("A", "B", "C", "D");
    private static final int MC_COUNT = 40;
    private static final int COMP_COUNT = 7;
    private static final int TOTAL = MC_COUNT + COMP_COUNT;

    private NewdbPaperValidator() {
    }

    public sealed interface Result permits Accepted, Rejected {
    }

    public record Accepted(PreparedPaper prepared) implements Result {
    }

    public record Rejected(List<String> violations) implements Result {
    }

    /**
     * 可写库的整卷：questions 有序（卷面题号序），raw 已消毒、stem_text 已派生。
     */
    public record PreparedPaper(String paperName, int year, int paperType, String sourceUrl,
                                String sourceSha256, List<String> warnings,
                                List<PreparedQuestion> questions) {
    }

    public record PreparedQuestion(int no, String subject, int questionType, String sourceRef,
                                   String stemHtml, String analysisHtml, String stemText,
                                   String correct, String displayReady, String statusReason,
                                   String assetsJson, List<String> tags) {
    }

    public static Result validate(NewdbPaperRecord paper) {
        List<String> violations = new ArrayList<>();
        if (paper.questions() == null || paper.questions().size() != TOTAL) {
            violations.add("题目数 %s ≠ %d".formatted(
                    paper.questions() == null ? "null" : paper.questions().size(), TOTAL));
            if (paper.questions() == null) {
                return new Rejected(violations);
            }
            // 不提前返回：继续核对题号断档，给出完整拒绝原因
        }
        Map<Integer, NewdbPaperRecord.Question> byNo = new LinkedHashMap<>();
        for (NewdbPaperRecord.Question q : paper.questions()) {
            if (byNo.put(q.no(), q) != null) {
                violations.add("题号重复：" + q.no());
            }
        }
        for (int no = 1; no <= TOTAL; no++) {
            if (!byNo.containsKey(no)) {
                violations.add("题号断档：" + no);
            }
        }
        if (paper.questionTotal() != null && paper.questionTotal() != TOTAL) {
            violations.add("提取器 questionTotal=%d 与实际 %d 不一致"
                    .formatted(paper.questionTotal(), TOTAL));
        }
        if (!violations.isEmpty()) {
            return new Rejected(violations);
        }

        List<PreparedQuestion> prepared = new ArrayList<>();
        for (int no = 1; no <= TOTAL; no++) {
            NewdbPaperRecord.Question q = byNo.get(no);
            String canonical = SUBJECT_CANONICAL.get(q.subject());
            if (canonical == null) {
                violations.add("Q%d 学科越界：%s".formatted(no, q.subject()));
                continue;
            }
            if (q.sourceRef() == null || q.sourceRef().isBlank()) {
                violations.add("Q%d 缺 source_ref（幂等业务键）".formatted(no));
                continue;
            }
            String stem = q.stemHtml() == null ? "" : q.stemHtml();
            if (stem.isBlank()) {
                violations.add("Q%d 题干为空".formatted(no));
                continue;
            }
            String analysis = q.analysisHtml();
            String displayReady = "1";
            String statusReason = null;
            if (q.questionType() == 1) {
                if (q.options() == null || q.options().size() != 4
                        || !ANSWER_KEYS.contains(q.correct())) {
                    violations.add("Q%d 单选必须 4 选项 + correct∈ABCD".formatted(no));
                    continue;
                }
            } else if (q.questionType() == 5) {
                if (q.correct() != null) {
                    violations.add("Q%d 综合题 correct 必须为 NULL（判分口径 D-28）".formatted(no));
                    continue;
                }
                if (analysis == null || analysis.isBlank()) {
                    // 不拒收：来源缺解析如实入库，上线阀关闭
                    displayReady = "0";
                    statusReason = "来源缺失：无参考答案/解析";
                    analysis = null;
                }
            } else {
                violations.add("Q%d 题型越界：%d".formatted(no, q.questionType()));
                continue;
            }
            try {
                prepared.add(prepare(no, canonical, q, stem, analysis,
                        displayReady, statusReason));
            } catch (IllegalStateException e) {
                violations.add("Q%d %s".formatted(no, e.getMessage()));
            }
        }
        if (!violations.isEmpty()) {
            return new Rejected(violations);
        }
        return new Accepted(new PreparedPaper(paper.paperName(), paper.year(),
                paper.paperType() == null ? 1 : paper.paperType(), paper.sourceUrl(),
                paper.sourceSha256(), paper.warnings() == null ? List.of() : paper.warnings(),
                prepared));
    }

    private static PreparedQuestion prepare(int no, String subject, NewdbPaperRecord.Question q,
                                            String stem, String analysis,
                                            String displayReady, String statusReason) {
        // 缺失资源（外链图无本地副本）→ 上线阀关闭 + 原因码（不静默、不删分母）
        List<String> missingImages = q.assets() == null || q.assets().missingImages() == null
                ? List.of() : q.assets().missingImages();
        String mergedReason = statusReason;
        String mergedReady = displayReady;
        if (!missingImages.isEmpty()) {
            mergedReady = "0";
            String reason = "缺失资源：外链图无本地副本 " + missingImages.size() + " 张";
            mergedReason = mergedReason == null ? reason : mergedReason + "；" + reason;
        }
        String rawStem;
        if (q.questionType() == 1) {
            StringBuilder opts = new StringBuilder();
            for (NewdbPaperRecord.Option o : q.options()) {
                opts.append("<p class=\"q-option\" data-key=\"").append(o.key()).append("\">")
                        .append(o.key()).append(". ").append(o.html() == null ? "" : o.html())
                        .append("</p>");
            }
            rawStem = stem + "<div class=\"q-options\">" + opts + "</div>";
        } else {
            rawStem = stem;
        }
        String cleanStem = HtmlSanitizer.cleanPaper(rawStem);
        String cleanAnalysis = analysis == null ? null : HtmlSanitizer.cleanPaper(analysis);
        verifyStructure("题干", stem, cleanStem);
        if (cleanAnalysis != null) {
            verifyStructure("解析", analysis, cleanAnalysis);
        }
        String assetsJson = paperAssetsJson(q.assets(), q.tags() == null ? List.of() : q.tags(),
                missingImages, q.analysisSource());
        return new PreparedQuestion(no, subject, q.questionType(), q.sourceRef(),
                cleanStem, cleanAnalysis, HtmlSanitizer.toText(cleanStem),
                q.correct(), mergedReady, mergedReason, assetsJson,
                q.tags() == null ? List.of() : q.tags());
    }

    /**
     * 结构完整性：消毒前后 table/pre/svg/合法img 计数一致，且 SVG 内嵌文字
     * （foreignObject 节点文字/箭头 image）逐一保留，否则拒绝（复杂内容不静默丢失）。
     */
    private static void verifyStructure(String part, String raw, String clean) {
        requireEqual(part, "table", count(raw, "<table"), HtmlSanitizer.countTables(clean));
        requireEqual(part, "pre", count(raw, "<pre"), HtmlSanitizer.countCodeBlocks(clean));
        requireEqual(part, "svg", count(raw, "<svg"), HtmlSanitizer.countInlineSvg(clean));
        requireEqual(part, "svg-image", count(raw, "<image"), HtmlSanitizer.countSvgImages(clean));
        String rawText = HtmlSanitizer.svgEmbeddedText(raw);
        String cleanText = HtmlSanitizer.svgEmbeddedText(clean);
        if (!rawText.equals(cleanText)) {
            throw new IllegalStateException(part + "消毒后 SVG 文字内容改变（raw=" + rawText
                    + " clean=" + cleanText + "）");
        }
    }

    private static void requireEqual(String part, String kind, int rawCount, int cleanCount) {
        if (rawCount != cleanCount) {
            throw new IllegalStateException(
                    "%s消毒后 %s 丢失（%d→%d）".formatted(part, kind, rawCount, cleanCount));
        }
    }

    private static int count(String html, String tag) {
        // tag 为固定字面量（<table/<pre/<svg），split 正则无特殊字符
        return html.split(tag, -1).length - 1;
    }

    /** assets 盘点 → 候选库扩展列 JSON（机读对账用，不参与判分）。tags/缺失图并入同一 JSON。 */
    private static String paperAssetsJson(NewdbPaperRecord.Assets a, List<String> tags,
                                          List<String> missingImages,
                                          java.util.Map<String, Object> analysisSource) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("external_images", a == null || a.externalImages() == null ? List.of() : a.externalImages());
        m.put("local_images", a == null || a.localImages() == null ? List.of() : a.localImages());
        m.put("missing_images", missingImages);
        m.put("tags", tags);
        // 单选解析补入来源（legacy 交叉对账产物；HTML 源解析无此键）
        m.put("analysis_source", analysisSource == null ? Map.of() : analysisSource);
        m.put("inline_svg", a == null || a.inlineSvg() == null ? 0 : a.inlineSvg());
        m.put("katex_spans", a == null || a.katexSpans() == null ? 0 : a.katexSpans());
        m.put("code_blocks", a == null || a.codeBlocks() == null ? 0 : a.codeBlocks());
        m.put("tables", a == null || a.tables() == null ? 0 : a.tables());
        try {
            return NewdbPaperRecord.MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("assets_json 序列化失败", e);
        }
    }
}
