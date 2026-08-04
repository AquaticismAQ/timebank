package cn.timebank.dataexchange.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * D5's "executor isolation" requirement — a 500k-row import must not
 * degrade p95 latency for interactive users (R11), so background jobs
 * never run on the request-handling pool (Tomcat's own virtual-thread
 * executor) or Spring's unbounded default {@code SimpleAsyncTaskExecutor}.
 * Small and bounded on purpose: this box runs at 2 CPUs / 3.8 GB total
 * (`00-Status.md` §11) — two concurrent heavy jobs is already a lot here.
 */
@Configuration
@EnableAsync
public class JobExecutorConfig {

    public static final String JOB_EXECUTOR = "jobExecutor";

    @Bean(JOB_EXECUTOR)
    public TaskExecutor jobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("job-exec-");
        executor.initialize();
        return executor;
    }
}
