package com.mindskip.xzs.newdb.controller;

import com.mindskip.xzs.base.RestResponse;
import com.mindskip.xzs.newdb.config.NewdbDbClient;
import com.mindskip.xzs.newdb.domain.NewdbCountItem;
import com.mindskip.xzs.newdb.repository.NewdbImportBatchMapper;
import com.mindskip.xzs.newdb.repository.NewdbQuestionMapper;
import com.mindskip.xzs.newdb.viewmodel.NewdbStatsVM;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工作台统计：题目总数/批次数 + 按年份、题型、状态分布 + 最近批次时间。
 */
@RestController
@RequestMapping("/api/newdb")
@Profile("newdb")
public class NewdbStatsController {

    private final NewdbDbClient dbClient;

    public NewdbStatsController(NewdbDbClient dbClient) {
        this.dbClient = dbClient;
    }

    @GetMapping("/stats")
    public RestResponse<Object> stats() {
        long questionTotal = dbClient.withMapper(NewdbQuestionMapper.class, NewdbQuestionMapper::countAll);
        long batchTotal = dbClient.withMapper(NewdbImportBatchMapper.class, NewdbImportBatchMapper::countAll);
        LocalDateTime lastBatchAt = dbClient.withMapper(NewdbImportBatchMapper.class,
                NewdbImportBatchMapper::lastStartedAt);
        List<NewdbStatsVM.StatItem> byYear = dbClient.withMapper(NewdbQuestionMapper.class,
                m -> toStatItems(m.countByYear()));
        List<NewdbStatsVM.StatItem> byType = dbClient.withMapper(NewdbQuestionMapper.class,
                m -> toStatItems(m.countByType()));
        List<NewdbStatsVM.StatItem> byStatus = dbClient.withMapper(NewdbQuestionMapper.class,
                m -> toStatItems(m.countByStatus()));
        return RestResponse.ok(new NewdbStatsVM(questionTotal, batchTotal, byYear, byType, byStatus,
                lastBatchAt));
    }

    private static List<NewdbStatsVM.StatItem> toStatItems(List<NewdbCountItem> items) {
        return items.stream()
                .map(i -> new NewdbStatsVM.StatItem(i.getStatKey(), i.getStatCount()))
                .toList();
    }
}
