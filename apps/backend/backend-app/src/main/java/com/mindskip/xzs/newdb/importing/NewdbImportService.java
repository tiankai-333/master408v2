package com.mindskip.xzs.newdb.importing;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindskip.xzs.newdb.config.NewdbDbClient;
import com.mindskip.xzs.newdb.config.NewdbProperties;
import com.mindskip.xzs.newdb.domain.NewdbImportBatchDO;
import com.mindskip.xzs.newdb.domain.NewdbQuestionDO;
import com.mindskip.xzs.newdb.repository.NewdbImportBatchMapper;
import com.mindskip.xzs.newdb.repository.NewdbQuestionMapper;
import com.mindskip.xzs.newdb.repository.NewdbSubjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 导入管道（D-31：新库题目唯一写入者）：JSON → 校验/组装/消毒 → import_batch + question。
 * 幂等：source_ref 唯一键，重放时已存在行按 duplicate 计数跳过；batch_tag 重放复用原批次。
 * 坏样本逐条拒绝并记录原因码；结构性错误（文件缺失/JSON 损坏）整批中止，不写任何行。
 */
@Component
@Profile("newdb")
public class NewdbImportService {

    private static final Logger log = LoggerFactory.getLogger(NewdbImportService.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * 导入结果：全部计数 + 拒绝明细（机读原因码）。
     */
    public record ImportReport(Path file, int candidates, int imported, int duplicates,
                               int rejected, List<String> rejectionDetails) {
        public String summarize() {
            return "candidate=%d imported=%d duplicate=%d rejected=%d".formatted(
                    candidates, imported, duplicates, rejected);
        }
    }

    private final NewdbProperties properties;
    private final NewdbDbClient dbClient;

    public NewdbImportService(NewdbProperties properties, NewdbDbClient dbClient) {
        this.properties = properties;
        this.dbClient = dbClient;
    }

    public ImportReport run() {
        NewdbProperties.Importer conf = properties.importer();
        Path file = Path.of(conf.file()).toAbsolutePath();
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("import file not found: " + file);
        }
        log.info("newdb import start: file={}, years={}, perYearLimit={}, batchTag={}",
                file, conf.years(), conf.perYearLimit(), conf.batchTag());

        List<NewdbQuestionRecord> records = parse(file);
        Set<Integer> years = Set.copyOf(conf.years());
        List<NewdbQuestionRecord> candidates = records.stream()
                .filter(r -> r.year() != null && years.contains(r.year()))
                .filter(r -> r.questionNumber() != null && r.questionNumber() <= conf.perYearLimit())
                .toList();

        List<NewdbQuestionValidator.Prepared> prepared = new ArrayList<>();
        List<String> rejections = new ArrayList<>();
        Set<String> seenRefs = new HashSet<>();
        for (NewdbQuestionRecord record : candidates) {
            String sourceRef = sourceRefOf(record);
            switch (NewdbQuestionValidator.validate(record, sourceRef)) {
                case NewdbQuestionValidator.Rejected rejected ->
                        rejections.add(rejected.violation().describe());
                case NewdbQuestionValidator.Accepted accepted -> {
                    if (!seenRefs.add(sourceRef)) {
                        rejections.add(record.year() + "-Q" + record.questionNumber()
                                + ": source-ref-duplicated-in-batch");
                    } else {
                        prepared.add(accepted.prepared());
                    }
                }
            }
        }

        ImportReport report = writeBatch(conf, prepared, candidates.size(), rejections);
        log.info("newdb import report: {} file={}", report.summarize(), report.file());
        report.rejectionDetails().forEach(d -> log.warn("newdb import rejected: {}", d));
        return report;
    }

    /**
     * 整批一个事务：批次行（重放复用）→ 逐题 upsert 学科、按 source_ref 幂等写题 → 批次收尾。
     */
    private ImportReport writeBatch(NewdbProperties.Importer conf,
                                    List<NewdbQuestionValidator.Prepared> prepared,
                                    int candidateCount, List<String> rejections) {
        return dbClient.inSession(session -> {
            NewdbImportBatchMapper batchMapper = session.getMapper(NewdbImportBatchMapper.class);
            NewdbQuestionMapper questionMapper = session.getMapper(NewdbQuestionMapper.class);
            NewdbSubjectMapper subjectMapper = session.getMapper(NewdbSubjectMapper.class);

            NewdbImportBatchDO batch = batchMapper.selectByTag(conf.batchTag());
            long batchId;
            if (batch == null) {
                batch = new NewdbImportBatchDO();
                batch.setBatchTag(conf.batchTag());
                batch.setSourceType("seed");
                batch.setStartedAt(LocalDateTime.now());
                batchMapper.insert(batch);
                batchId = batch.getId();
            } else {
                batchId = batch.getId();
            }

            int imported = 0;
            int duplicates = 0;
            for (NewdbQuestionValidator.Prepared item : prepared) {
                subjectMapper.upsertByName(item.subject());
                Integer subjectId = subjectMapper.idByName(item.subject());
                if (questionMapper.selectBySourceRef(item.sourceRef()) != null) {
                    duplicates++;
                    continue;
                }
                questionMapper.insert(toRow(item, subjectId, batchId));
                imported++;
            }
            String note = "replay-safe summary: " + "candidate=%d imported=%d duplicate=%d rejected=%d"
                    .formatted(candidateCount, imported, duplicates, rejections.size());
            batchMapper.markFinished(batchId, LocalDateTime.now(), note);
            return new ImportReport(Path.of(conf.file()), candidateCount, imported, duplicates,
                    rejections.size(), rejections);
        });
    }

    private static NewdbQuestionDO toRow(NewdbQuestionValidator.Prepared item, Integer subjectId, long batchId) {
        NewdbQuestionDO row = new NewdbQuestionDO();
        row.setQuestionType(1);
        row.setSubjectId(subjectId);
        // 源 JSON 无分值事实，不伪称（score=NULL，页面显示“—”）；后续批次有来源再补
        row.setScore(null);
        row.setDifficult(null);
        row.setSourceName(item.sourceName());
        row.setSourceYear(item.source().year());
        row.setSourceQuestionNo(item.sourceQuestionNo());
        // 页面 URL 真实（crawler/config.json 在册）；#q{n} 是本导入器自定义约定，站点无题目锚点
        row.setSourceRef(item.sourceRef());
        row.setStemHtml(item.stemHtml());
        row.setAnalysisHtml(item.analysisHtml());
        row.setStemText(item.stemText());
        row.setCorrect(item.correct());
        row.setAnswerText(null);
        row.setStatus(1);
        row.setStatusReason(null);
        // 上线阀：校验链已保证 4 选项齐全 + correct 非空 + 消毒结构完整（D-33 纪律 2）
        row.setDisplayReady(1);
        row.setLicenseTag("crawler-review");
        row.setBatchId(batchId);
        return row;
    }

    /**
     * source_ref = 真实页面 URL + 自定义题号锚点；数据集内 (year, question_number) 唯一（已实测），
     * 故可作导入幂等业务键。
     */
    private static String sourceRefOf(NewdbQuestionRecord record) {
        return "https://www.csgraduates.com/study_methods/408quiz/" + record.year()
                + "/#q" + record.questionNumber();
    }

    private static List<NewdbQuestionRecord> parse(Path file) {
        try {
            return List.of(JSON.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    NewdbQuestionRecord[].class));
        } catch (Exception e) {
            // 结构性错误：整批中止（inSession 之外抛出，不写任何行）
            throw new IllegalStateException("import file unreadable or invalid JSON: " + file, e);
        }
    }
}
