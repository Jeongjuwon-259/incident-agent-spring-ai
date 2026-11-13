package com.samsung.gsre.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 비동기 실행 설정. 품질 평가 배치는 별도 스레드에서 돌려 웹 요청을 막지 않는다. */
@Configuration
public class AsyncConfig {

    @Bean("evalExecutor")
    public TaskExecutor evalExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("eval-");
        executor.initialize();
        return executor;
    }
}
