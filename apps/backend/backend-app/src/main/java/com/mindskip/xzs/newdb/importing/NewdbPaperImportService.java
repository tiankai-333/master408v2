package com.mindskip.xzs.newdb.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindskip.xzs.newdb.config.NewdbDbClient;
import com.mindskip.xzs.newdb.config.NewdbProperties;
import com.mindskip.xzs.newdb.domain.NewdbImportBatchDO;
import com.mindskip.xzs.newdb.domain.NewdbPaperDO;
import com.mindskip.xzs.newdb.domain.NewdbQuestionDO;
import com.mindskip.xzs.newdb.repository.NewdbImportBatchMapper;
import com.mindskip.xzs.newdb.repository.NewdbPaperMapper;
import com.mindskip.xzs.newdb.repository.NewdbSubjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 整卷导入管道（M1）：提取器 JSON 目录 → 逐卷校验/消毒/组装 → import_batch + question + paper。
 * 幂等：question 以 source_ref 唯一键重放跳过；paper 以 name 幂等（重放刷新结构列）。
 * 隔离粒度：一卷一事务——单卷结构异常整卷回滚，不影响已导入卷（可恢复、可重跑）。
 * 候选库专用：SQL 含扩展列，NEWDB_DB_URL 必须指向 master408_candidate。
 */
@Component
@Profile("newdb")
public class NewdbPaperImportService {

    private static final Logger log = LoggerFactory.getLogger(NewdbPaperImportService.class);
    // 与记录模型共用同一 mapper：snake_case 命名策略在 NewdbPaperRecord.MAPPER 上
    private static final ObjectMapper JSON = NewdbPaperRecord.MAPPER;
    private static final int EXPECTED_TOTAL = 47;

    public record PaperReport(String paperName, int year, int imported, int duplicates,
                              int updated, int missingAnalysis, int warnings, String status) {
        public String summarize() {
            return "%s(%d): %s imported=%d duplicate=%d updated=%d missingAnalysis=%d warnings=%d"
                    .formatted(paperName, year, status, imported, duplicates, updated,
                            missingAnalysis, warnings);
        }
    }

    public record RunReport(Path dir, int papers, int imported, int duplicates, int updated,
                            int rejectedPapers, List<PaperReport> details) {
        public String summarize() {
            return "papers=%d imported=%d duplicate=%d updated=%d rejectedPapers=%d"
                    .formatted(papers, imported, duplicates, updated, rejectedPapers);
        }
    }

    private final NewdbProperties properties;
    private final NewdbDbClient dbClient;

    public NewdbPaperImportService(NewdbProperties properties, NewdbDbClient dbClient) {
        this.properties = properties;
        this.dbClient = dbClient;
    }

    public RunReport run() {
        NewdbProperties.PaperImporter conf = properties.paperImporter();
        if (conf == null) {
            throw new IllegalStateException("newdb.paper-importer 未配置");
        }
        Path dir = Path.of(conf.dir()).toAbsolutePath();
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("paper import dir not found: " + dir);
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(p -> p.getFileName().toString().matches("paper-\\d{4}\\.json"))
                    .sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("paper import dir unreadable: " + dir, e);
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException("no paper-*.json in " + dir);
        }
        log.info("paper import start: dir={}, files={}, batchTag={}",
                dir, files.size(), conf.batchTag());

