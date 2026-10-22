package com.mindskip.xzs.newdb.domain;

/**
 * GROUP BY 统计行（statKey = 年份/题型/状态的原值，statCount = 行数）。
 * XML 侧列别名 stat_key / stat_count，靠 mapUnderscoreToCamelCase 对上。
 */
public class NewdbCountItem {

    private String statKey;
    private long statCount;

    public String getStatKey() {
        return statKey;
    }

    public void setStatKey(String statKey) {
        this.statKey = statKey;
    }

    public long getStatCount() {
        return statCount;
    }

    public void setStatCount(long statCount) {
        this.statCount = statCount;
    }
}
