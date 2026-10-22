package com.mindskip.xzs.newdb.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * 新库（master408 v0）专用数据库客户端。
 *
 * 设计约束（specs/2026-10-13 plan D1/D2）：Boot 4 的 MyBatis / Flyway 自动装配依赖
 * {@code @ConditionalOnSingleCandidate(DataSource)}——若把新库注册为 Spring DataSource
 * 或 SqlSessionFactory bean，旧链路自动装配会整体退避。因此本组件在内部编程式构建
 * HikariDataSource 与 SqlSessionFactory，不暴露任何 bean；新库 mapper 一律经
 * {@code openSession().getMapper()} 获取，不参与 {@code @MapperScan}。
 * Flyway 零感知新库：master408 无 flyway_schema_history 是隔离证据之一。
 */
@Component
@org.springframework.context.annotation.Profile("newdb")
public class NewdbDbClient implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NewdbDbClient.class);

    private final HikariDataSource dataSource;
    private final SqlSessionFactory sqlSessionFactory;

    public NewdbDbClient(NewdbProperties properties) throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.ds().url());
        config.setUsername(properties.ds().username());
        config.setPassword(properties.ds().password());
        config.setMaximumPoolSize(properties.ds().maximumPoolSize());
        config.setPoolName("newdb-pool");
        this.dataSource = new HikariDataSource(config);

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(this.dataSource);
        // mapper XML 放 classpath:mapper/newdb/ 子目录：旧工厂的 classpath:mapper/*.xml
        // glob 不跨目录（application.yml mybatis.mapper-locations），双向隔离
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath:mapper/newdb/*.xml"));
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        this.sqlSessionFactory = factoryBean.getObject();
        log.info("newdb datasource ready: {}", properties.ds().url());
    }

    /**
     * 写路径：显式事务边界，正常 commit、RuntimeException 回滚。
     */
    public <T> T inSession(Function<SqlSession, T> work) {
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            try {
                T result = work.apply(session);
                session.commit();
                return result;
            } catch (RuntimeException e) {
                session.rollback();
                throw e;
            }
        }
    }

    /**
     * 读路径：自动提交的便捷封装。
     */
    public <M, T> T withMapper(Class<M> mapperType, Function<M, T> work) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return work.apply(session.getMapper(mapperType));
        }
    }

    @Override
    public void destroy() {
        dataSource.close();
    }
}
