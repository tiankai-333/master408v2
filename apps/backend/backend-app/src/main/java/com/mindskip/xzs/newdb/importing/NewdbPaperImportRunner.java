package com.mindskip.xzs.newdb.importing;

import com.mindskip.xzs.newdb.config.NewdbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 整卷导入入口（M1）：CommandLineRunner，由 newdb.paper-importer.enabled 门控（默认 false）。
 * 幂等可重跑：重放时题目按 source_ref 计 duplicate，paper 按名刷新结构列。
 */
@Component
@Profile("newdb")
public class NewdbPaperImportRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(NewdbPaperImportRunner.class);

    private final NewdbProperties properties;
    private final NewdbPaperImportService importService;

    public NewdbPaperImportRunner(NewdbProperties properties, NewdbPaperImportService importService) {
        this.properties = properties;
        this.importService = importService;
    }

    @Override
    public void run(String... args) {
        NewdbProperties.PaperImporter conf = properties.paperImporter();
        if (conf == null || !conf.enabled()) {
            return;
        }
        NewdbPaperImportService.RunReport report = importService.run();
        log.info("paper import runner done: {}", report.summarize());
        report.details().forEach(d -> log.info("paper import detail: {}", d.summarize()));
    }
}
