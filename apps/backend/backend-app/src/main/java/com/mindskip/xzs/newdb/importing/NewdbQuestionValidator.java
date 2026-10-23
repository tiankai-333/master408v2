package com.mindskip.xzs.newdb.importing;

import java.util.List;
import java.util.Set;

/**
 * 导入前校验与 raw 组装（纯函数，不碰库）。
 * 校验失败逐条拒绝（附机读原因码），不中断整批；通过则产出含消毒后 raw 的 Prepared。
 */
public final class NewdbQuestionValidator {

    /** 学科白名单：408 四科（旧库 t_subject 口径，防脏学科入库）。 */
    public static final Set<String> SUBJECT_WHITELIST =
            Set.of("数据结构", "计算机组成原理", "操作系统", "计算机网络");

    private static final Set<String> ANSWER_KEYS = Set.of("A", "B", "C", "D");
    private static final int MAX_STEM_LENGTH = 20000;

    private NewdbQuestionValidator() {
    }

    /**
     * 拒绝原因：机读码 + 来源定位。
     */
    public record Violation(Integer year, Integer questionNumber, String reason) {
        public String describe() {
            return year + "-Q" + questionNumber + ": " + reason;
        }
    }

    /**
     * 通过校验、可写库的题目：raw 已组装并消毒，stem_text 已派生。
     */
    public record Prepared(NewdbQuestionRecord source, List<String> options, String sourceRef,
                           String stemHtml, String analysisHtml, String stemText) {

        public String correct() {
            return source.answer().trim();
        }

        public String subject() {
            return source.subject().trim();
        }

        public String sourceName() {
            return source.year() + "年408真题";
        }

        public String sourceQuestionNo() {
            return String.valueOf(source.questionNumber());
        }
    }

    public sealed interface Result permits Accepted, Rejected {
    }

    public record Accepted(Prepared prepared) implements Result {
    }

    public record Rejected(Violation violation) implements Result {
    }

    /**
     * 校验顺序：来源身份 → 内容非空 → 选项切分 → 答案核对 → 组装消毒。
     * images 非空拒绝：v0 无资产表，带图题留待后续批次（不伪成功）。
     * v1 富内容路径：question_html 存在时为权威内容，纯文本字段仅作选项切分/派生核对；
     * 老记录（无 *_html）走原有纯文本组装，行为不变。
     */
    public static Result validate(NewdbQuestionRecord record, String sourceRef) {
        Integer year = record.year();
        Integer no = record.questionNumber();
        if (year == null || no == null) {
            return reject(year, no, "identity-missing");
        }
        String subject = record.subject();
        if (subject == null || !SUBJECT_WHITELIST.contains(subject.trim())) {
            return reject(year, no, "subject-not-in-whitelist");
        }
        String stem = record.question();
        if (stem == null || stem.isBlank()) {
            return reject(year, no, "stem-empty");
        }
        if (stem.length() > MAX_STEM_LENGTH) {
            return reject(year, no, "stem-too-long");
        }
        String analysis = record.analysis();
        if (analysis == null || analysis.isBlank()) {
            return reject(year, no, "analysis-empty");
        }
        if (record.images() != null && !record.images().isBlank()) {
            return reject(year, no, "images-not-supported-in-v0");
        }
        List<String> options = OptionSplitter.split(record.options())
                .orElse(null);
        if (options == null) {
            return reject(year, no, "options-unsplitable");
        }
        String answer = record.answer() == null ? "" : record.answer().trim();
        if (answer.length() != 1 || !ANSWER_KEYS.contains(answer)) {
            return reject(year, no, "answer-invalid");
        }
        if (record.questionHtml() != null && !record.questionHtml().isBlank()) {
            return validateRich(record, options, sourceRef);
        }
        String stemHtml = StemHtmlAssembler.withOptions(StemHtmlAssembler.stem(stem), options);
        String analysisHtml = StemHtmlAssembler.analysis(analysis);
        String cleanStem = HtmlSanitizer.clean(stemHtml);
        String cleanAnalysis = HtmlSanitizer.clean(analysisHtml);
        if (HtmlSanitizer.countDataKeys(cleanStem) != options.size()) {
            return reject(year, no, "sanitize-broke-structure");
        }
        return new Accepted(new Prepared(record, options, sourceRef,
                cleanStem, cleanAnalysis, HtmlSanitizer.toText(cleanStem)));
    }

    /**
     * 富内容路径：源片段（table/sup/sub + `\(`…`\)` 公式文本）白名单消毒，
     * 结构完整性 = 表格数与公式定界符对在消毒前后一致；夹带 img 明确拒绝（v0 无资产表）。
     * 派生 stem_text 仍从消毒后 raw 重建（RAG 派生文本与展示权威内容分离）。
     */
    private static Result validateRich(NewdbQuestionRecord record, List<String> options,
                                       String sourceRef) {
        Integer year = record.year();
        Integer no = record.questionNumber();
        String stemFragment = record.questionHtml();
        String optionsFragment = record.optionsHtml() == null ? "" : record.optionsHtml();
        String analysisFragment = record.analysisHtml() == null ? "" : record.analysisHtml();
        // 契约：富路径不绕过四选项——选项片段文本必须含 A./B./C./D. 各一次以上
        // （必要条件；选项数与答案字母契约仍由上方纯文本切分路径保证）
        if (!optionsFragment.isBlank()) {
            String optText = org.jsoup.Jsoup.parse(optionsFragment).text();
            for (String key : new String[]{"A.", "B.", "C.", "D."}) {
                if (!optText.contains(key)) {
                    return reject(year, no, "options-html-letter-missing");
                }
            }
        }
        String stemRaw = StemHtmlAssembler.richWithOptions(
                StemHtmlAssembler.richStem(stemFragment), optionsFragment);
        String analysisRaw = analysisFragment.isBlank()
                ? StemHtmlAssembler.analysis(record.analysis())
                : StemHtmlAssembler.richAnalysis(analysisFragment);
        if (HtmlSanitizer.hasImageTag(stemRaw) || HtmlSanitizer.hasImageTag(analysisRaw)) {
            return reject(year, no, "images-not-supported-in-v0");
        }
        String cleanStem = HtmlSanitizer.clean(stemRaw);
        String cleanAnalysis = HtmlSanitizer.clean(analysisRaw);
        if (HtmlSanitizer.countTables(cleanStem) != HtmlSanitizer.countTables(stemRaw)
                || HtmlSanitizer.countInlineFormulas(cleanStem) != HtmlSanitizer.countInlineFormulas(stemRaw)) {
            return reject(year, no, "sanitize-broke-structure");
        }
        return new Accepted(new Prepared(record, options, sourceRef,
                cleanStem, cleanAnalysis, HtmlSanitizer.toText(cleanStem)));
    }

    private static Result reject(Integer year, Integer no, String reason) {
        return new Rejected(new Violation(year, no, reason));
    }
}
