package com.mindskip.xzs.newdb.importing;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 爬虫 JSON 里的 options 是连体字符串（如 "A.O(logn)B.O(n)C.O(nlog2n)D.O(n2)"），
 * 需切成 4 段独立选项。
 *
 * 算法：顺序贪婪扫描，依次定位 A. / B. / C. / D. 标记（各在前一个标记之后），取标记间文本。
 * 数据集全量验证（v1-reference/crawler/data/exam_questions.json，560 条）：559 条切成
 * 4 段非空，唯一失败为空串坏样本——失败即拒绝，不猜测。
 * 刻意不用负向断言（(?<![A-Za-z])B.）：会误伤选项内容含 IIB. / CDROM 的题目（2011 Q8/Q14 实测）。
 */
public final class OptionSplitter {

    private static final String[] KEYS = {"A.", "B.", "C.", "D."};

    private OptionSplitter() {
    }

    /**
     * @return 4 段非空选项；无法可靠切分时返回 empty（调用方按 options-unsplitable 拒绝）
     */
    public static Optional<List<String>> split(String joined) {
        if (joined == null || joined.isBlank()) {
            return Optional.empty();
        }
        int[] positions = new int[KEYS.length];
        int from = 0;
        for (int i = 0; i < KEYS.length; i++) {
            int index = joined.indexOf(KEYS[i], from);
            if (index < 0) {
                return Optional.empty();
            }
            positions[i] = index;
            from = index + KEYS[i].length();
        }
        List<String> options = new ArrayList<>(KEYS.length);
        for (int i = 0; i < KEYS.length; i++) {
            int start = positions[i] + KEYS[i].length();
            int end = i < KEYS.length - 1 ? positions[i + 1] : joined.length();
            String segment = joined.substring(start, end).trim();
            if (segment.isEmpty()) {
                return Optional.empty();
            }
            options.add(segment);
        }
        return Optional.of(options);
    }
}
