package com.mindskip.xzs.newdb.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * newdb profile 的装配入口：启用 NewdbProperties。
 * 鉴权由 NewdbAuthFilter（Servlet Filter）承担——旧 MVC 配置继承
 * WebMvcConfigurationSupport，不委托 WebMvcConfigurer，故不用拦截器方式。
 * 不带 profile 时本配置与 newdb 包内控制器整体缺席（/api/newdb/* 404），旧链路不变。
 */
@Configuration
@Profile("newdb")
@EnableConfigurationProperties(NewdbProperties.class)
public class NewdbWebConfig {
}
