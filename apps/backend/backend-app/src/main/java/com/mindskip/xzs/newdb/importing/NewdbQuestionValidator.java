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

    private static Result reject(Integer year, Integer no, String reason) {
        return new Rejected(new Violation(year, no, reason));
    }
}
