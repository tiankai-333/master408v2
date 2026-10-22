package com.mindskip.xzs.newdb.viewmodel;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工作台统计。
 */
public record NewdbStatsVM(long questionTotal, long batchTotal, List<StatItem> byYear,
                           List<StatItem> byType, List<StatItem> byStatus,
                           LocalDateTime lastBatchAt) {

    public record StatItem(String key, long count) {
    }
}
