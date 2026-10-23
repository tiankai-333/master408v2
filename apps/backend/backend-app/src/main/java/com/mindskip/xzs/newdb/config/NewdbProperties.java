package com.mindskip.xzs.newdb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * 新库（master408 v0）切片配置，全部来自环境变量（见 application-newdb.yml）。
 * 不提供默认口令：admin 账号未配置时登录端点恒 401。
 * 仅在 newdb profile 下注册（NewdbWebConfig @EnableConfigurationProperties）。
 */
@ConfigurationProperties(prefix = "newdb")
public record NewdbProperties(Ds ds, Admin admin, @DefaultValue("8h") Duration tokenTtl,
                              Importer importer, PaperImporter paperImporter) {

    /**
     * 新库连接（与旧库主数据源 spring.datasource 相互独立，见 NewdbDbClient）。
     */
    public record Ds(String url, String username, String password, @DefaultValue("4") int maximumPoolSize) {
    }

    /**
     * 管理端临时单账号（无用户表，用户域入库后替换）。
     */
    public record Admin(String username, String password) {
    }

    /**
     * 导入门控：enabled=false 时 CommandLineRunner 直接返回。
     */
    public record Importer(boolean enabled, String file, List<Integer> years,
                           @DefaultValue("5") int perYearLimit, String batchTag) {
    }

    /**
     * 整卷导入门控（M1，候选库专用）：enabled=false 时不执行；
     * dir 为提取器 JSON 目录，SQL 含候选库扩展列，库必须为 master408_candidate；
     * assetsDir 为本地题目配图副本目录（NewdbAssetController 只读 serve）。
     */
    public record PaperImporter(boolean enabled, String dir,
                                @DefaultValue("m1-papers-2015-2024") String batchTag,
                                @DefaultValue String assetsDir) {
    }
}
