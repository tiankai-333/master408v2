package com.mindskip.xzs.newdb.importing;

import com.mindskip.xzs.newdb.config.NewdbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 导入入口：CommandLineRunner，由 newdb.importer.enabled 门控（默认 false）。
 * 带 newdb profile 启动不等于必然重放导入；开启后可反复执行以验证幂等重放。
 */
@Component
@Profile("newdb")
public class NewdbImportRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(NewdbImportRunner.class);

    private final NewdbProperties properties;
    private final NewdbImportService importService;

    public NewdbImportRunner(NewdbProperties properties, NewdbImportService importService) {
        this.properties = properties;
        this.importService = importService;
    }

    @Override
    public void run(String... args) {
        if (!properties.importer().enabled()) {
            return;
        }
        NewdbImportService.ImportReport report = importService.run();
        log.info("newdb import done: {}", report.summarize());
    }
}
