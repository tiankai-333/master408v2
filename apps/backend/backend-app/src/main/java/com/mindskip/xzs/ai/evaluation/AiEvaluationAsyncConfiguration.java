package com.mindskip.xzs.ai.evaluation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * A deliberately small, bounded executor protects model quota and application threads.
 *
 * <p>This is a single-instance baseline. A distributed deployment should replace it
 * with a durable queue and separately scalable evaluation workers.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class AiEvaluationAsyncConfiguration {

    @Bean(name = "aiEvaluationExecutor")
    Executor aiEvaluationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("ai-evaluation-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