        int totalImported = 0;
        int totalDuplicates = 0;
        int totalUpdated = 0;
        int rejected = 0;
        List<PaperReport> details = new ArrayList<>();
        for (Path file : files) {
            try {
                PaperReport report = importOne(conf, file);
                details.add(report);
                totalImported += report.imported();
                totalDuplicates += report.duplicates();
                totalUpdated += report.updated();
                log.info("paper import: {}", report.summarize());
            } catch (PaperRejectedException e) {
                rejected++;
                details.add(e.report);
                log.warn("paper import rejected: {} — {}", file.getFileName(), e.getMessage());
            } catch (RuntimeException e) {
                // 单卷 DB 层失败（如约束冲突）：事务已在 inSession 内回滚，
                // 转为该卷拒绝，不杀应用、不影响剩余卷（可恢复，异常可追踪）
                rejected++;
                String year = file.getFileName().toString().replaceAll("\\D", "");
                PaperReport report = new PaperReport(
                        "paper-" + (year.isBlank() ? "?" : year), parseIntSafe(year), 0, 0, 0, 0, 0,
                        "DB-ERROR");
                details.add(report);
                log.warn("paper import db-failed (rolled back): {} — {}",
                        file.getFileName(), e.getMessage());
            }
        }
        RunReport report = new RunReport(dir, files.size(), totalImported, totalDuplicates,
                totalUpdated, rejected, List.copyOf(details));
        log.info("paper import done: {}", report.summarize());
        return report;
    }

    private static final class PaperRejectedException extends RuntimeException {
        private final PaperReport report;

        PaperRejectedException(PaperReport report, String message) {
            super(message);
            this.report = report;
        }
    }

    private PaperReport importOne(NewdbProperties.PaperImporter conf, Path file) {
        NewdbPaperRecord paper = parse(file);
        NewdbPaperValidator.Result result = NewdbPaperValidator.validate(paper);
        if (result instanceof NewdbPaperValidator.Rejected rejected) {
            PaperReport report = new PaperReport(paper.paperName(), paper.year(), 0, 0, 0, 0,
                    paper.warnings() == null ? 0 : paper.warnings().size(), "REJECTED");
            throw new PaperRejectedException(report, String.join("; ", rejected.violations()));
        }
        NewdbPaperValidator.PreparedPaper prepared =
                ((NewdbPaperValidator.Accepted) result).prepared();

        return dbClient.inSession(session -> {
            NewdbImportBatchMapper batchMapper = session.getMapper(NewdbImportBatchMapper.class);
            NewdbSubjectMapper subjectMapper = session.getMapper(NewdbSubjectMapper.class);
            NewdbPaperMapper paperMapper = session.getMapper(NewdbPaperMapper.class);

            NewdbImportBatchDO batch = batchMapper.selectByTag(conf.batchTag());
            long batchId;
            if (batch == null) {
                batch = new NewdbImportBatchDO();
                batch.setBatchTag(conf.batchTag());
                batch.setSourceType("paper_extract");
                batch.setStartedAt(LocalDateTime.now());
                batchMapper.insert(batch);
                batchId = batch.getId();
            } else {
                batchId = batch.getId();
            }

            int imported = 0;
            int duplicates = 0;
            int updated = 0;
            int missingAnalysis = 0;
            List<Long> questionIds = new ArrayList<>();
            for (NewdbPaperValidator.PreparedQuestion item : prepared.questions()) {
                subjectMapper.upsertByName(item.subject());
                // 必须用候选库专用查询（含 assets_json 扩展列）——旧查询缺列会把
                // 内容修复误判为全行变化
                NewdbQuestionDO existing = paperMapper.selectQuestionBySourceRef(item.sourceRef());
                if (existing != null) {
                    questionIds.add(existing.getId());
                    duplicates++;
                    if (item.analysisHtml() == null) {
                        missingAnalysis++;
                    }
                    // 内容修复检测：与新准备结果不一致则按新批次整行更新（id 不变，
                    // paper.question_ids 稳定）——缺图恢复等修正不能因 source_ref 跳过而修不到
                    if (contentDiffers(existing, item, batchId)) {
                        NewdbQuestionDO fixed = toRow(item, subjectMapper.idByName(item.subject()),
                                prepared, batchId);
                        fixed.setId(existing.getId());
                        paperMapper.updateQuestionContent(fixed);
                        updated++;
                    }
                    continue;
                }
                NewdbQuestionDO row = toRow(item, subjectMapper.idByName(item.subject()),
                        prepared, batchId);
                paperMapper.insertWithAssets(row);
                questionIds.add(row.getId());
                imported++;
                if (item.analysisHtml() == null) {
                    missingAnalysis++;
                }
            }
            if (questionIds.size() != EXPECTED_TOTAL) {
                throw new IllegalStateException(
                        "写库后题数 %d ≠ %d，整卷回滚".formatted(questionIds.size(), EXPECTED_TOTAL));
            }

            NewdbPaperDO paperRow = new NewdbPaperDO();
            paperRow.setName(prepared.paperName());
            paperRow.setPaperType(prepared.paperType());
            paperRow.setSourceYear(prepared.year());
            paperRow.setQuestionIds(toJson(questionIds));
            paperRow.setQuestionCount(questionIds.size());
            paperRow.setBatchId(batchId);
            paperRow.setSourceSha256(prepared.sourceSha256());
            paperRow.setSourceUrl(prepared.sourceUrl());
            paperRow.setWarningCount(prepared.warnings().size());
            paperRow.setWarningsJson(toJson(prepared.warnings()));

            NewdbPaperDO existingPaper = paperMapper.selectByName(prepared.paperName());
            if (existingPaper == null) {
                paperMapper.insert(paperRow);
            } else {
                paperMapper.updateContent(existingPaper.getId(), paperRow);
            }
            String note = "paper replay-safe: %s imported=%d duplicate=%d".formatted(
                    prepared.paperName(), imported, duplicates);
            batchMapper.markFinished(batchId, LocalDateTime.now(), note);
            return new PaperReport(prepared.paperName(), prepared.year(), imported, duplicates,
                    updated, missingAnalysis, prepared.warnings().size(), "OK");
        });
    }

    private static NewdbQuestionDO toRow(NewdbPaperValidator.PreparedQuestion item,
                                         Integer subjectId,
                                         NewdbPaperValidator.PreparedPaper prepared, long batchId) {
        NewdbQuestionDO row = new NewdbQuestionDO();
        row.setQuestionType(item.questionType());
        row.setSubjectId(subjectId);
        row.setScore(null); // 来源无分值事实，不伪称
        row.setDifficult(null);
        row.setSourceName(prepared.paperName());
        row.setSourceYear(prepared.year());
        row.setSourceQuestionNo(String.valueOf(item.no()));
        row.setSourceRef(item.sourceRef());
        row.setStemHtml(item.stemHtml());
        row.setAnalysisHtml(item.analysisHtml());
        row.setStemText(item.stemText());
        row.setCorrect(item.correct());
        row.setAnswerText(null);
        row.setStatus(1);
        row.setStatusReason(item.statusReason());
        // 上线阀：单选校验链保证 4 选项+答案齐；综合题解析缺失时为 0（纪律2）
        row.setDisplayReady(Integer.parseInt(item.displayReady()));
        row.setLicenseTag("crawler-review");
        row.setBatchId(batchId);
        row.setAssetsJson(item.assetsJson());
        return row;
    }

    /**
     * 内容修复检测：候选行与新准备结果在内容口径上是否不一致
     * （assets/上线阀/原因码/解析/题干——与 id、批次、时间戳无关）。
     */
    private static boolean contentDiffers(NewdbQuestionDO existing,
                                          NewdbPaperValidator.PreparedQuestion item, long batchId) {
        return !java.util.Objects.equals(existing.getStemHtml(), item.stemHtml())
                || !java.util.Objects.equals(existing.getAnalysisHtml(), item.analysisHtml())
                || !java.util.Objects.equals(existing.getCorrect(), item.correct())
                || !java.util.Objects.equals(existing.getAssetsJson(), item.assetsJson())
                || !java.util.Objects.equals(existing.getStatusReason(), item.statusReason())
                || existing.getDisplayReady() == null
                || existing.getDisplayReady() != Integer.parseInt(item.displayReady());
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static NewdbPaperRecord parse(Path file) {
        try {
            return JSON.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    NewdbPaperRecord.class);
        } catch (Exception e) {
            throw new IllegalStateException("paper file unreadable or invalid JSON: " + file, e);
        }
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("json serialize failed", e);
        }
    }
}
