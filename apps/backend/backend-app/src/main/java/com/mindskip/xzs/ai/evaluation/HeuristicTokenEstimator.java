package com.mindskip.xzs.ai.evaluation;

/**
 * Provider-neutral fallback used only when exact usage metadata is unavailable.
 *
 * <p>The estimate deliberately remains simple and transparent: a CJK code point is
 * counted as one token, four ASCII letters/digits as one token, and two remaining
 * symbols as one token. It is suitable for relative baselines, not billing.</p>
 */
public final class HeuristicTokenEstimator {

    private HeuristicTokenEstimator() {
    }

    public static int estimate(String... values) {
        int cjk = 0;
        int asciiWordCharacters = 0;
        int other = 0;
        if (values == null) {
            return 0;
        }
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                continue;
            }
            for (int offset = 0; offset < value.length(); ) {
                int codePoint = value.codePointAt(offset);
                offset += Character.charCount(codePoint);
                if (isCjk(codePoint)) {
                    cjk++;
                } else if (codePoint < 128 && Character.isLetterOrDigit(codePoint)) {
                    asciiWordCharacters++;
                } else if (!Character.isWhitespace(codePoint)) {
                    other++;
                }
            }
        }
        return cjk + ceilDiv(asciiWordCharacters, 4) + ceilDiv(other, 2);
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static int ceilDiv(int value, int divisor) {
        return value == 0 ? 0 : (value + divisor - 1) / divisor;
    }
}
